package app.soundshelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.VolumeProvider;
import androidx.media3.common.Player;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends Service {
    static PlaybackService active;
    private static QueueRequest requested;
    private record Queue(List<MediaEntry> entries, int index, boolean book) {}
    private record QueueRequest(Queue queue, PlayOrder.Mode mode, int position) {}
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer media;
    private AirPlaySession airPlay;
    private AirPlayPlayback castPlayback;
    private DlnaController.Session dlna;
    private DlnaStreamServer dlnaStream;
    private final java.util.concurrent.ExecutorService dlnaIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int dlnaRevision;
    private boolean dlnaPlaying, dlnaPolling;
    private int dlnaPosition, dlnaDuration, dlnaFailures;
    private long dlnaPositionAt;
    private PowerManager.WakeLock dlnaWake;
    private final Runnable dlnaPoll = this::pollDlna;
    private VolumeProvider castVolume;
    private boolean airPlayConnected, airPlayPin;
    private int airPlayVolume = 60;
    private String airPlayMessage = "";
    private EffectSettings effectSettings;
    private MusicEffects effects;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean ready;
    private boolean playWhenReady, ended, buffering;
    private int retryPosition;
    private int buffered;
    private boolean pausedForFocus;
    private int selection;
    private Bitmap artwork;
    private String artworkKey = "";
    private java.util.concurrent.Future<?> artworkTask;
    private final java.util.concurrent.ExecutorService artworkIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean cachedPlayback, plainRetried;
    private boolean onlineAttempted;
    private String cachedQuality = "";
    private String requestedQuality = "";
    private MediaSources.Resolved currentMedia;
    private AudioDetails audioDetails = AudioDetails.EMPTY;
    private java.util.concurrent.Future<?> audioDetailsTask;
    private final java.util.concurrent.ExecutorService audioDetailsIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private MediaSources.OnlineResolution onlineResolution;
    private CachedAudio cachedSource;
    private java.util.concurrent.Future<?> probe;
    private final java.util.concurrent.ExecutorService cacheIo = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.concurrent.ExecutorService cacheWriter = java.util.concurrent.Executors.newSingleThreadExecutor();
    private CachedAudio.Capture capture;
    private java.util.concurrent.Future<?> captureTask;
    private CachedAudio.Outcome lastCacheOutcome;
    private boolean completedOnlineTrack;
    private boolean captureReady;
    private MediaSources sources;
    private List<Lyrics.Line> miniLyricLines = List.of();
    private String miniLyricKey = "";
    private int miniLyricRequest;
    private java.util.concurrent.Future<?> miniLyricTask;
    private final java.util.concurrent.ExecutorService miniLyricIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Queue queue;
    private MusicResume musicContinuation;
    private String musicContinuationMetadata = "", savedMusicMetadata = "";
    private final PlayOrder order = new PlayOrder();
    private PlayOrder.Mode musicMode;
    private int queueVersion;
    private final java.util.ArrayDeque<Integer> playNext = new java.util.ArrayDeque<>();
    private long sleepAt;
    private boolean sleepAfterItem;
    private final Runnable sleepTask = this::checkSleep;
    private final android.content.BroadcastReceiver headphones = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (sources.settings.getBoolean("player-pause-on-unplug", true)) pause();
        }
    };
    private String key = "";
    String title = "";
    String subtitle = "";
    String cover = "";
    String error = "";
    String sourceStatus = "";
    String sourceFallback = "";
    int sourceStatusVersion;
    boolean resolving;
    boolean book;
    float speed = 1;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            checkSleep();
            save();
            if (captureTask == null && capture != null && playing()
                && position() >= Math.max(duration() / 2, duration() - 60_000)) finishOnlineCache();
            handler.postDelayed(this, 5000);
        }
    };

    static void queue(Context context, List<MediaEntry> entries, int index, boolean book) {
        queue(context, entries, index, book, null);
    }

    static void queue(Context context, List<MediaEntry> entries, int index, boolean book, PlayOrder.Mode mode) {
        if (index < 0 || index >= entries.size()) return;
        requested = new QueueRequest(new Queue(List.copyOf(entries), index, book), mode, 0);
        context.startForegroundService(new Intent(context, PlaybackService.class).setAction("queue"));
    }

    static void continueMusic(Context context, MusicResume recent) {
        requested = new QueueRequest(new Queue(List.of(recent.entry()), 0, false), null, recent.resumePosition());
        context.startForegroundService(new Intent(context, PlaybackService.class).setAction("queue"));
    }

    MusicResume musicContinuation() {
        if (book || musicContinuation == null) return null;
        return musicContinuation.progress(ready ? position() : retryPosition, ready ? duration() : 0);
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        sources = new MediaSources(this);
        effectSettings = EffectSettings.load(sources.settings);
        effects = new MusicEffects(handler, effectSettings);
        musicMode = PlayOrder.Mode.read(sources.settings.getString("player-order", ""));
        var filter = new android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(headphones, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(headphones, filter);
        audio = getSystemService(AudioManager.class);
        var attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> {
                if (change == AudioManager.AUDIOFOCUS_GAIN && pausedForFocus) {
                    pausedForFocus = false;
                    resume();
                } else if (change < 0 && playWhenReady) {
                    pause();
                    pausedForFocus = change != AudioManager.AUDIOFOCUS_LOSS;
                }
            }, handler).build();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("playback", "音频播放", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "音浪");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pausedForFocus = false; pause(); }
            @Override public void onSeekTo(long position) { seek((int) position); }
            @Override public void onFastForward() { seek(position() + 30_000); }
            @Override public void onRewind() { seek(position() - 15_000); }
            @Override public void onSkipToNext() { skip(1); }
            @Override public void onSkipToPrevious() { skip(-1); }
            @Override public void onStop() { stopSelf(); }
        }, handler);
        session.setActive(true);
        handler.postDelayed(ticker, 5000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if ("toggle".equals(action)) toggle();
        if ("previous".equals(action)) skip(-1);
        if ("next".equals(action)) skip(1);
        if ("stop".equals(action)) stopSelf();
        if ("queue".equals(action) && requested != null) {
            QueueRequest selected = requested;
            requested = null;
            queue = selected.queue();
            if (selected.mode() != null) musicMode = selected.mode();
            playNext.clear();
            queueVersion++;
            select(queue.index(), true, true, selected.position(), 1f);
        }
        return START_NOT_STICKY;
    }

    List<MediaEntry> entries() { return queue == null ? List.of() : queue.entries(); }
    int index() { return queue == null ? -1 : queue.index(); }
    int queueVersion() { return queueVersion; }

    String airPlayName() { return dlna != null ? dlna.device().name() : airPlay == null ? "" : airPlay.device.name(); }
    boolean airPlayConnected() { return dlna != null || airPlayConnected; }
    boolean airPlayPinRequired() { return airPlayPin; }
    String airPlayMessage() { return airPlayMessage; }
    int airPlayVolume() { return airPlayVolume; }

    void connectAirPlay(AirPlayDiscovery.Device device, String password) {
        if (queue == null || !airPlayName().isEmpty()) return;
        airPlayMessage = "正在连接 " + device.name() + "…";
        try {
            airPlay = new AirPlaySession(device, getSharedPreferences("airplay", MODE_PRIVATE), this::airPlayEvent);
            airPlay.start(password);
        } catch (LinkageError failure) {
            airPlay = null;
            airPlayMessage = "此设备无法启动投送";
        }
    }

    void submitAirPlayPin(String pin) {
        if (airPlay == null || !airPlayPin || !pin.matches("[0-9]{4}")) return;
        airPlayPin = false;
        airPlayMessage = "正在配对 " + airPlayName() + "…";
        airPlay.submitPin(pin);
    }

    boolean canPairAgain() { return airPlay != null && airPlay.device.mode() >= AirPlayDiscovery.TRANSIENT; }

    void pairAirPlayAgain() {
        if (!canPairAgain()) return;
        var device = airPlay.device;
        airPlay.forgetPairing();
        disconnectAirPlay("");
        connectAirPlay(new AirPlayDiscovery.Device(device.id(), device.name(), device.host(), device.port(), AirPlayDiscovery.PIN), "");
    }

    void adjustAirPlayVolume(int percent) {
        if (dlna != null) {
            airPlayVolume = Math.max(0, Math.min(100, percent));
            int value = airPlayVolume;
            dlnaCommand(target -> target.setVolume(value));
            if (castVolume != null) castVolume.setCurrentVolume(value);
            return;
        }
        if (!airPlayConnected || airPlay == null) return;
        airPlayVolume = Math.max(0, Math.min(100, percent));
        airPlay.setVolume(airPlayVolume);
        if (castVolume != null) castVolume.setCurrentVolume(airPlayVolume);
    }

    void disconnectAirPlay() {
        if (dlna != null) disconnectDlna("");
        else disconnectAirPlay("");
    }

    void connectDlna(DlnaController.Device device) {
        if (queue == null || !airPlayName().isEmpty()) return;
        dlna = new DlnaController.Session(device);
        dlnaWake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yinlang:dlna");
        dlnaWake.acquire();
        airPlayMessage = "正在连接 " + device.name() + "…";
        castVolume = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, airPlayVolume) {
            @Override public void onAdjustVolume(int direction) { handler.post(() -> adjustAirPlayVolume(airPlayVolume + direction * 5)); }
            @Override public void onSetVolumeTo(int value) { handler.post(() -> adjustAirPlayVolume(value)); }
        };
        session.setPlaybackToRemote(castVolume);
        switchAudioOutput();
    }

    private interface DlnaAction { void run(DlnaController.Session target) throws Exception; }
    private void dlnaCommand(DlnaAction action) {
        var target = dlna;
        int revision = dlnaRevision;
        if (target == null) return;
        dlnaIo.execute(() -> {
            if (revision != dlnaRevision) return;
            try { action.run(target); }
            catch (Exception failure) { handler.post(() -> {
                if (target == dlna && revision == dlnaRevision) {
                    airPlayMessage = "设备未接受操作：" + failure.getMessage();
                    update();
                }
            }); }
        });
    }

    private void disconnectDlna(String message) {
        int offset = ready ? position() : retryPosition;
        release();
        dlna = null;
        if (dlnaWake != null) { if (dlnaWake.isHeld()) dlnaWake.release(); dlnaWake = null; }
        castVolume = null;
        airPlayMessage = message;
        session.setPlaybackToLocal(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
        if (queue != null) select(index(), false, playWhenReady, offset, speed);
    }

    private void openDlna(String address, Bundle headers, CachedAudio input) {
        var target = dlna;
        var request = currentMedia;
        var entry = entries().get(index());
        int revision = dlnaRevision;
        int offset = retryPosition;
        dlnaPosition = offset;
        dlnaDuration = 0;
        dlnaFailures = 0;
        dlnaIo.execute(() -> {
            DlnaStreamServer stream = null;
            try {
                if (revision != dlnaRevision) return;
                stream = DlnaStreamServer.start(this, target.device().host(), address, headers, input,
                    request.hls(), request.extension(), request.details());
                String artwork = entry.cover();
                if (entry.source().equals("library") && !artwork.isBlank()) artwork = sources.navidrome().endpoint("getCoverArt", "&id=" + Http.enc(artwork) + "&size=512");
                artwork = stream.artworkUrl(artwork, new Bundle());
                target.setMedia(new DlnaController.Media(stream.url(), entry.title(), entry.subtitle(),
                    MediaEntry.first(entry.raw(), "albumName", "album"), artwork, stream.mimeType(), 0));
                if (revision != dlnaRevision) { stream.close(); return; }
                if (offset > 0) try { target.seek(offset); } catch (Exception ignored) {}
                var output = stream;
                handler.post(() -> {
                    if (target != dlna || revision != dlnaRevision) { output.close(); return; }
                    dlnaStream = output;
                    ready = true;
                    retryPosition = 0;
                    airPlayMessage = "";
                    speed = 1;
                    if (playWhenReady) resume(); else update();
                    handler.removeCallbacks(dlnaPoll);
                    handler.postDelayed(dlnaPoll, 1200);
                });
            } catch (Exception failure) {
                if (stream != null) stream.close();
                handler.post(() -> {
                    if (target == dlna && revision == dlnaRevision)
                        disconnectDlna("投送失败：" + failure.getMessage());
                });
            }
        });
    }

    private void pollDlna() {
        var target = dlna;
        int revision = dlnaRevision;
        if (target == null || !ready || dlnaPolling) return;
        dlnaPolling = true;
        dlnaIo.execute(() -> {
            DlnaController.Position value = null;
            Exception problem = null;
            try { if (revision == dlnaRevision) value = target.position(); }
            catch (Exception failure) { problem = failure; }
            var result = value;
            var failure = problem;
            handler.post(() -> {
                dlnaPolling = false;
                if (target != dlna || revision != dlnaRevision || !ready) return;
                if (failure != null) {
                    if (++dlnaFailures >= 4) { disconnectDlna("投送设备连接中断"); return; }
                } else if (result != null) {
                    dlnaFailures = 0;
                    if (result.durationMs() > 0) dlnaDuration = (int)Math.min(Integer.MAX_VALUE, result.durationMs());
                    boolean finished = dlnaPlaying && playWhenReady && "STOPPED".equals(result.state())
                        && dlnaDuration > 0 && position() >= dlnaDuration - 3000;
                    dlnaPosition = (int)Math.min(Integer.MAX_VALUE, result.positionMs());
                    dlnaPositionAt = android.os.SystemClock.elapsedRealtime();
                    if (!result.state().isEmpty()) dlnaPlaying = "PLAYING".equals(result.state());
                    if ("PAUSED_PLAYBACK".equals(result.state())) playWhenReady = false;
                    buffering = "TRANSITIONING".equals(result.state());
                    if (finished) { completed(); return; }
                    update();
                }
                handler.postDelayed(dlnaPoll, 1500);
            });
        });
    }

    private void airPlayEvent(AirPlaySession sender, int event, String detail) {
        if (sender != airPlay) return;
        switch (event) {
            case AirPlaySession.PIN_REQUIRED -> {
                airPlayPin = true;
                airPlayMessage = "输入接收设备上显示的 4 位配对码";
            }
            case AirPlaySession.CONNECTED -> {
                airPlayPin = false;
                airPlayConnected = true;
                airPlayMessage = "";
                sender.setVolume(airPlayVolume);
                castVolume = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, airPlayVolume) {
                    @Override public void onAdjustVolume(int direction) {
                        handler.post(() -> adjustAirPlayVolume(airPlayVolume + direction * 5));
                    }
                    @Override public void onSetVolumeTo(int value) { handler.post(() -> adjustAirPlayVolume(value)); }
                };
                session.setPlaybackToRemote(castVolume);
                switchAudioOutput();
            }
            case AirPlaySession.FAILED -> {
                String reason = detail.toLowerCase(java.util.Locale.ROOT);
                if (reason.contains("verify") || reason.contains("credentials")) sender.forgetPairing();
                String description = detail.replaceAll("(?i)\\bAirPlay(?:\\s+2)?\\b", "投送");
                disconnectAirPlay("无法连接 " + sender.device.name() + (description.isBlank() ? "" : " · " + description));
            }
            case AirPlaySession.DISCONNECTED -> disconnectAirPlay(sender.device.name() + " 已断开");
        }
    }

    private void disconnectAirPlay(String message) {
        boolean wasConnected = airPlayConnected;
        airPlayConnected = false;
        airPlayPin = false;
        airPlayMessage = message;
        if (airPlay != null) { airPlay.close(); airPlay = null; }
        castVolume = null;
        if (wasConnected) {
            session.setPlaybackToLocal(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
            switchAudioOutput();
        }
    }

    private void switchAudioOutput() {
        if (queue == null) return;
        select(index(), false, playWhenReady, ready ? position() : retryPosition, speed);
    }
    PlayOrder.Mode mode() { return book ? PlayOrder.Mode.SEQUENTIAL : musicMode; }
    void setMode(PlayOrder.Mode mode) {
        if (book || musicMode == mode) return;
        musicMode = mode;
        sources.settings.edit().putString("player-order", mode.name()).apply();
        order.reset(index(), entries().size());
        update();
    }
    boolean playNext(MediaEntry entry, boolean audiobook) {
        if (queue == null || book != audiobook) return false;
        var entries = new java.util.ArrayList<>(entries());
        int next = index() + 1;
        entries.add(next, entry);
        queue = new Queue(List.copyOf(entries), index(), book);
        shiftPriority(next, 1);
        playNext.addFirst(next);
        order.reset(index(), entries.size());
        queueVersion++;
        return true;
    }
    boolean remove(int index) {
        if (queue == null || index < 0 || index >= entries().size() || index == index()) return false;
        var remaining = new java.util.ArrayList<>(entries());
        remaining.remove(index);
        queue = new Queue(List.copyOf(remaining), index < index() ? index() - 1 : index(), book);
        playNext.remove(index);
        shiftPriority(index, -1);
        order.reset(index(), remaining.size());
        queueVersion++;
        return true;
    }
    private void shiftPriority(int index, int direction) {
        int count = playNext.size();
        for (int i = 0; i < count; i++) {
            int pending = playNext.removeFirst();
            playNext.addLast(pending >= index ? pending + direction : pending);
        }
    }
    void skip(int direction) { advance(direction, false); }
    private void advance(int direction, boolean completed) {
        if (queue == null) return;
        if (direction > 0 && !playNext.isEmpty() && (!completed || mode() != PlayOrder.Mode.REPEAT_ONE)) {
            int next = playNext.removeFirst();
            order.reset(next, entries().size());
            select(next, false);
            return;
        }
        int next = order.next(mode(), index(), entries().size(), direction, completed);
        if (next == index() && ready) {
            ended = false;
            seek(0);
            resume();
        } else if (next >= 0) select(next, false);
        else if (direction < 0 && ready) seek(0);
        else update();
    }
    void select(int index) {
        select(index, true);
    }
    private void select(int index, boolean resetOrder) {
        select(index, resetOrder, true, 0, 1f);
    }

    boolean onlineMusic() {
        return queue != null && !book && !List.of("offline", "library").contains(entries().get(index()).source());
    }

    void reloadQuality() {
        if (onlineMusic()) select(index(), false, playWhenReady, ready ? position() : retryPosition, speed);
    }

    String resolvedQuality() { return currentMedia == null ? "" : currentMedia.quality(); }
    String audioDetailsLabel() { return audioDetails.label(duration()); }

    private void select(int index, boolean resetOrder, boolean autoPlay, int offset, float musicSpeed) {
        if (queue == null || index < 0 || index >= queue.entries().size()) return;
        save();
        cancelResolution();
        clearCapture(completedOnlineTrack);
        release();
        playWhenReady = autoPlay;
        ended = false;
        buffering = false;
        buffered = 0;
        retryPosition = offset;
        if (resetOrder) {
            order.reset(index, entries().size());
            playNext.clear();
        }
        Queue selected = new Queue(queue.entries(), index, queue.book());
        queue = selected;
        MediaEntry entry = selected.entries().get(index);
        key = entry.source() + ":" + entry.id();
        title = entry.title();
        subtitle = entry.subtitle();
        if (airPlayConnected && airPlay != null) {
            airPlay.nowPlaying(title, subtitle, MediaEntry.first(entry.raw(), "albumName", "album"));
        }
        cover = entry.cover();
        book = selected.book();
        musicContinuation = book ? null : new MusicResume(entry, sources.navCacheKey(), offset, 0);
        musicContinuationMetadata = musicContinuation == null ? "" : musicContinuation.metadata();
        currentMedia = null;
        audioDetails = AudioDetails.EMPTY;
        plainRetried = false;
        onlineAttempted = false;
        cachedQuality = "";
        lastCacheOutcome = null;
        requestedQuality = sources.settings.getString("music-quality", "320k");
        resolving = !List.of("offline", "library").contains(entry.source()) && !book;
        sourceStatus = "";
        sourceFallback = "";
        sourceStatusVersion++;
        if (book) {
            speed = sources.settings.getFloat(bookSpeedKey(), 1f);
            String bookKey = entry.source() + "-" + entry.raw().optString("_book", subtitle);
            sources.settings.edit().putString("last-book-" + bookKey, entry.id())
                .putString("last-book-chapter-" + bookKey, entry.title()).apply();
        } else speed = musicSpeed;
        error = "";
        int revision = ++selection;
        loadArtwork(entry, revision);
        startForeground(24, notification());
        update();
        ensureMiniLyrics();
        if (resolving) {
            boolean connected = hasNetwork();
            tryCached(entry, revision, !connected, () -> {
                if (hasNetwork()) startOnline(entry, revision);
                else if (connected) tryCached(entry, revision, true, this::offlineMiss);
                else offlineMiss();
            });
        } else sources.resolve(entry, book, false, resolved(entry, revision));
    }

    private boolean hasNetwork() {
        return getSystemService(ConnectivityManager.class).getActiveNetwork() != null;
    }

    Bitmap artwork() { return artwork; }

    private void loadArtwork(MediaEntry entry, int revision) {
        if (artworkTask != null) { artworkTask.cancel(true); artworkTask = null; }
        var request = PlaybackArtwork.request(sources, entry);
        String nextKey = request == null ? "" : request.cacheKey();
        if (!nextKey.equals(artworkKey)) artwork = null;
        artworkKey = nextKey;
        if (request == null || artwork != null) return;
        var cache = MediaCache.get(this).bucket(book);
        long cacheRevision = cache.revision();
        boolean network = hasNetwork();
        artworkTask = artworkIo.submit(() -> {
            Bitmap result = PlaybackArtwork.load(request, cache, cacheRevision, network);
            if (Thread.currentThread().isInterrupted()) return;
            handler.post(() -> {
                // A late download must never replace the next song's artwork or revive a stopped service.
                if (active != this || selection != revision) return;
                artworkTask = null;
                if (result == null) return;
                artwork = result;
                update();
            });
        });
    }

    /** Current and following synchronized lines for the compact player; never performs UI-thread I/O. */
    List<String> miniLyrics(int lines) {
        if (lines <= 0 || book || sources == null || "off".equals(sources.settings.getString("mini-lyrics", "off"))) return List.of();
        ensureMiniLyrics();
        int current = Lyrics.current(miniLyricLines, position());
        if (current < 0) return List.of();
        var result = new java.util.ArrayList<String>(2);
        String text = miniLyricLines.get(current).text();
        // A blank LRC line deliberately clears the compact lyric during an instrumental break.
        if (text.isBlank()) return List.of();
        result.add(text);
        if (lines > 1) {
            for (int index = current + 1; index < miniLyricLines.size(); index++) {
                String next = miniLyricLines.get(index).text();
                if (!next.isBlank()) { result.add(next); break; }
            }
        }
        return List.copyOf(result);
    }

    private void ensureMiniLyrics() {
        if (book || queue == null || index() < 0 || sources == null || active != this
            || "off".equals(sources.settings.getString("mini-lyrics", "off"))) return;
        MediaEntry entry = entries().get(index());
        String cacheKey = "lyrics:" + entry.source() + ":" + entry.id()
            + (entry.source().equals("library") ? ":" + sources.navCacheKey() : "");
        if (cacheKey.equals(miniLyricKey)) return;
        miniLyricKey = cacheKey;
        miniLyricLines = List.of();
        int request = ++miniLyricRequest;
        if (miniLyricTask != null) miniLyricTask.cancel(true);
        var cache = MediaCache.get(this).bucket(false);
        long cacheRevision = cache.revision();
        Navidrome library = entry.source().equals("library") ? sources.navidrome() : null;
        // Cache and network work run in the executor; plugin WebViews are created on the main thread.
        miniLyricTask = miniLyricIo.submit(() -> {
            org.json.JSONObject stored = null;
            try {
                byte[] bytes = cache.read(cacheKey);
                if (bytes != null) stored = new org.json.JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                if (stored == null && library != null)
                    stored = MediaSources.args("lyric", library.lyrics(entry));
            } catch (Exception ignored) {}
            org.json.JSONObject result = stored;
            handler.post(() -> {
                if (request != miniLyricRequest || active != this) return;
                if (result != null) {
                    receiveMiniLyrics(request, result, cache, cacheKey, cacheRevision);
                } else if (!entry.source().equals("library")) {
                    sources.lyrics(entry, (value, error) -> {
                        if (request == miniLyricRequest && active == this && error == null
                            && value instanceof org.json.JSONObject json)
                            receiveMiniLyrics(request, json, cache, cacheKey, cacheRevision);
                    });
                }
            });
        });
    }

    private void receiveMiniLyrics(int request, org.json.JSONObject json, MediaCache.Bucket cache,
            String cacheKey, long cacheRevision) {
        if (request != miniLyricRequest || active != this) return;
        miniLyricLines = Lyrics.parse(json.optString("lyric"), json.optString("tlyric"));
        byte[] data = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (data.length <= 512 * 1024 && !miniLyricIo.isShutdown())
            miniLyricIo.execute(() -> cache.write(cacheKey, data, cacheRevision));
    }

    private static String audioIndex(MediaEntry entry, String quality) {
        if (entry.id().isEmpty() || quality.isEmpty()) return "";
        return "online-audio:" + entry.source() + "\n" + entry.id() + "\n" + quality;
    }

    private void clearCapture(boolean keepWriting) {
        if (!keepWriting && captureTask != null) captureTask.cancel(true);
        captureTask = null;
        capture = null;
        captureReady = false;
        completedOnlineTrack = false;
    }

    private void finishOnlineCache() {
        if (capture == null || captureReady || captureTask != null || !hasNetwork()) return;
        var request = capture;
        var reader = cachedSource;
        var cache = MediaCache.get(this);
        String statusKey = audioIndex(entries().get(index()), "state");
        int revision = selection;
        captureTask = cacheWriter.submit(() -> {
            CachedAudio.Outcome outcome = CachedAudio.finish(cache, request, reader);
            if (outcome == CachedAudio.Outcome.STOPPED) return;
            var bucket = cache.bucket(false);
            synchronized (bucket) {
                if (bucket.revision() != request.revision()) return;
                if (outcome == CachedAudio.Outcome.READY) bucket.remove(statusKey);
                else bucket.write(statusKey, outcome.name().getBytes(java.nio.charset.StandardCharsets.US_ASCII), request.revision());
            }
            handler.post(() -> {
                if (revision != selection || active != this || capture != request || captureReady
                    || cache.bucket(false).revision() != request.revision()) return;
                sourceStatus = "离线缓存 · " + outcome.label;
                if (outcome == CachedAudio.Outcome.READY) sourceStatus += " · " + MusicQuality.label(resolvedQuality());
                sourceFallback = "";
                sourceStatusVersion++;
            });
        });
    }

    private static CachedAudio.Outcome cacheOutcome(MediaCache cache, MediaEntry entry) {
        byte[] record = cache.bucket(false).read(audioIndex(entry, "state"));
        if (record == null) return null;
        try { return CachedAudio.Outcome.valueOf(new String(record, java.nio.charset.StandardCharsets.US_ASCII)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private void tryCached(MediaEntry entry, int revision, boolean allowFallback, Runnable miss) {
        var cache = MediaCache.get(this);
        if (entry.id().isEmpty() || cache.limitMb(false) == 0) { miss.run(); return; }
        String preferred = requestedQuality;
        String alreadyTried = cachedQuality;
        var tiers = allowFallback ? MusicQuality.attempts(preferred, false,
            Set.of("128k", "192k", "320k", "flac", "flac24bit", "hires", "atmos", "master")) : List.of(preferred);
        probe = cacheIo.submit(() -> {
            CachedAudio input = null;
            String found = "";
            for (String tier : tiers) {
                var keys = tier.equals("atmos_plus") ? List.of("atmos_plus", "atmos")
                    : tier.equals("atmos") ? MusicQuality.SPATIAL.advertisedKeys() : List.of(tier);
                for (String quality : keys) {
                    if (quality.equals(alreadyTried)) continue;
                    input = CachedAudio.openOffline(cache, audioIndex(entry, quality));
                    if (input != null) { found = quality; break; }
                }
                if (input != null) break;
            }
            CachedAudio result = input;
            String quality = found;
            CachedAudio.Outcome outcome = result == null && allowFallback ? cacheOutcome(cache, entry) : null;
            handler.post(() -> {
                if (revision != selection || active != this) {
                    if (result != null) result.close();
                    return;
                }
                probe = null;
                if (result == null) { lastCacheOutcome = outcome; miss.run(); return; }
                resolving = false;
                cachedQuality = quality;
                currentMedia = new MediaSources.Resolved("", new Bundle(), false, quality);
                sourceStatus = "离线缓存 · " + MusicQuality.label(quality);
                sourceFallback = "";
                sourceStatusVersion++;
                open("", currentMedia.headers(), result);
                update();
            });
        });
    }

    private void offlineMiss() {
        resolving = false;
        String reason = MediaCache.get(this).limitMb(false) == 0 ? "音乐缓存已关闭" :
            lastCacheOutcome == CachedAudio.Outcome.STREAM_ONLY || lastCacheOutcome == CachedAudio.Outcome.TOO_LARGE
                ? lastCacheOutcome.label : "这首歌没有完整的离线缓存";
        error = "当前未连接网络 · " + reason;
        sourceStatus = "离线缓存 · " + reason;
        sourceStatusVersion++;
        update();
    }

    private void startOnline(MediaEntry entry, int revision) {
        onlineAttempted = true;
        onlineResolution = sources.playbackResolution(entry, requestedQuality, (name, status) -> {
            if (revision != selection || active != this) return;
            if (sourceStatus.endsWith("解析失败") && !status.equals("解析失败")) sourceFallback = sourceStatus;
            sourceStatus = name + " · " + status;
            sourceStatusVersion++;
        });
        nextOnline(entry, revision);
    }

    private void nextOnline(MediaEntry entry, int revision) {
        resolving = true;
        onlineResolution.next(resolved(entry, revision));
    }

    private MediaSources.ResolveCallback resolved(MediaEntry entry, int revision) {
        return (value, failure) -> {
            if (revision != selection || active != this) return;
            boolean online = resolving;
            resolving = false;
            if (failure != null) {
                cancelResolution();
                if (online && sourceStatus.isEmpty()) {
                    sourceStatus = MediaSources.label(entry.source()) + " · 无可用音源";
                    sourceStatusVersion++;
                }
                Runnable report = () -> {
                    error = online && sourceStatus.endsWith("解析失败") ? sourceStatus + "\n" + failure : failure;
                    update();
                };
                if (online) tryCached(entry, revision, true, hasNetwork() ? report : this::offlineMiss);
                else report.run();
                return;
            }
            currentMedia = value;
            audioDetails = value.details();
            plainRetried = false;
            var cache = MediaCache.get(this);
            String indexKey = online ? audioIndex(entry, value.quality()) : "";
            if (!indexKey.isEmpty() && cache.limitMb(false) > 0) {
                capture = new CachedAudio.Capture(indexKey, value.url(), value.headers(), value.hls(), cache.bucket(false).revision());
            }
            if (value.hls() || !value.url().startsWith("http") || cache.limitMb(book) == 0) {
                open(value.url(), value.headers(), null);
                return;
            }
            boolean audiobook = book;
            String stableId = entry.source().equals("library")
                ? sources.navCacheKey() + "\n" + entry.id()
                    + "\n" + entry.raw().optString("changed") + "\n" + entry.raw().optString("size") + "\n" + value.quality()
                : "";
            probe = cacheIo.submit(() -> {
                CachedAudio input = null;
                try { input = CachedAudio.open(cache, audiobook, value.url(), value.headers(), stableId, indexKey); }
                catch (Exception ignored) {}
                CachedAudio result = input;
                handler.post(() -> {
                    if (revision != selection || active != this) {
                        if (result != null) result.close();
                        return;
                    }
                    probe = null;
                    open(value.url(), value.headers(), result);
                });
            });
        };
    }

    private void open(String address, Bundle values, CachedAudio input) {
        cachedPlayback = input != null;
        cachedSource = input;
        if (input != null && capture != null) {
            int selected = selection;
            var request = capture;
            input.onOfflineReady(() -> handler.post(() -> {
                if (active != this || selection != selected || cachedSource != input || capture != request || captureReady) return;
                captureReady = true;
                sourceStatus = "离线缓存 · 已可离线播放 · " + MusicQuality.label(resolvedQuality());
                sourceFallback = "";
                sourceStatusVersion++;
                String statusKey = audioIndex(entries().get(index()), "state");
                cacheWriter.execute(() -> MediaCache.get(this).bucket(false).remove(statusKey));
            }));
        }
        if (input != null) input.startPrefetch();
        if (input != null && audioDetails.bytes() == 0) {
            audioDetails = audioDetails.with(new AudioDetails("", 0, 0, input.getSize()));
        }
        if (airPlayConnected && airPlay != null) {
            openAirPlay(address, values, input);
            return;
        }
        if (dlna != null) { openDlna(address, values, input); return; }
        try {
            media = new MediaPlayer();
            media.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(book ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_MUSIC).build());
            media.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            var headers = new HashMap<String, String>();
            if (values != null) for (String name : values.keySet()) headers.put(name, values.getString(name, ""));
            if (cachedPlayback) media.setDataSource(input);
            else media.setDataSource(this, Uri.parse(address), headers);
            media.setOnPreparedListener(player -> {
                if (player != media) return;
                ready = true;
                var metrics = player.getMetrics();
                if (audioDetails.format().isEmpty() && metrics != null) {
                    String mime = metrics.getString(MediaPlayer.MetricsConstants.MIME_TYPE_AUDIO);
                    audioDetails = audioDetails.with(new AudioDetails(AudioDetails.format(mime), 0, 0, 0));
                }
                loadAudioDetails(address, values, input);
                if (book && duration() > 0) getSharedPreferences("progress", MODE_PRIVATE).edit().putInt(key + ":duration", duration()).apply();
                if (!book) effects.attach(player);
                int offset = retryPosition > 0 ? retryPosition : book ? getSharedPreferences("progress", MODE_PRIVATE).getInt(key, 0) : 0;
                retryPosition = 0;
                if (offset > 0 && offset < duration() - (book ? 2000 : 0)) seek(offset);
                if (playWhenReady) resume(); else update();
            });
            media.setOnBufferingUpdateListener((player, percent) -> { if (player == media) buffered = percent; });
            media.setOnInfoListener((player, what, extra) -> {
                if (player != media) return false;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true;
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) buffering = false;
                else return false;
                update();
                return true;
            });
            media.setOnCompletionListener(player -> {
                if (player == media) completed();
            });
            media.setOnErrorListener((player, what, extra) -> {
                if (player != media || retryPlain()) return true;
                playbackFailed("无法播放这段音频（" + what + " / " + extra + "）");
                return true;
            });
            media.prepareAsync();
        } catch (Exception ignored) {
            if (retryPlain()) return;
            playbackFailed("无法打开音频地址");
        }
    }

    @androidx.media3.common.util.UnstableApi
    private void openAirPlay(String address, Bundle headers, CachedAudio input) {
        try {
            AirPlaySession target = airPlay;
            var route = new AirPlayPlayback(this, target, address, headers, input, currentMedia.hls());
            castPlayback = route;
            var player = route.player;
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (route != castPlayback) return;
                    buffering = state == Player.STATE_BUFFERING;
                    if (state == Player.STATE_READY && !ready) {
                        ready = true;
                        updateCastFormat(player.getAudioFormat());
                        if (book && duration() > 0) getSharedPreferences("progress", MODE_PRIVATE).edit()
                            .putInt(key + ":duration", duration()).apply();
                        if (playWhenReady) resume();
                    }
                    if (state == Player.STATE_ENDED && !ended) completed();
                    else update();
                }
                @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                    if (route != castPlayback) return;
                    for (var group : tracks.getGroups()) {
                        if (group.getType() != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue;
                        for (int index = 0; index < group.length; index++) {
                            if (group.isTrackSelected(index)) {
                                updateCastFormat(group.getTrackFormat(index));
                                return;
                            }
                        }
                    }
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    if (route != castPlayback) return;
                    if (player.getPlaybackState() != Player.STATE_ENDED) target.playback(playing);
                    update();
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException failure) {
                    if (route == castPlayback && !retryPlain()) disconnectAirPlay("投送无法播放这段音频，已切回手机");
                }
            });
            int offset = retryPosition > 0 ? retryPosition : book
                ? getSharedPreferences("progress", MODE_PRIVATE).getInt(key, 0) : 0;
            if (offset > 0) player.seekTo(offset);
            retryPosition = offset;
            player.prepare();
        } catch (RuntimeException error) {
            disconnectAirPlay("投送无法打开音频，已切回手机");
        }
    }

    private void updateCastFormat(androidx.media3.common.Format format) {
        if (format != null) {
            audioDetails = audioDetails.with(new AudioDetails(AudioDetails.format(format.sampleMimeType),
                format.averageBitrate, format.sampleRate, 0));
        }
    }

    private void loadAudioDetails(String address, Bundle headers, CachedAudio input) {
        var request = currentMedia;
        if (request == null || request.hls() || audioDetails.sampleRate() > 0
            && (audioDetails.bitrate() > 0 || audioDetails.bytes() > 0)) return;
        int revision = selection;
        var selectedPlayer = media;
        audioDetailsTask = audioDetailsIo.submit(() -> {
            AudioDetails result = AudioDetails.extract(this, address, headers, input, request.hls());
            handler.post(() -> {
                if (revision == selection && currentMedia == request && media == selectedPlayer) {
                    audioDetails = audioDetails.with(result);
                    audioDetailsTask = null;
                }
            });
        });
    }

    private void completed() {
        ended = true;
        playWhenReady = false;
        completedOnlineTrack = capture != null;
        finishOnlineCache();
        effects.refreshPan();
        if (book) getSharedPreferences("progress", MODE_PRIVATE).edit().remove(key).apply();
        if (sleepAfterItem || checkSleep()) {
            setSleep(0);
            update();
        } else advance(1, true);
    }
    private void playbackFailed(String reason) {
        clearCapture(false);
        if (currentMedia != null && currentMedia.url().isEmpty()) {
            try { if (ready) retryPosition = position(); } catch (IllegalStateException ignored) {}
            release();
            currentMedia = null;
            if (hasNetwork() && !onlineAttempted) startOnline(entries().get(index()), selection);
            else {
                error = "离线缓存无法播放，联网后可重试";
                sourceStatus = "离线缓存 · 读取失败";
                sourceStatusVersion++;
            }
            update();
            return;
        }
        if (onlineResolution != null && !ready) {
            release();
            currentMedia = null;
            onlineResolution.failed(reason);
            nextOnline(entries().get(index()), selection);
            update();
            return;
        }
        error = reason;
        release();
        update();
    }

    private void cancelResolution() {
        if (onlineResolution != null) { onlineResolution.cancel(); onlineResolution = null; }
    }
    private boolean retryPlain() {
        if (!cachedPlayback || plainRetried || currentMedia == null || currentMedia.url().isEmpty()) return false;
        plainRetried = true;
        try { if (ready) retryPosition = position(); } catch (IllegalStateException ignored) {}
        release();
        open(currentMedia.url(), currentMedia.headers(), null);
        return true;
    }
    int revision() { return selection; }

    private PendingIntent command(String action, int request) {
        return PendingIntent.getService(this, request, new Intent(this, PlaybackService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        var intent = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("show-player", true);
        return new Notification.Builder(this, "playback")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(error.isEmpty() ? subtitle : error)
            .setLargeIcon(artwork).setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .setOnlyAlertOnce(true).setOngoing(playing())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, book ? "上一集" : "上一首", command("previous", 3)).build())
            .addAction(new Notification.Action.Builder(wantsPause() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                wantsPause() ? "暂停" : "播放", command("toggle", 1)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, book ? "下一集" : "下一首", command("next", 4)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", command("stop", 2)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
            .build();
    }

    private void update() {
        var metadata = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, key)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
        if (queue != null && index() >= 0 && index() < entries().size()) {
            metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, MediaEntry.first(entries().get(index()).raw(), "albumName", "album"));
        }
        if (artwork != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        session.setMetadata(metadata.build());
        int state = !error.isEmpty() ? PlaybackState.STATE_ERROR : preparing() ? PlaybackState.STATE_BUFFERING : playing() ? PlaybackState.STATE_PLAYING
            : ready || !playWhenReady ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_BUFFERING;
        session.setPlaybackState(new PlaybackState.Builder().setBufferedPosition(bufferedPosition()).setActions(PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(state, position(), playing() ? speed : 0).setErrorMessage(error).build());
        getSystemService(NotificationManager.class).notify(24, notification());
    }

    boolean playing() { return ready && (dlna != null ? dlnaPlaying : castPlayback != null ? castPlayback.player.isPlaying() : media != null && media.isPlaying()); }
    boolean wantsPause() { return error.isEmpty() && playWhenReady && (!ready || buffering || playing()); }
    boolean preparing() { return (!ready || buffering) && playWhenReady && error.isEmpty(); }
    int position() {
        if (dlna != null) return Math.min(dlnaDuration > 0 ? dlnaDuration : Integer.MAX_VALUE,
            dlnaPosition + (dlnaPlaying ? (int)Math.min(10000, android.os.SystemClock.elapsedRealtime() - dlnaPositionAt) : 0));
        if (castPlayback != null) return (int) Math.max(0, Math.min(Integer.MAX_VALUE, castPlayback.player.getCurrentPosition()));
        return ready ? media.getCurrentPosition() : 0;
    }
    int duration() {
        if (dlna != null) return dlnaDuration;
        if (castPlayback != null) return (int) Math.max(0, Math.min(Integer.MAX_VALUE, castPlayback.player.getDuration()));
        return ready ? Math.max(0, media.getDuration()) : 0;
    }
    int bufferedPosition() {
        if (cachedSource != null && cachedSource.getSize() > 0) {
            // Byte coverage is an estimate for variable bitrate audio; only contiguous data counts.
            return (int) Math.min(duration(), duration() * cachedSource.bufferedBytes() / cachedSource.getSize());
        }
        if (dlna != null) return position();
        if (castPlayback != null) return (int) Math.max(0, Math.min(Integer.MAX_VALUE, castPlayback.player.getBufferedPosition()));
        return (int) (duration() * (long) buffered / 100);
    }
    void toggle() { if (wantsPause()) pause(); else resume(); }
    void pause() {
        pausedForFocus = false;
        playWhenReady = false;
        if (dlna != null) {
            dlnaPosition = position();
            dlnaPlaying = false;
            dlnaCommand(DlnaController.Session::pause);
            save();
        } else if (castPlayback != null) { castPlayback.player.pause(); save(); }
        else if (playing()) { media.pause(); save(); }
        effects.refreshPan();
        update();
    }
    void resume() {
        if (!error.isEmpty() && queue != null) { select(index(), false); return; }
        pausedForFocus = false;
        playWhenReady = true;
        if (!ready) { update(); return; }
        if (dlna != null) {
            if (ended) { ended = false; seek(0); }
            dlnaPositionAt = android.os.SystemClock.elapsedRealtime();
            dlnaPlaying = true;
            dlnaCommand(DlnaController.Session::play);
            save();
            update();
            return;
        }
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (ended) { ended = false; seek(0); }
            if (castPlayback != null) {
                if (airPlay != null) airPlay.playback(true);
                castPlayback.player.play();
            }
            else media.start();
            if (cachedSource == null) finishOnlineCache();
            applySpeed();
            save();
            update();
        } else {
            playWhenReady = false;
            update();
        }
    }
    void seek(int milliseconds) {
        if (ready && duration() > 0) {
            if (milliseconds < duration()) ended = false;
            int target = Math.max(0, Math.min(duration(), milliseconds));
            if (dlna != null) {
                dlnaPosition = target;
                dlnaPositionAt = android.os.SystemClock.elapsedRealtime();
                dlnaCommand(receiver -> receiver.seek(target));
            } else if (castPlayback != null) {
                if (airPlay != null) airPlay.discardAudio();
                castPlayback.player.seekTo(target);
            } else media.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            update();
        }
    }
    void changeSpeed(float value) {
        speed = value;
        if (book && queue != null) sources.settings.edit().putFloat(bookSpeedKey(), value).apply();
        applySpeed();
        update();
    }
    private void applySpeed() {
        if (!ready || dlna != null) return;
        if (castPlayback != null) castPlayback.player.setPlaybackSpeed(speed);
        else effects.playbackParams(media, speed);
    }

    EffectSettings effectSettings() { return effectSettings; }
    String effectStatus() { return effects.status(); }
    void updateEffects(boolean pitchChanged) {
        effects.apply();
        if (pitchChanged) applySpeed();
    }
    private String bookSpeedKey() {
        MediaEntry entry = entries().get(index());
        return "book-speed:" + entry.source() + ":" + entry.raw().optString("_book", entry.subtitle());
    }
    void setSleep(int minutes) {
        handler.removeCallbacks(sleepTask);
        sleepAfterItem = minutes < 0;
        sleepAt = minutes > 0 ? android.os.SystemClock.elapsedRealtime() + minutes * 60_000L : 0;
        if (sleepAt != 0) handler.postDelayed(sleepTask, minutes * 60_000L);
    }
    long sleepRemaining() { return Math.max(0, sleepAt - android.os.SystemClock.elapsedRealtime()); }
    boolean sleepAfterItem() { return sleepAfterItem; }
    private boolean checkSleep() {
        if (sleepAt == 0 || sleepRemaining() > 0) return false;
        setSleep(0);
        pause();
        return true;
    }
    private void save() {
        if (ready && !ended && book && duration() > 0 && position() < duration() - 2000) {
            getSharedPreferences("progress", MODE_PRIVATE).edit().putInt(key, position()).apply();
        }
        if (ready && !book && musicContinuation != null && !musicContinuationMetadata.isEmpty()) {
            var edit = sources.settings.edit().putInt(MusicResume.POSITION_KEY, ended ? 0 : position())
                .putInt(MusicResume.DURATION_KEY, duration());
            if (!musicContinuationMetadata.equals(savedMusicMetadata)) {
                edit.putString(MusicResume.KEY, musicContinuationMetadata);
                savedMusicMetadata = musicContinuationMetadata;
            }
            edit.apply();
        }
    }
    private void release() {
        dlnaRevision++;
        handler.removeCallbacks(dlnaPoll);
        dlnaPolling = false;
        dlnaPlaying = false;
        if (dlnaStream != null) { dlnaStream.close(); dlnaStream = null; }
        if (dlna != null) {
            var target = dlna;
            dlnaIo.execute(() -> { try { target.stop(); } catch (Exception ignored) {} });
        }
        ready = false;
        pausedForFocus = false;
        cachedPlayback = false;
        buffering = false;
        if (probe != null) { probe.cancel(true); probe = null; }
        if (audioDetailsTask != null) { audioDetailsTask.cancel(true); audioDetailsTask = null; }
        if (castPlayback != null) {
            var route = castPlayback;
            castPlayback = null;
            if (airPlay != null && !ended) {
                airPlay.playback(false);
                airPlay.discardAudio();
            }
            route.player.release();
        }
        if (cachedSource != null) { cachedSource.close(); cachedSource = null; }
        audio.abandonAudioFocusRequest(focus);
        effects.release();
        if (media != null) { media.release(); media = null; }
    }
    @Override public void onDestroy() {
        selection++;
        if (artworkTask != null) artworkTask.cancel(true);
        artworkIo.shutdownNow();
        artwork = null;
        miniLyricRequest++;
        if (miniLyricTask != null) miniLyricTask.cancel(true);
        miniLyricIo.shutdownNow();
        cancelResolution();
        cacheIo.shutdownNow();
        cacheWriter.shutdownNow();
        audioDetailsIo.shutdownNow();
        save();
        handler.removeCallbacksAndMessages(null);
        release();
        dlna = null;
        dlnaIo.shutdown();
        if (dlnaWake != null) { if (dlnaWake.isHeld()) dlnaWake.release(); dlnaWake = null; }
        if (airPlay != null) { airPlay.close(); airPlay = null; }
        session.release();
        unregisterReceiver(headphones);
        active = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
