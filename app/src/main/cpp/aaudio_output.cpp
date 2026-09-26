#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <array>
#include <cstdint>
#include <new>

// Resolve at runtime: this APK also supports Android 4.1, where linking libaaudio would prevent
// the entire JNI library from loading. No audio callbacks enter Java or hold a JVM array pinned.
#define AAUDIO_FUNCTIONS(X) \
    X(AAudio_createStreamBuilder, int32_t, (AAudioStreamBuilder**)) \
    X(AAudioStreamBuilder_delete, int32_t, (AAudioStreamBuilder*)) \
    X(AAudioStreamBuilder_setDirection, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setSharingMode, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setPerformanceMode, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setSampleRate, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setChannelCount, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setFormat, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setBufferCapacityInFrames, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_openStream, int32_t, (AAudioStreamBuilder*, AAudioStream**)) \
    X(AAudioStream_getSampleRate, int32_t, (AAudioStream*)) \
    X(AAudioStream_getChannelCount, int32_t, (AAudioStream*)) \
    X(AAudioStream_getFormat, int32_t, (AAudioStream*)) \
    X(AAudioStream_getPerformanceMode, int32_t, (AAudioStream*)) \
    X(AAudioStream_getBufferCapacityInFrames, int32_t, (AAudioStream*)) \
    X(AAudioStream_getBufferSizeInFrames, int32_t, (AAudioStream*)) \
    X(AAudioStream_setBufferSizeInFrames, int32_t, (AAudioStream*, int32_t)) \
    X(AAudioStream_getFramesPerBurst, int32_t, (AAudioStream*)) \
    X(AAudioStream_getXRunCount, int32_t, (AAudioStream*)) \
    X(AAudioStream_requestStart, int32_t, (AAudioStream*)) \
    X(AAudioStream_requestPause, int32_t, (AAudioStream*)) \
    X(AAudioStream_requestFlush, int32_t, (AAudioStream*)) \
    X(AAudioStream_waitForStateChange, int32_t, (AAudioStream*, aaudio_stream_state_t, aaudio_stream_state_t*, int64_t)) \
    X(AAudioStream_requestStop, int32_t, (AAudioStream*)) \
    X(AAudioStream_write, int32_t, (AAudioStream*, const void*, int32_t, int64_t)) \
    X(AAudioStream_close, int32_t, (AAudioStream*))

struct Api {
// Explicit signatures avoid referencing declarations marked unavailable at the APK's minSdk.
#define DECLARE(name, result, args) using name##Fn = result (*) args; name##Fn name = nullptr;
    AAUDIO_FUNCTIONS(DECLARE)
#undef DECLARE
    bool ready = false;
    Api() {
        void* library = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) return;
#define LOAD(name, result, args) name = reinterpret_cast<name##Fn>(dlsym(library, #name)); if (!name) return;
        AAUDIO_FUNCTIONS(LOAD)
#undef LOAD
        ready = true;
        // Kept loaded for the process lifetime; every output stream shares these function pointers.
    }
};

static Api& api() { static Api instance; return instance; }
struct Output {
    AAudioStream* stream = nullptr;
    std::array<jshort, 960> scratch{}; // exactly one 10ms, stereo PCM16 block
};
static Output* output(jlong handle) { return reinterpret_cast<Output*>(static_cast<intptr_t>(handle)); }

