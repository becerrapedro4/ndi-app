// Puente JNI contra libsrt (SRT SDK oficial de Haivision).
//
// Usa la API "rich" de SRT: srt_sendmsg2 / srt_recvmsg2 con SRT_MSGCTRL, que es
// la recomendada desde SRT 1.4 (srt_sendmsg/srt_recvmsg son legacy).
//
// Los sockets se crean con srt_create_socket() y hay que llamar a srt_startup()
// una vez por proceso antes de crear ninguno.

#include <jni.h>
#include <android/log.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <cstring>
#include <string>

#include "srt.h"

#define TAG "SrtBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr jlong INVALID_HANDLE = -1;

SRTSOCKET asSock(jlong handle) { return static_cast<SRTSOCKET>(handle); }

std::string jstringToStd(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) return {};
    std::string out(utf);
    env->ReleaseStringUTFChars(value, utf);
    return out;
}

bool setInt(SRTSOCKET sock, SRT_SOCKOPT opt, int32_t value) {
    return srt_setsockflag(sock, opt, &value, sizeof(value)) != SRT_ERROR;
}

bool setBool(SRTSOCKET sock, SRT_SOCKOPT opt, bool value) {
    return srt_setsockflag(sock, opt, &value, sizeof(value)) != SRT_ERROR;
}

// Parámetros comunes de un socket en modo live (TSBPD + latencia + mensajes).
bool configureLive(SRTSOCKET sock, SRT_TRANSTYPE type, jint latencyMs, const std::string &passphrase) {
    if (srt_setsockflag(sock, SRTO_TRANSTYPE, &type, sizeof(type)) == SRT_ERROR) {
        LOGE("SRTO_TRANSTYPE falló: %s", srt_getlasterror_str());
        return false;
    }

    const int32_t latency = latencyMs > 0 ? latencyMs : 120;
    setInt(sock, SRTO_PEERLATENCY, latency);
    setInt(sock, SRTO_RCVLATENCY, latency);
    setBool(sock, SRTO_TSBPDMODE, true);
    setBool(sock, SRTO_TLPKTDROP, true);
    setBool(sock, SRTO_MESSAGEAPI, true);

    if (!passphrase.empty()) {
        if (srt_setsockflag(sock, SRTO_PASSPHRASE, passphrase.data(),
                            static_cast<int>(passphrase.size())) == SRT_ERROR) {
            LOGE("SRTO_PASSPHRASE falló (¿libsrt sin cifrado?): %s", srt_getlasterror_str());
            return false;
        }
        const int32_t keyLen = 16;
        setInt(sock, SRTO_PBKEYLEN, keyLen);
    }
    return true;
}

