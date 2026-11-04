#include <jni.h>
#include <string>
#include <memory>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "AudioEngine.h"
// Only the FLAC decoder declarations here — the DR_FLAC_IMPLEMENTATION
// instantiation lives in Track.cpp.
#include "dr_flac.h"
#include "WavReader.h"
#include <cstring>
#include <strings.h>
#include <vector>

// Guard against calls arriving after dispose() zeroed the Java handle.
// Race window example: the service disposes the engine while a background
// timer in a client Activity is still calling read/write natives.
static inline AudioEngine* asEngine(jlong h) {
    return reinterpret_cast<AudioEngine*>(h);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeCreate(JNIEnv*, jclass){
    return reinterpret_cast<jlong>(new AudioEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeDispose(JNIEnv*, jclass, jlong h){
    if (h == 0) return;
    delete asEngine(h);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeAddTrack(JNIEnv* env, jclass, jlong h, jstring path_, jboolean loop){
    if (h == 0) return -1;
    const char* path = env->GetStringUTFChars(path_, nullptr);
    int id = asEngine(h)->addTrack(path, loop);
    env->ReleaseStringUTFChars(path_, path);
    return id;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTempo(JNIEnv*, jclass, jlong h, jint id, jfloat t){
    if (h == 0) return;
    asEngine(h)->setTempo(id, t);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetPitch(JNIEnv*, jclass, jlong h, jint id, jfloat s){
    if (h == 0) return;
    asEngine(h)->setPitch(id, s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetVolume(JNIEnv*, jclass, jlong h, jint id, jfloat L, jfloat R){
    if (h == 0) return;
    asEngine(h)->setVolume(id, L, R);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetDuckFactor(JNIEnv*, jclass, jlong h, jfloat f){
    if (h == 0) return;
    asEngine(h)->setDuckFactor(f);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeStart(JNIEnv*, jclass, jlong h){
    if (h == 0) return JNI_FALSE;
    return asEngine(h)->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativePause(JNIEnv*, jclass, jlong h){
    if (h == 0) return;
    asEngine(h)->pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeResume(JNIEnv*, jclass, jlong h){
    if (h == 0) return;
    asEngine(h)->resume();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeStop(JNIEnv*, jclass, jlong h){
    if (h == 0) return;
    asEngine(h)->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSeekTo(JNIEnv*, jclass, jlong h, jint id, jlong ms){
    if (h == 0) return;
    asEngine(h)->seekToMs(id, ms);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetCurrentPosition(JNIEnv*, jclass, jlong h, jint id){
    if (h == 0) return 0;
    return asEngine(h)->getCurrentPositionMs(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSeekToAll(JNIEnv*, jclass, jlong h, jlong ms){
    if (h == 0) return;
    asEngine(h)->seekToAll((int64_t)ms);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetCurrentPositionAll(JNIEnv*, jclass, jlong h){
    if (h == 0) return 0;
    return asEngine(h)->getCurrentPositionAllMs();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetDuration(JNIEnv*, jclass, jlong h){
    if (h == 0) return 0;
    return asEngine(h)->getDurationMs();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetOutputLatencyMs(JNIEnv*, jclass, jlong h){
    if (h == 0) return 0;
    return asEngine(h)->getOutputLatencyMs();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetClickWav(JNIEnv* env, jclass, jlong h, jstring path_){
    if (h == 0) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(path_, nullptr);
    bool ok = asEngine(h)->setClickWav(path);
    env->ReleaseStringUTFChars(path_, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeScheduleClickAt(JNIEnv*, jclass, jlong h, jlong sourceMs){
    if (h == 0) return;
    asEngine(h)->scheduleClickAt(sourceMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeClearClickSchedule(JNIEnv*, jclass, jlong h){
    if (h == 0) return;
    asEngine(h)->clearClickSchedule();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetClickEnabled(JNIEnv*, jclass, jlong h, jboolean e){
    if (h == 0) return;
    asEngine(h)->setClickEnabled(e == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetClickDuckTrack(JNIEnv*, jclass, jlong h, jint trackId){
    if (h == 0) return;
    asEngine(h)->setClickDuckTrack(trackId);
}

// === Per-track 3D mode ===
extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrack3DEnabled(JNIEnv*, jclass, jlong h, jint id, jboolean e){
    if (h == 0) return;
    asEngine(h)->setTrack3DEnabled(id, e == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrack3DPosition(JNIEnv*, jclass, jlong h, jint id, jfloat azimuthRad, jfloat distance, jfloat elevationDeg){
    if (h == 0) return;
    asEngine(h)->setTrack3DPosition(id, azimuthRad, distance, elevationDeg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrack3DOmnipresent(JNIEnv*, jclass, jlong h, jint id, jboolean omni){
    if (h == 0) return;
    asEngine(h)->setTrack3DOmnipresent(id, omni == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrack3DMuted(JNIEnv*, jclass, jlong h, jint id, jboolean muted){
    if (h == 0) return;
    asEngine(h)->setTrack3DMuted(id, muted == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrackSoloMuted(JNIEnv*, jclass, jlong h, jint id, jboolean muted){
    if (h == 0) return;
    asEngine(h)->setTrackSoloMuted(id, muted == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeInitHrtf(JNIEnv* env, jclass, jlong h, jobject assetMgrJava, jstring assetPath_){
    if (h == 0) return JNI_FALSE;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetMgrJava);
    const char* p = env->GetStringUTFChars(assetPath_, nullptr);
    bool ok = asEngine(h)->initHrtfFromAssets(mgr, p);
    env->ReleaseStringUTFChars(assetPath_, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// === Standalone FLAC decoder (used by the Java offline export) ===
// Returns a float[] interleaved in [ch, ch, ch, ...] order with channels and
// sampleRate written to outMeta[2] = {ch, sampleRate}. Returns null on failure.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jotapeveloper_fision_FlacDecoderJni_nativeDecodeFlac(
        JNIEnv* env, jclass, jstring path_, jintArray outMeta){
    const char* path = env->GetStringUTFChars(path_, nullptr);
    unsigned int channels = 0, sampleRate = 0;
    drflac_uint64 totalFrames = 0;
    float* samples = drflac_open_file_and_read_pcm_frames_f32(
            path, &channels, &sampleRate, &totalFrames, nullptr);
    env->ReleaseStringUTFChars(path_, path);
    if (!samples) return nullptr;
    // A Java array holds at most INT32_MAX elements.
    if (!(channels == 1 || channels == 2) || sampleRate == 0
            || totalFrames * channels > (drflac_uint64) INT32_MAX) {
        drflac_free(samples, nullptr);
        return nullptr;
    }
    jsize total = (jsize)(totalFrames * channels);
    jfloatArray out = env->NewFloatArray(total);
    if (!out) { drflac_free(samples, nullptr); return nullptr; }
    env->SetFloatArrayRegion(out, 0, total, samples);
    drflac_free(samples, nullptr);
    if (outMeta != nullptr && env->GetArrayLength(outMeta) >= 2) {
        jint meta[2] = { (jint)channels, (jint)sampleRate };
        env->SetIntArrayRegion(outMeta, 0, 2, meta);
    }
    return out;
}

// === Offline stem energy ===
namespace {
// Accumulates the mono power of consecutive audio blocks per beat window.
struct BeatPowerMeter {
    const std::vector<double>& windowStarts; // first frame of each window
    std::vector<double> sums;
    std::vector<long long> counts;
    size_t window = 0;
    long long frame = 0;

    explicit BeatPowerMeter(const std::vector<double>& starts)
            : windowStarts(starts), sums(starts.size(), 0.0), counts(starts.size(), 0) {}

    void add(const float* pcm, size_t frames, int channels) {
        for (size_t i = 0; i < frames; i++, frame++) {
            while (window + 1 < windowStarts.size() && frame >= windowStarts[window + 1]) window++;
            if (frame < windowStarts[window]) continue;
            float mono = 0.0f;
            for (int c = 0; c < channels; c++) mono += pcm[i * channels + c];
            mono /= (float) channels;
            sums[window] += (double) mono * mono;
            counts[window]++;
        }
    }
};

std::vector<double> windowStartFrames(const std::vector<float>& beats, unsigned int sampleRate) {
    std::vector<double> starts(beats.size());
    for (size_t i = 0; i < beats.size(); i++) starts[i] = (double) beats[i] * sampleRate;
    return starts;
}

bool measureFlac(const char* path, const std::vector<float>& beats,
                 std::vector<double>& sums, std::vector<long long>& counts) {
    drflac* flac = drflac_open_file(path, nullptr);
    if (!flac) return false;
    const int channels = flac->channels;
    if (channels < 1 || flac->sampleRate == 0) { drflac_close(flac); return false; }
    const std::vector<double> starts = windowStartFrames(beats, flac->sampleRate);
    BeatPowerMeter meter(starts);
    std::vector<float> block(4096 * channels);
    drflac_uint64 read;
    while ((read = drflac_read_pcm_frames_f32(flac, 4096, block.data())) > 0) {
        meter.add(block.data(), (size_t) read, channels);
    }
    drflac_close(flac);
    sums = std::move(meter.sums);
    counts = std::move(meter.counts);
    return true;
}

bool measureWav(const char* path, const std::vector<float>& beats,
                std::vector<double>& sums, std::vector<long long>& counts) {
    WavData wav;
    if (!loadWavPcmToFloat(path, wav) || wav.channels < 1 || wav.sampleRate <= 0) return false;
    const std::vector<double> starts = windowStartFrames(beats, (unsigned int) wav.sampleRate);
    BeatPowerMeter meter(starts);
    meter.add(wav.pcm.data(), wav.pcm.size() / wav.channels, wav.channels);
    sums = std::move(meter.sums);
    counts = std::move(meter.counts);
    return true;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jotapeveloper_fision_StemEnergyJni_nativeAddBeatPower(
        JNIEnv* env, jclass, jstring path_, jfloatArray beats_, jdoubleArray power_){
    const jsize n = env->GetArrayLength(beats_);
    if (n == 0 || env->GetArrayLength(power_) != n) return JNI_FALSE;
    std::vector<float> beats(n);
    env->GetFloatArrayRegion(beats_, 0, n, beats.data());

    const char* path = env->GetStringUTFChars(path_, nullptr);
    const size_t len = std::strlen(path);
    const bool isFlac = len >= 5 && strcasecmp(path + len - 5, ".flac") == 0;
    std::vector<double> sums;
    std::vector<long long> counts;
    const bool ok = isFlac ? measureFlac(path, beats, sums, counts)
                           : measureWav(path, beats, sums, counts);
    env->ReleaseStringUTFChars(path_, path);
    if (!ok) return JNI_FALSE;

    std::vector<double> power(n);
    env->GetDoubleArrayRegion(power_, 0, n, power.data());
    for (jsize i = 0; i < n; i++) {
        if (counts[i] > 0) power[i] += sums[i] / (double) counts[i];
    }
    env->SetDoubleArrayRegion(power_, 0, n, power.data());
    return JNI_TRUE;
}

// === Per-track FX (realtime) ===
extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrackFilter(JNIEnv*, jclass, jlong h, jint id, jfloat a){
    if (h == 0) return;
    asEngine(h)->setTrackFilter(id, a);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrackEcho(JNIEnv*, jclass, jlong h, jint id, jfloat a, jfloat delayMs){
    if (h == 0) return;
    asEngine(h)->setTrackEcho(id, a, delayMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrackReverbSend(JNIEnv*, jclass, jlong h, jint id, jfloat s, jint room){
    if (h == 0) return;
    asEngine(h)->setTrackReverbSend(id, s, room);
}

// === Offline FX for the mix export ===
// One instance per stem: filter + echo (tempo 1) + volume, then its own reverb
// on the post-fader send. Reverb and echo are linear, so summing per-stem
// reverbs equals the shared bus of the realtime engine.
namespace {
struct OfflineFx {
    int sr;
    fx::Filter filter;
    fx::Echo   echo;
    fx::Reverb reverb;
    float send = 0.f;
    std::vector<float> chunk, wet, io;
    OfflineFx(int s, int room) : sr(s) { reverb.setRoom(room); reverb.setSampleRate(s); }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_StemFxOffline_nativeCreate(JNIEnv*, jclass, jint sr,
        jfloat filter, jfloat echo, jfloat echoDelayMs, jfloat send, jint room){
    auto* o = new OfflineFx(sr, room);
    o->filter.setAmount(filter);
    if (echo > 0.f) o->echo.prepare(sr);
    o->echo.setDelaySourceMs(echoDelayMs);
    o->echo.setAmount(echo);
    o->send = std::max(0.f, std::min(1.f, (float) send));
    return reinterpret_cast<jlong>(o);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_StemFxOffline_nativeDispose(JNIEnv*, jclass, jlong h){
    delete reinterpret_cast<OfflineFx*>(h);
}

// Reads [frames] from src (interleaved, srcCh channels; null = silence for the
// tail), runs the FX and ADDS the result to out (stereo) at outFrame.
extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_StemFxOffline_nativeProcess(JNIEnv* env, jclass, jlong h,
        jfloatArray src, jint srcCh, jint srcFrame, jint frames,
        jfloatArray out, jint outFrame, jfloat volL, jfloat volR){
    if (h == 0 || frames <= 0) return;
    auto* o = reinterpret_cast<OfflineFx*>(h);
    o->chunk.assign((size_t) frames * 2, 0.f);
    if (src != nullptr) {
        o->io.resize((size_t) frames * srcCh);
        env->GetFloatArrayRegion(src, srcFrame * srcCh, frames * srcCh, o->io.data());
        for (int f = 0; f < frames; ++f) {
            const float* p = o->io.data() + (size_t) f * srcCh;
            o->chunk[2 * f]     = p[0];
            o->chunk[2 * f + 1] = srcCh > 1 ? p[1] : p[0];
        }
    }
    if (o->filter.isActive()) o->filter.process(o->chunk.data(), frames, o->sr);
    if (o->echo.isActive())   o->echo.process(o->chunk.data(), frames, o->sr, 1.f);
    for (int f = 0; f < frames; ++f) { o->chunk[2 * f] *= volL; o->chunk[2 * f + 1] *= volR; }
    const bool rev = o->send > 0.f;
    if (rev) {
        o->wet.resize(o->chunk.size());
        for (size_t i = 0; i < o->chunk.size(); ++i) o->wet[i] = o->chunk[i] * o->send;
        o->reverb.process(o->wet.data(), frames);
    }
    o->io.resize(o->chunk.size());
    env->GetFloatArrayRegion(out, outFrame * 2, frames * 2, o->io.data());
    for (size_t i = 0; i < o->chunk.size(); ++i) o->io[i] += o->chunk[i] + (rev ? o->wet[i] : 0.f);
    env->SetFloatArrayRegion(out, outFrame * 2, frames * 2, o->io.data());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetTrackEnvelope(JNIEnv* env, jclass, jlong h, jint id){
    if (h == 0) return nullptr;
    std::vector<float> e = asEngine(h)->getTrackEnvelope(id);
    jfloatArray out = env->NewFloatArray((jsize) e.size());
    if (out != nullptr && !e.empty()) env->SetFloatArrayRegion(out, 0, (jsize) e.size(), e.data());
    return out;
}