extern "C" JNIEXPORT jlong JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_open(JNIEnv*, jobject) {
    auto& a = api();
    if (!a.ready) return 0;
    AAudioStreamBuilder* builder = nullptr;
    if (a.AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder) return 0;
    a.AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    // Multiple AA channels and other Android apps must retain access to the output device.
    a.AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    a.AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    a.AAudioStreamBuilder_setSampleRate(builder, 48000);
    a.AAudioStreamBuilder_setChannelCount(builder, 2);
    a.AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    a.AAudioStreamBuilder_setBufferCapacityInFrames(builder, 19200);
    auto* sink = new (std::nothrow) Output();
    if (!sink) { a.AAudioStreamBuilder_delete(builder); return 0; }
    const auto result = a.AAudioStreamBuilder_openStream(builder, &sink->stream);
    a.AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || !sink->stream) {
        __android_log_print(ANDROID_LOG_WARN, "NativeAAudio", "open failed: %d", result);
        delete sink;
        return 0;
    }
    if (a.AAudioStream_getSampleRate(sink->stream) != 48000 ||
        a.AAudioStream_getChannelCount(sink->stream) != 2 ||
        a.AAudioStream_getFormat(sink->stream) != AAUDIO_FORMAT_PCM_I16) {
        a.AAudioStream_close(sink->stream);
        delete sink;
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, "NativeAAudio", "opened shared PCM16, performance=%d, burst=%d, capacity=%d",
        a.AAudioStream_getPerformanceMode(sink->stream), a.AAudioStream_getFramesPerBurst(sink->stream),
        a.AAudioStream_getBufferCapacityInFrames(sink->stream));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(sink));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_start(JNIEnv*, jobject, jlong handle) {
    auto* sink = output(handle);
    return sink ? api().AAudioStream_requestStart(sink->stream) : AAUDIO_ERROR_INVALID_HANDLE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_pause(JNIEnv*, jobject, jlong handle) {
    auto* sink = output(handle);
    if (!sink) return AAUDIO_ERROR_INVALID_HANDLE;
    auto& a = api();
    auto result = a.AAudioStream_requestPause(sink->stream);
    if (result < 0) return result;
    aaudio_stream_state_t state = AAUDIO_STREAM_STATE_UNINITIALIZED;
    result = a.AAudioStream_waitForStateChange(sink->stream, AAUDIO_STREAM_STATE_PAUSING, &state, 100'000'000);
    if (result < 0) return result;
    if (state != AAUDIO_STREAM_STATE_PAUSED) return AAUDIO_ERROR_INVALID_STATE;
    result = a.AAudioStream_requestFlush(sink->stream);
    if (result < 0) return result;
    result = a.AAudioStream_waitForStateChange(sink->stream, AAUDIO_STREAM_STATE_FLUSHING, &state, 100'000'000);
    if (result < 0) return result;
    return state == AAUDIO_STREAM_STATE_FLUSHED ? AAUDIO_OK : AAUDIO_ERROR_INVALID_STATE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_write(JNIEnv* env, jobject, jlong handle,
        jshortArray data, jint offset, jint count) {
    auto* sink = output(handle);
    if (!sink || !data) return AAUDIO_ERROR_INVALID_HANDLE;
    if (offset < 0 || count < 0 || count > 960 || (count % 2) != 0 ||
        offset > env->GetArrayLength(data) - count) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    env->GetShortArrayRegion(data, offset, count, sink->scratch.data());
    if (env->ExceptionCheck()) return AAUDIO_ERROR_INTERNAL;
    // A bounded blocking write clocks the existing high-priority 10ms mixer. Partial results are
    // retried by AudioWriteLoop; shutdown cannot get stuck in an unbounded native write.
    const auto frames = api().AAudioStream_write(sink->stream, sink->scratch.data(), count / 2, 10'000'000);
    return frames < 0 ? frames : frames * 2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_setBufferFrames(JNIEnv*, jobject, jlong handle, jint frames) {
    auto* sink = output(handle);
    return sink ? api().AAudioStream_setBufferSizeInFrames(sink->stream, frames) : AAUDIO_ERROR_INVALID_HANDLE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_stat(JNIEnv*, jobject, jlong handle, jint kind) {
    auto* sink = output(handle);
    if (!sink) return AAUDIO_ERROR_INVALID_HANDLE;
    auto& a = api();
    switch (kind) {
        case 0: return a.AAudioStream_getBufferCapacityInFrames(sink->stream);
        case 1: return a.AAudioStream_getBufferSizeInFrames(sink->stream);
        case 2: return a.AAudioStream_getFramesPerBurst(sink->stream);
        case 3: return a.AAudioStream_getXRunCount(sink->stream);
        default: return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_close(JNIEnv*, jobject, jlong handle) {
    auto* sink = output(handle);
    if (!sink) return;
    // Only called by the owning mixer thread, after its final write has returned.
    api().AAudioStream_requestStop(sink->stream);
    api().AAudioStream_close(sink->stream);
    delete sink;
}