bool parseSockAddr(const std::string &host, jint port, sockaddr_in &out) {
    std::memset(&out, 0, sizeof(out));
    out.sin_family = AF_INET;
    out.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host.c_str(), &out.sin_addr) == 1) return true;
    // Resolución por nombre.
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo *result = nullptr;
    if (getaddrinfo(host.c_str(), nullptr, &hints, &result) != 0 || result == nullptr) return false;
    out.sin_addr = reinterpret_cast<sockaddr_in *>(result->ai_addr)->sin_addr;
    freeaddrinfo(result);
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeStartup(JNIEnv *, jobject) {
    const int rc = srt_startup();
    LOGI("srt_startup -> %d (libsrt %s)", rc, SRT_VERSION_STRING);
    return rc != SRT_ERROR ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeCleanup(JNIEnv *, jobject) {
    srt_cleanup();
}

JNIEXPORT jstring JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(SRT_VERSION_STRING);
}

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeCallerCreate(JNIEnv *env, jobject, jint latencyMs,
                                                       jstring passphrase) {
    const SRTSOCKET sock = srt_create_socket();
    if (sock == SRT_INVALID_SOCK) {
        LOGE("srt_create_socket falló: %s", srt_getlasterror_str());
        return INVALID_HANDLE;
    }
    if (!configureLive(sock, SRT_TRANSTYPE_CALLER, latencyMs, jstringToStd(env, passphrase))) {
        srt_close(sock);
        return INVALID_HANDLE;
    }
    return static_cast<jlong>(sock);
}

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeListenerCreate(JNIEnv *env, jobject, jint port,
                                                         jint latencyMs, jstring passphrase) {
    const SRTSOCKET sock = srt_create_socket();
    if (sock == SRT_INVALID_SOCK) {
        LOGE("srt_create_socket falló: %s", srt_getlasterror_str());
        return INVALID_HANDLE;
    }
    if (!configureLive(sock, SRT_TRANSTYPE_LISTENER, latencyMs, jstringToStd(env, passphrase))) {
        srt_close(sock);
        return INVALID_HANDLE;
    }

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(static_cast<uint16_t>(port));

    if (srt_bind(sock, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) == SRT_ERROR) {
        LOGE("srt_bind(%d) falló: %s", port, srt_getlasterror_str());
        srt_close(sock);
        return INVALID_HANDLE;
    }
    if (srt_listen(sock, 1) == SRT_ERROR) {
        LOGE("srt_listen falló: %s", srt_getlasterror_str());
        srt_close(sock);
        return INVALID_HANDLE;
    }
    return static_cast<jlong>(sock);
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeConnect(JNIEnv *env, jobject, jlong handle, jstring host,
                                                  jint port) {
    const SRTSOCKET sock = asSock(handle);
    if (sock == SRT_INVALID_SOCK) return -1;

    sockaddr_in addr{};
    if (!parseSockAddr(jstringToStd(env, host), port, addr)) {
        LOGE("Host no resoluble");
        return -1;
    }
    if (srt_connect(sock, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) == SRT_ERROR) {
        LOGE("srt_connect falló: %s", srt_getlasterror_str());
        return -1;
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeAccept(JNIEnv *, jobject, jlong listener,
                                                 jint timeoutMs) {
    const SRTSOCKET sock = asSock(listener);
    if (sock == SRT_INVALID_SOCK) return INVALID_HANDLE;

    setInt(sock, SRTO_RCVTIMEO, timeoutMs > 0 ? timeoutMs : 1000);

    sockaddr_in peer{};
    int peerLen = sizeof(peer);
    const SRTSOCKET accepted =
            srt_accept(sock, reinterpret_cast<sockaddr *>(&peer), &peerLen);
    if (accepted == SRT_INVALID_SOCK) return INVALID_HANDLE;
    return static_cast<jlong>(accepted);
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeSend(JNIEnv *env, jobject, jlong handle,
                                               jbyteArray data, jint offset, jint length,
                                               jint timeoutMs) {
    const SRTSOCKET sock = asSock(handle);
    if (sock == SRT_INVALID_SOCK || data == nullptr || length <= 0) return -1;
    if (env->GetArrayLength(data) < offset + length) return -1;

    setInt(sock, SRTO_SNDTIMEO, timeoutMs > 0 ? timeoutMs : 1000);

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return -1;

    SRT_MSGCTRL ctrl;
    srt_msgctrl_init(&ctrl);
    ctrl.msgttl = -1;

    const int sent = srt_sendmsg2(sock, reinterpret_cast<const char *>(bytes) + offset, length, &ctrl);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (sent == SRT_ERROR) {
        LOGE("srt_sendmsg2 falló: %s", srt_getlasterror_str());
        return -1;
    }
    return sent;
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeRecv(JNIEnv *env, jobject, jlong handle,
                                               jbyteArray buffer, jint offset, jint length,
                                               jint timeoutMs) {
    const SRTSOCKET sock = asSock(handle);
    if (sock == SRT_INVALID_SOCK || buffer == nullptr || length <= 0) return -1;
    if (env->GetArrayLength(buffer) < offset + length) return -1;

    setInt(sock, SRTO_RCVTIMEO, timeoutMs > 0 ? timeoutMs : 500);

    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
    if (bytes == nullptr) return -1;

    SRT_MSGCTRL ctrl;
    srt_msgctrl_init(&ctrl);

    const int received =
            srt_recvmsg2(sock, reinterpret_cast<char *>(bytes) + offset, length, &ctrl);
    // Se escribe siempre porque el contenido recibido es la salida del socket.
    env->ReleaseByteArrayElements(buffer, bytes, 0);

    if (received <= 0) {
        if (received < 0) {
            const int code = srt_getlasterror(nullptr);
            if (code != SRT_ETIMEOUT) {
                LOGE("srt_recvmsg2 falló (%d): %s", code, srt_getlasterror_str());
            }
        }
        return -1;
    }
    return received;
}

JNIEXPORT void JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeClose(JNIEnv *, jobject, jlong handle) {
    const SRTSOCKET sock = asSock(handle);
    if (sock == SRT_INVALID_SOCK) return;
    srt_close(sock);
}

JNIEXPORT jstring JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeLastError(JNIEnv *env, jobject) {
    const char *message = srt_getlasterror_str();
    return env->NewStringUTF(message != nullptr ? message : "sin error");
}

JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeSocketState(JNIEnv *, jobject, jlong handle) {
    const SRTSOCKET sock = asSock(handle);
    if (sock == SRT_INVALID_SOCK) return -1;
    return static_cast<jint>(srt_getsockstate(sock));
}

}  // extern "C"
