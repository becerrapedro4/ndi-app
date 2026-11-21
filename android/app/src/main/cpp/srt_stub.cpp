// Stub de SRT: misma superficie JNI que srt_bridge.cpp para que el proyecto
// compile y arranque cuando libsrt.a no está disponible (por ejemplo, un build
// local sin NDK). Todas las operaciones fallan de forma controlada.

#include <jni.h>

namespace {
constexpr jlong INVALID_HANDLE = -1;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeStartup(JNIEnv *, jobject) { return JNI_FALSE; }

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeCleanup(JNIEnv *, jobject) {}

JNIEXPORT jstring JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF("stub");
}

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeCallerCreate(JNIEnv *, jobject, jint, jstring) {
    return INVALID_HANDLE;
}

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeListenerCreate(JNIEnv *, jobject, jint, jint, jstring) {
    return INVALID_HANDLE;
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeConnect(JNIEnv *, jobject, jlong, jstring, jint) {
    return -1;
}

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeAccept(JNIEnv *, jobject, jlong, jint) {
    return INVALID_HANDLE;
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeSend(JNIEnv *, jobject, jlong, jbyteArray, jint, jint,
                                                jint) {
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeRecv(JNIEnv *, jobject, jlong, jbyteArray, jint, jint,
                                                jint) {
    return -1;
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeClose(JNIEnv *, jobject, jlong) {}

JNIEXPORT jstring JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF("libsrt no incluida en este build");
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeSocketState(JNIEnv *, jobject, jlong) { return -1; }

}  // extern "C"
