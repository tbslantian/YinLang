#include <jni.h>
#include <android/log.h>

#include "raop_loop.h"
#include "raop_sender.h"
#include "ring_buffer.h"

#include <atomic>
#include <chrono>
#include <mutex>
#include <optional>
#include <span>
#include <string>

using namespace fxchain;
using namespace std::chrono_literals;

namespace {
struct Session {
    RingBuffer<int16_t> audio{65536};
    std::atomic<bool> stop{false};
    std::atomic<bool> playing{false};
    std::atomic<uint32_t> sampleRate{44100};
    std::mutex mutex;
    bool discard = false;
    std::optional<std::string> pin;
    std::optional<double> volume;
    struct Track { std::string title, artist, album; };
    std::optional<Track> track;
};

Session& session(jlong handle) { return *reinterpret_cast<Session*>(handle); }

std::string utf8(JNIEnv* env, jbyteArray data) {
    if (!data) return {};
    std::string value(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, value.size(), reinterpret_cast<jbyte*>(value.data()));
    return value;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_soundshelf_AirPlaySession_create(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Session());
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_run(JNIEnv* env, jobject self, jlong handle, jbyteArray host,
    jint port, jbyteArray name, jbyteArray receiverId, jbyteArray localId, jint mode,
    jbyteArray credentials, jbyteArray password) {
    auto& state = session(handle);
    auto callback = env->GetMethodID(env->GetObjectClass(self), "onNativeEvent", "(I[B)V");
    auto emit = [&](int type, const std::string& message = "") {
        auto bytes = env->NewByteArray(message.size());
        if (!bytes) return;
        env->SetByteArrayRegion(bytes, 0, message.size(), reinterpret_cast<const jbyte*>(message.data()));
        env->CallVoidMethod(self, callback, type, bytes);
        env->DeleteLocalRef(bytes);
    };
    if (state.stop.load()) return;
    RaopLoop loop;
    RaopEvents events;
    events.launched = [&](bool ok, const std::string& error) { emit(ok ? 1 : 0, error); };
    events.closed = [&] { if (!state.stop.load()) emit(2); };
    events.pinRequired = [&](const std::string&) { emit(3); };
    events.credentialsObtained = [&](const std::string&, const std::string& data) { emit(4, data); };
    RaopSender sender(loop, std::move(events), [](RaopLogLevel level, const std::string& message) {
        __android_log_write(level == RaopLogLevel::Warn ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
            "YinLangCast", message.c_str());
    });
    using Auth = RaopDeviceInfo::Auth;
    const Auth auth = mode == 4 ? Auth::HapPin : mode == 3 ? Auth::HapTransient
        : mode == 2 ? Auth::AuthSetup : mode == 1 ? Auth::Password : Auth::None;
    RaopIdentity identity;
    identity.name = "YinLang";
    identity.deviceId = utf8(env, localId);
    sender.setIdentity(identity);
    sender.setAuth(auth, mode >= 3, utf8(env, receiverId), utf8(env, credentials), utf8(env, password));
    sender.setStrictReceiverAuth(true);
    sender.setInputFormat(44100);
    sender.start(utf8(env, host), static_cast<uint16_t>(port), utf8(env, name));
    uint32_t inputRate = 44100;
    while (!state.stop.load() && sender.active()) {
        std::optional<std::string> pin;
        std::optional<double> volume;
        std::optional<Session::Track> track;
        {
            std::lock_guard lock(state.mutex);
            if (state.discard) {
                state.audio.reset();
                state.discard = false;
            }
            pin.swap(state.pin);
            volume.swap(state.volume);
            track.swap(state.track);
        }
        if (pin) sender.submitPin(*pin);
        if (volume) sender.setVolume(*volume);
        if (track) sender.setNowPlaying(track->title, track->artist, track->album);
        const auto rate = state.sampleRate.load();
        if (rate != inputRate) { sender.setInputFormat(rate); inputRate = rate; }
        sender.attachRing(state.playing.load() ? &state.audio : nullptr);
        loop.pump(sender, 10ms);
    }
    if (sender.active()) {
        sender.stop();
        // Let the socket send TEARDOWN before the transport is destroyed.
        loop.pump(sender, 20ms);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_playing(JNIEnv*, jclass, jlong handle, jboolean value) {
    session(handle).playing.store(value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_soundshelf_AirPlaySession_write(JNIEnv* env, jclass, jlong handle, jshortArray data, jint length) {
    auto& state = session(handle);
    std::lock_guard lock(state.mutex);
    if (state.stop.load() || state.discard || length < 0 || length > env->GetArrayLength(data)
        || static_cast<size_t>(length) > state.audio.availableWrite()) return JNI_FALSE;
    auto* samples = env->GetShortArrayElements(data, nullptr);
    if (!samples) return JNI_FALSE;
    const bool accepted = state.audio.tryPush(std::span<const int16_t>(samples, length));
    env->ReleaseShortArrayElements(data, samples, JNI_ABORT);
    return accepted;
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_discard(JNIEnv*, jclass, jlong handle) {
    std::lock_guard lock(session(handle).mutex);
    session(handle).discard = true;
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_pin(JNIEnv* env, jclass, jlong handle, jbyteArray code) {
    std::lock_guard lock(session(handle).mutex);
    session(handle).pin = utf8(env, code);
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_volume(JNIEnv*, jclass, jlong handle, jint value) {
    std::lock_guard lock(session(handle).mutex);
    session(handle).volume = value;
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_sampleRate(JNIEnv*, jclass, jlong handle, jint rate) {
    if (rate > 0) session(handle).sampleRate.store(static_cast<uint32_t>(rate));
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_track(JNIEnv* env, jclass, jlong handle, jbyteArray title, jbyteArray artist, jbyteArray album) {
    std::lock_guard lock(session(handle).mutex);
    session(handle).track = Session::Track{utf8(env, title), utf8(env, artist), utf8(env, album)};
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_stop(JNIEnv*, jclass, jlong handle) {
    session(handle).stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_app_soundshelf_AirPlaySession_destroy(JNIEnv*, jclass, jlong handle) {
    delete &session(handle);
}
