// Stub del puente NDI: expone los mismos símbolos que ndi_bridge.cpp para que el
// APK arranque y la app informe que el runtime NDI no está disponible, en lugar
// de abortar por UnsatisfiedLinkError. Se usa solo cuando el SDK de NDI no está
// presente en el checkout (su licencia no permite redistribuirlo).

#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_isSdkAvailable(JNIEnv *, jobject) { return JNI_FALSE; }

JNIEXPORT jstring JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sdkVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF("no disponible");
}

JNIEXPORT jboolean JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_init(JNIEnv *, jobject) { return JNI_FALSE; }

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendCreate(JNIEnv *, jobject, jstring) { return 0; }

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendVideo(JNIEnv *, jobject, jlong, jbyteArray, jint, jint,
                                               jint, jint) {}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendAudio(JNIEnv *, jobject, jlong, jfloatArray, jint, jint,
                                               jint) {}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendConnections(JNIEnv *, jobject, jlong) { return 0; }

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_sendDestroy(JNIEnv *, jobject, jlong) {}

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_findCreate(JNIEnv *, jobject) { return 0; }

JNIEXPORT jobjectArray JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_findSources(JNIEnv *, jobject, jlong, jint) { return nullptr; }

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_findDestroy(JNIEnv *, jobject, jlong) {}

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_recvCreate(JNIEnv *, jobject, jstring, jstring, jboolean) {
    return 0;
}

JNIEXPORT jintArray JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_recvPoll(JNIEnv *, jobject, jlong, jbyteArray, jfloatArray,
                                              jint) {
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_ndi_NdiNative_recvDestroy(JNIEnv *, jobject, jlong) {}

}  // extern "C"
