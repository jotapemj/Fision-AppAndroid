#include <jni.h>
#include <string>
#include <memory>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "AudioEngine.h"
// Only the FLAC decoder declarations here — the DR_FLAC_IMPLEMENTATION
// instantiation lives in Track.cpp.
#include "dr_flac.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeCreate(JNIEnv*, jclass){
    return reinterpret_cast<jlong>(new AudioEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeDispose(JNIEnv*, jclass, jlong h){
    auto* e = reinterpret_cast<AudioEngine*>(h); delete e;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeAddTrack(JNIEnv* env, jclass, jlong h, jstring path_, jboolean loop){
    const char* path = env->GetStringUTFChars(path_, nullptr);
    auto* e = reinterpret_cast<AudioEngine*>(h);
    int id = e->addTrack(path, loop);
    env->ReleaseStringUTFChars(path_, path);
    return id;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTempo(JNIEnv*, jclass, jlong h, jint id, jfloat t){
    reinterpret_cast<AudioEngine*>(h)->setTempo(id, t);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetPitch(JNIEnv*, jclass, jlong h, jint id, jfloat s){
    reinterpret_cast<AudioEngine*>(h)->setPitch(id, s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetVolume(JNIEnv*, jclass, jlong h, jint id, jfloat L, jfloat R){
    reinterpret_cast<AudioEngine*>(h)->setVolume(id, L, R);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetDuckFactor(JNIEnv*, jclass, jlong h, jfloat f){
    reinterpret_cast<AudioEngine*>(h)->setDuckFactor(f);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeStart(JNIEnv*, jclass, jlong h){
    return reinterpret_cast<AudioEngine*>(h)->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativePause(JNIEnv*, jclass, jlong h){
    reinterpret_cast<AudioEngine*>(h)->pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeResume(JNIEnv*, jclass, jlong h){
    reinterpret_cast<AudioEngine*>(h)->resume();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeStop(JNIEnv*, jclass, jlong h){
    reinterpret_cast<AudioEngine*>(h)->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSeekTo(JNIEnv*, jclass, jlong h, jint id, jlong ms){
    reinterpret_cast<AudioEngine*>(h)->seekToMs(id, ms);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetCurrentPosition(JNIEnv*, jclass, jlong h, jint id){
    return reinterpret_cast<AudioEngine*>(h)->getCurrentPositionMs(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSeekToAll(JNIEnv*, jclass, jlong h, jlong ms){
    reinterpret_cast<AudioEngine*>(h)->seekToAll((int64_t)ms);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetCurrentPositionAll(JNIEnv*, jclass, jlong h){
    return reinterpret_cast<AudioEngine*>(h)->getCurrentPositionAllMs();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetDuration(JNIEnv*, jclass, jlong h){
    return reinterpret_cast<AudioEngine*>(h)->getDurationMs();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeGetOutputLatencyMs(JNIEnv*, jclass, jlong h){
    return reinterpret_cast<AudioEngine*>(h)->getOutputLatencyMs();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetClickWav(JNIEnv* env, jclass, jlong h, jstring path_){
    const char* path = env->GetStringUTFChars(path_, nullptr);
    bool ok = reinterpret_cast<AudioEngine*>(h)->setClickWav(path);
    env->ReleaseStringUTFChars(path_, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeScheduleClickAt(JNIEnv*, jclass, jlong h, jlong sourceMs){
    reinterpret_cast<AudioEngine*>(h)->scheduleClickAt(sourceMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeClearClickSchedule(JNIEnv*, jclass, jlong h){
    reinterpret_cast<AudioEngine*>(h)->clearClickSchedule();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetClickEnabled(JNIEnv*, jclass, jlong h, jboolean e){
    reinterpret_cast<AudioEngine*>(h)->setClickEnabled(e == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetClickDuckTrack(JNIEnv*, jclass, jlong h, jint trackId){
    reinterpret_cast<AudioEngine*>(h)->setClickDuckTrack(trackId);
}

// === Per-track 3D mode ===
extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrack3DEnabled(JNIEnv*, jclass, jlong h, jint id, jboolean e){
    reinterpret_cast<AudioEngine*>(h)->setTrack3DEnabled(id, e == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrack3DPosition(JNIEnv*, jclass, jlong h, jint id, jfloat azimuthRad, jfloat distance, jfloat elevationDeg){
    reinterpret_cast<AudioEngine*>(h)->setTrack3DPosition(id, azimuthRad, distance, elevationDeg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeSetTrack3DOmnipresent(JNIEnv*, jclass, jlong h, jint id, jboolean omni){
    reinterpret_cast<AudioEngine*>(h)->setTrack3DOmnipresent(id, omni == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jotapeveloper_fision_AudioEngine_nativeInitHrtf(JNIEnv* env, jclass, jlong h, jobject assetMgrJava, jstring assetPath_){
    AAssetManager* mgr = AAssetManager_fromJava(env, assetMgrJava);
    const char* p = env->GetStringUTFChars(assetPath_, nullptr);
    bool ok = reinterpret_cast<AudioEngine*>(h)->initHrtfFromAssets(mgr, p);
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
