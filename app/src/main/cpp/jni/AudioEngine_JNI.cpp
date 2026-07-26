#include <jni.h>
#include <string>
#include <memory>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "AudioEngine.h"
// Only the FLAC decoder declarations here — the DR_FLAC_IMPLEMENTATION
// instantiation lives in Track.cpp.
#include "dr_flac.h"

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
    if (!(channels == 1 || channels == 2)) {
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
