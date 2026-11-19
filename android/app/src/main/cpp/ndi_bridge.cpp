// Puente JNI entre Kotlin y el SDK de NDI para Android.
// Envia video RGBA y audio PCM float intercalado, descubre fuentes y recibe
// video/audio de cualquier fuente NDI de la red.

#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#include "Processing.NDI.Lib.h"
#include "Processing.NDI.utilities.h"

#define TAG "NdiBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::once_flag g_init_once;
bool g_init_ok = false;

struct Sender {
    NDIlib_send_instance_t instance;
};

struct Receiver {
    NDIlib_recv_instance_t instance;
};

struct Finder {
    NDIlib_find_instance_t instance;
};

void ensureInit() {
    std::call_once(g_init_once, []() {
        g_init_ok = NDIlib_initialize();
        LOGI("NDIlib_initialize -> %d (v%s)", g_init_ok ? 1 : 0, NDIlib_version());
    });
}

int64_t now100ns() {
    using namespace std::chrono;
    const auto ns = duration_cast<nanoseconds>(steady_clock::now().time_since_epoch()).count();
    return ns / 100;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_isSdkAvailable(JNIEnv *, jobject) {
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sdkVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(NDIlib_version());
}

JNIEXPORT jboolean JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_init(JNIEnv *, jobject) {
    ensureInit();
    return g_init_ok ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------- emisor ----

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendCreate(JNIEnv *env, jobject, jstring name) {
    ensureInit();
    if (!g_init_ok) return 0;

    const char *utf = env->GetStringUTFChars(name, nullptr);
    NDIlib_send_create_t desc{};
    desc.p_ndi_name = utf;
    desc.p_groups = nullptr;
    desc.clock_video = true;
    desc.clock_audio = false;

    NDIlib_send_instance_t instance = NDIlib_send_create(&desc);
    env->ReleaseStringUTFChars(name, utf);

    if (!instance) return 0;
    LOGI("Fuente NDI creada");
    return reinterpret_cast<jlong>(new Sender{instance});
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendVideo(JNIEnv *env, jobject, jlong handle,
                                               jbyteArray rgba, jint width, jint height,
                                               jint fps_num, jint fps_den) {
    auto *sender = reinterpret_cast<Sender *>(handle);
    if (!sender || !sender->instance || width <= 0 || height <= 0) return;
    if (env->GetArrayLength(rgba) < width * height * 4) return;

    uint8_t *pixels = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(rgba, nullptr));
    if (!pixels) return;

    NDIlib_video_frame_v2_t frame{};
    frame.xres = width;
    frame.yres = height;
    frame.FourCC = NDIlib_FourCC_video_type_RGBA;
    frame.frame_rate_N = fps_num > 0 ? fps_num : 30;
    frame.frame_rate_D = fps_den > 0 ? fps_den : 1;
    frame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
    frame.frame_format_type = NDIlib_frame_format_type_progressive;
    frame.timecode = now100ns();
    frame.p_data = pixels;
    frame.line_stride_in_bytes = width * 4;
    frame.p_metadata = nullptr;
    frame.timestamp = now100ns() * 100;  // nanosegundos

    NDIlib_send_send_video_v2(sender->instance, &frame);
    env->ReleasePrimitiveArrayCritical(rgba, pixels, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendAudio(JNIEnv *env, jobject, jlong handle,
                                               jfloatArray pcm, jint samples,
                                               jint channels, jint sample_rate) {
    auto *sender = reinterpret_cast<Sender *>(handle);
    if (!sender || !sender->instance) return;
    if (samples <= 0 || channels <= 0 || sample_rate <= 0) return;
    if (env->GetArrayLength(pcm) < samples * channels) return;

    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
    if (!data) return;

    NDIlib_audio_frame_interleaved_32f_t frame{};
    frame.sample_rate = sample_rate;
    frame.no_channels = channels;
    frame.no_samples = samples;
    frame.timecode = now100ns();
    frame.p_data = data;

    NDIlib_util_send_send_audio_interleaved_32f(sender->instance, &frame);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendConnections(JNIEnv *, jobject, jlong handle) {
    auto *sender = reinterpret_cast<Sender *>(handle);
    if (!sender || !sender->instance) return 0;
    return NDIlib_send_get_no_connections(sender->instance, 0);
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendDestroy(JNIEnv *, jobject, jlong handle) {
    auto *sender = reinterpret_cast<Sender *>(handle);
    if (!sender) return;
    if (sender->instance) NDIlib_send_destroy(sender->instance);
    delete sender;
}

// ----------------------------------------------------------- descubrir ----

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_findCreate(JNIEnv *, jobject) {
    ensureInit();
    if (!g_init_ok) return 0;

    NDIlib_find_create_t desc{};
    desc.show_local_sources = true;
    desc.p_groups = nullptr;
    desc.p_extra_ips = nullptr;

    NDIlib_find_instance_t instance = NDIlib_find_create_v2(&desc);
    if (!instance) return 0;
    return reinterpret_cast<jlong>(new Finder{instance});
}

JNIEXPORT jobjectArray JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_findSources(JNIEnv *env, jobject, jlong handle,
                                                 jint timeout_ms) {
    auto *finder = reinterpret_cast<Finder *>(handle);
    if (!finder || !finder->instance) return nullptr;

    NDIlib_find_wait_for_sources(finder->instance, static_cast<uint32_t>(timeout_ms));

    uint32_t count = 0;
    const NDIlib_source_t *sources = NDIlib_find_get_current_sources(finder->instance, &count);

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), string_class, nullptr);
    if (!result) return nullptr;

    for (uint32_t i = 0; i < count; ++i) {
        const char *name = sources[i].p_ndi_name ? sources[i].p_ndi_name : "";
        const char *url = sources[i].p_url_address ? sources[i].p_url_address : "";
        std::string entry = std::string(name) + "\t" + url;
        jstring item = env->NewStringUTF(entry.c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_findDestroy(JNIEnv *, jobject, jlong handle) {
    auto *finder = reinterpret_cast<Finder *>(handle);
    if (!finder) return;
    if (finder->instance) NDIlib_find_destroy(finder->instance);
    delete finder;
}

// ------------------------------------------------------------ receptor ----

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_recvCreate(JNIEnv *env, jobject, jstring name,
                                                jstring url, jboolean low_bandwidth) {
    ensureInit();
    if (!g_init_ok) return 0;

    const char *utf_name = env->GetStringUTFChars(name, nullptr);
    const char *utf_url = url ? env->GetStringUTFChars(url, nullptr) : nullptr;

    NDIlib_recv_create_v3_t desc{};
    desc.source_to_connect_to.p_ndi_name = utf_name;
    desc.source_to_connect_to.p_url_address = utf_url;
    desc.color_format = NDIlib_recv_color_format_RGBX_RGBA;
    desc.bandwidth = low_bandwidth ? NDIlib_recv_bandwidth_lowest
                                   : NDIlib_recv_bandwidth_highest;
    desc.allow_video_fields = false;
    desc.p_ndi_recv_name = "NDI App Monitor";

    NDIlib_recv_instance_t instance = NDIlib_recv_create_v3(&desc);

    env->ReleaseStringUTFChars(name, utf_name);
    if (utf_url) env->ReleaseStringUTFChars(url, utf_url);

    if (!instance) return 0;
    return reinterpret_cast<jlong>(new Receiver{instance});
}

JNIEXPORT jintArray JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_recvPoll(JNIEnv *env, jobject, jlong handle,
                                              jbyteArray rgba, jfloatArray pcm,
                                              jint timeout_ms) {
    auto *receiver = reinterpret_cast<Receiver *>(handle);
    if (!receiver || !receiver->instance) return nullptr;

    NDIlib_video_frame_v2_t video{};
    NDIlib_audio_frame_v2_t audio{};
    NDIlib_metadata_frame_t metadata{};

    const NDIlib_frame_type_e type = NDIlib_recv_capture_v2(
            receiver->instance, &video, &audio, &metadata, static_cast<uint32_t>(timeout_ms));

    int32_t out[6] = {static_cast<int32_t>(type), 0, 0, 0, 0, 0};

    if (type == NDIlib_frame_type_video) {
        const int width = video.xres;
        const int height = video.yres;
        const int stride = video.line_stride_in_bytes;
        if (width > 0 && height > 0 && video.p_data &&
            env->GetArrayLength(rgba) >= width * height * 4) {
            auto *dst = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(rgba, nullptr));
            if (dst) {
                for (int y = 0; y < height; ++y) {
                    std::memcpy(dst + static_cast<size_t>(y) * width * 4,
                                video.p_data + static_cast<size_t>(y) * stride,
                                static_cast<size_t>(width) * 4);
                }
                env->ReleasePrimitiveArrayCritical(rgba, dst, 0);
                out[1] = width;
                out[2] = height;
            }
        }
        NDIlib_recv_free_video_v2(receiver->instance, &video);
    } else if (type == NDIlib_frame_type_audio) {
        const int channels = audio.no_channels;
        const int samples = audio.no_samples;
        if (channels > 0 && samples > 0 && audio.p_data &&
            env->GetArrayLength(pcm) >= channels * samples) {
            std::vector<float> interleaved(static_cast<size_t>(channels) * samples);
            NDIlib_audio_frame_interleaved_32f_t dst{};
            dst.sample_rate = audio.sample_rate;
            dst.no_channels = channels;
            dst.no_samples = samples;
            dst.timecode = audio.timecode;
            dst.p_data = interleaved.data();
            NDIlib_util_audio_to_interleaved_32f_v2(&audio, &dst);

            auto *target = reinterpret_cast<jfloat *>(env->GetPrimitiveArrayCritical(pcm, nullptr));
            if (target) {
                std::memcpy(target, interleaved.data(),
                            sizeof(float) * static_cast<size_t>(channels) * samples);
                env->ReleasePrimitiveArrayCritical(pcm, target, 0);
                out[3] = samples;
                out[4] = channels;
                out[5] = audio.sample_rate;
            }
        }
        NDIlib_recv_free_audio_v2(receiver->instance, &audio);
    } else if (type == NDIlib_frame_type_metadata) {
        NDIlib_recv_free_metadata(receiver->instance, &metadata);
    }

    jintArray result = env->NewIntArray(6);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, 6, out);
    return result;
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_recvDestroy(JNIEnv *, jobject, jlong handle) {
    auto *receiver = reinterpret_cast<Receiver *>(handle);
    if (!receiver) return;
    if (receiver->instance) NDIlib_recv_destroy(receiver->instance);
    delete receiver;
}

}  // extern "C"
