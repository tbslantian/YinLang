package app.soundshelf;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;

@UnstableApi
final class AirPlayPlayback {
    final ExoPlayer player;

    AirPlayPlayback(Context context, AirPlaySession target, String address, Bundle headers, CachedAudio cache, boolean hls) {
        var transport = new PcmSink(target);
        var renderers = new DefaultRenderersFactory(context) {
            @Override protected AudioSink buildAudioSink(Context context, boolean floats, boolean platformSpeed) {
                var sonic = new SonicAudioProcessor();
                var processor = new TeeAudioProcessor(transport);
                var chain = new DefaultAudioSink.DefaultAudioProcessorChain(
                    new AudioProcessor[0], new SilenceSkippingAudioProcessor(), sonic) {
                    @Override public AudioProcessor[] getAudioProcessors() {
                        var standard = super.getAudioProcessors();
                        var processors = Arrays.copyOf(standard, standard.length + 1);
                        processors[standard.length] = processor;
                        return processors;
                    }
                };
                var sink = new DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(false).setAudioProcessorChain(chain).build();
                return new ForwardingAudioSink(sink) {
                    @Override public int getFormatSupport(Format format) {
                        return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)
                            ? super.getFormatSupport(format) : AudioSink.SINK_FORMAT_UNSUPPORTED;
                    }
                    @Override public boolean supportsFormat(Format format) {
                        return getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED;
                    }
                };
            }
        };
        DataSource.Factory data;
        if (cache != null) {
            data = () -> new CacheDataSource(cache);
        } else {
            var requestHeaders = new HashMap<String, String>();
            if (headers != null) for (String key : headers.keySet()) requestHeaders.put(key, headers.getString(key, ""));
            var http = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(requestHeaders)
                .setAllowCrossProtocolRedirects(true);
            data = new DefaultDataSource.Factory(context, http);
        }
        player = new ExoPlayer.Builder(context, renderers)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        player.setVolume(0f);
        var item = new MediaItem.Builder().setUri(cache == null ? Uri.parse(address) : Uri.parse("yinlang-cache:/audio"));
        if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
    }

    private static final class PcmSink implements TeeAudioProcessor.AudioBufferSink {
        private final AirPlaySession target;
        private final short[] samples = new short[2048];
        private int channels, encoding;

        PcmSink(AirPlaySession target) { this.target = target; }

        @Override public void flush(int rate, int channels, int encoding) {
            this.channels = channels;
            this.encoding = encoding;
            if (rate <= 0 || channels < 1 || encoding != C.ENCODING_PCM_16BIT)
                throw new IllegalStateException("投送音频格式不支持：" + rate + " Hz / " + encoding);
            // The native sender resamples to RAOP's 44.1 kHz. Never silently drop
            // decoded 48/96 kHz buffers when Sonic is inactive or reconfigured.
            target.setSampleRate(rate);
            android.util.Log.i("YinLangCast", "PCM input: " + rate + " Hz, " + channels + " channels");
        }

        @Override public void handleBuffer(ByteBuffer bytes) {
            if (channels < 1 || encoding != C.ENCODING_PCM_16BIT) return;
            var pcm = bytes.order(ByteOrder.nativeOrder()).asShortBuffer();
            while (pcm.remaining() >= channels) {
                int count = 0;
                while (count < samples.length && pcm.remaining() >= channels) {
                    short left = pcm.get();
                    samples[count++] = left;
                    samples[count++] = channels == 1 ? left : pcm.get();
                    if (channels > 2) pcm.position(pcm.position() + channels - 2);
                }
                if (!target.audio(samples, count)) break;
            }
        }
    }

    private static final class CacheDataSource extends BaseDataSource {
        private final CachedAudio cache;
        private Uri uri;
        private long position, remaining;

        CacheDataSource(CachedAudio cache) { super(false); this.cache = cache; }

        @Override public long open(DataSpec spec) throws IOException {
            transferInitializing(spec);
            position = spec.position;
            remaining = cache.getSize() - position;
            if (remaining < 0) throw new IOException("读取位置超出音频大小");
            if (spec.length != C.LENGTH_UNSET) remaining = Math.min(remaining, spec.length);
            uri = spec.uri;
            transferStarted(spec);
            return remaining;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return C.RESULT_END_OF_INPUT;
            int read = cache.readAt(position, buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                position += read;
                remaining -= read;
                bytesTransferred(read);
            }
            return read;
        }

        @Override public Uri getUri() { return uri; }
        @Override public void close() {
            if (uri == null) return;
            uri = null;
            transferEnded();
        }
    }
}
