package app.soundshelf;

import android.media.AudioDeviceInfo;
import android.media.AudioRouting;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class MusicEffects {
    private final Handler handler;
    private final EffectSettings settings;
    private MediaPlayer player;
    private DynamicsProcessing dynamics;
    private Equalizer equalizer;
    private PresetReverb reverb;
    private EffectSettings.Room appliedRoom;
    private final int[] appliedGain = new int[EffectSettings.FREQUENCIES.length];
    private int appliedPitch = 100;
    private float appliedSpeed = 1f;
    private boolean eqFailed, reverbFailed, pitchFailed;
    private boolean routedToHeadphones;
    private long lastPan;
    private double angle;
    private final Runnable pan = this::panFrame;
    private final AudioRouting.OnRoutingChangedListener routingChanged = device -> refreshPan();

    MusicEffects(Handler handler, EffectSettings settings) {
        this.handler = handler;
        this.settings = settings;
    }

    void attach(MediaPlayer media) {
        release();
        player = media;
        player.addOnRoutingChangedListener(routingChanged, handler);
        apply();
    }

    void apply() {
        if (player == null) return;
        applyEq();
        applyReverb();
        refreshPan();
    }

    void playbackParams(MediaPlayer media, float speed) {
        if (!media.isPlaying()) return;
        int pitch = media == player && settings.enabled ? settings.pitch : 100;
        if (media == player && pitch == appliedPitch && speed == appliedSpeed) {
            refreshPan();
            return;
        }
        pitchFailed = false;
        try {
            media.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch / 100f)
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL));
            if (media == player) { appliedPitch = pitch; appliedSpeed = speed; }
        } catch (RuntimeException rejected) {
            pitchFailed = pitch != 100;
            try {
                media.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f));
                if (media == player) { appliedPitch = 100; appliedSpeed = speed; }
            }
            catch (RuntimeException ignored) {}
        }
        refreshPan();
    }

    private void applyEq() {
        eqFailed = false;
        if (!settings.enabled || !settings.hasEq()) { clearEq(); return; }
        if ((equalizer != null || dynamics != null) && Arrays.equals(settings.gain, appliedGain)) return;
        int session = player.getAudioSessionId();
        if (session <= 0) { eqFailed = true; return; }
        if (equalizer == null) {
            try {
                if (dynamics == null) {
                    var config = new DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2, true, settings.gain.length, false, 0, false, 0, false).build();
                    dynamics = new DynamicsProcessing(0, session, config);
                }
                var curve = new DynamicsProcessing.Eq(true, true, settings.gain.length);
                for (int i = 0; i < settings.gain.length; i++) {
                    curve.setBand(i, new DynamicsProcessing.EqBand(true, EffectSettings.FREQUENCIES[i], settings.gain[i]));
                }
                dynamics.setPreEqAllChannelsTo(curve);
                if (dynamics.getEnabled() || dynamics.setEnabled(true) == AudioEffect.SUCCESS) {
                    System.arraycopy(settings.gain, 0, appliedGain, 0, appliedGain.length);
                    return;
                }
            } catch (RuntimeException ignored) {}
            clearEq();
        }
        try {
            if (equalizer == null) equalizer = new Equalizer(0, session);
            short[] range = equalizer.getBandLevelRange();
            for (short i = 0; i < equalizer.getNumberOfBands(); i++) {
                int level = Math.round(100 * settings.gainAt(equalizer.getCenterFreq(i) / 1000f));
                equalizer.setBandLevel(i, (short) EffectSettings.limit(level, range[0], range[1]));
            }
            eqFailed = !equalizer.getEnabled() && equalizer.setEnabled(true) != AudioEffect.SUCCESS;
        } catch (RuntimeException ignored) { eqFailed = true; }
        if (eqFailed) clearEq();
        else System.arraycopy(settings.gain, 0, appliedGain, 0, appliedGain.length);
    }

    private void applyReverb() {
        reverbFailed = false;
        if (!settings.enabled || !settings.hasReverb()) { clearReverb(); return; }
        try {
            if (reverb == null) {
                reverb = new PresetReverb(0, 0);
                player.attachAuxEffect(reverb.getId());
            }
            if (settings.room != appliedRoom) {
                short room = switch (settings.room) {
                    case SMALL -> PresetReverb.PRESET_SMALLROOM;
                    case MEDIUM -> PresetReverb.PRESET_MEDIUMROOM;
                    case LARGE -> PresetReverb.PRESET_LARGEROOM;
                    case HALL -> PresetReverb.PRESET_MEDIUMHALL;
                    case CHURCH -> PresetReverb.PRESET_LARGEHALL;
                    case PLATE -> PresetReverb.PRESET_PLATE;
                    case OFF -> PresetReverb.PRESET_NONE;
                };
                reverb.setPreset(room);
                appliedRoom = settings.room;
            }
            reverbFailed = !reverb.getEnabled() && reverb.setEnabled(true) != AudioEffect.SUCCESS;
            if (!reverbFailed) player.setAuxEffectSendLevel(settings.wet / 100f);
        } catch (RuntimeException ignored) { reverbFailed = true; }
        if (reverbFailed) clearReverb();
    }

    void refreshPan() {
        handler.removeCallbacks(pan);
        lastPan = SystemClock.elapsedRealtime();
        routedToHeadphones = false;
        try {
            routedToHeadphones = player != null && settings.enabled && settings.surround && player.isPlaying() && headphoneOutput();
        } catch (RuntimeException ignored) {}
        panFrame();
    }

    private void panFrame() {
        if (player == null) return;
        float volume = reverb == null ? 1f : settings.dry / 100f;
        try {
            if (!settings.enabled || !settings.surround || !player.isPlaying() || !routedToHeadphones) {
                player.setVolume(volume, volume);
                return;
            }
            long now = SystemClock.elapsedRealtime();
            angle = (angle + (now - lastPan) * .00018 * settings.speed) % (Math.PI * 2);
            lastPan = now;
            double panPosition = Math.sin(angle) * (.40 + settings.distance / 55.0);
            float distance = (float) (1 - (.5 + .5 * Math.cos(angle)) * settings.distance / 100);
            float left = (float) Math.sqrt(1 - Math.max(0, panPosition));
            float right = (float) Math.sqrt(1 + Math.min(0, panPosition));
            player.setVolume(volume * distance * left, volume * distance * right);
            handler.postDelayed(pan, 40);
        } catch (RuntimeException ignored) {}
    }

    private boolean headphoneOutput() {
        if (Build.VERSION.SDK_INT >= 36) {
            List<AudioDeviceInfo> routes = player.getRoutedDevices();
            if (!routes.isEmpty()) return routes.stream().allMatch(MusicEffects::headphone);
        }
        return headphone(player.getRoutedDevice());
    }

    private static boolean headphone(AudioDeviceInfo route) {
        if (route == null) return false;
        return switch (route.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true;
            default -> false;
        };
    }

    String status() {
        if (!settings.enabled) return "原声播放 · 音效已关闭";
        if (!settings.hasEffects()) return "原声播放 · 未启用额外音效";
        if (player == null) return "音效将在音乐播放时应用";
        var parts = new ArrayList<String>();
        if (settings.hasEq()) parts.add(eqFailed ? "均衡器不可用" : equalizer != null ? "均衡器 · 设备频段" : "十段均衡器");
        if (settings.hasReverb()) parts.add(reverbFailed ? "混响不可用" : settings.room.label + "混响");
        if (settings.pitch != 100) parts.add(pitchFailed ? "升降调不可用" : player.isPlaying() ? "升降调" : "升降调等待播放");
        if (settings.surround) parts.add(routedToHeadphones ? "耳机环绕" : "环绕等待耳机播放");
        return String.join("  ·  ", parts);
    }

    private void clearEq() {
        if (dynamics != null) { dynamics.release(); dynamics = null; }
        if (equalizer != null) { equalizer.release(); equalizer = null; }
    }

    private void clearReverb() {
        if (reverb == null) return;
        try {
            player.setAuxEffectSendLevel(0f);
            player.attachAuxEffect(0);
        } catch (RuntimeException ignored) {}
        reverb.release();
        reverb = null;
        appliedRoom = null;
    }

    void release() {
        handler.removeCallbacks(pan);
        clearEq();
        clearReverb();
        if (player != null) player.removeOnRoutingChangedListener(routingChanged);
        player = null;
        angle = 0;
        appliedPitch = 100;
        appliedSpeed = 1f;
        routedToHeadphones = false;
        eqFailed = reverbFailed = pitchFailed = false;
    }
}
