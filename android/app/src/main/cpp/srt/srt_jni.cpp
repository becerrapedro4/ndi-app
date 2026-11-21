// Puente mínimo con libsrt: lo justo para reproducir un srt:// en ExoPlayer y
// para empujar el MPEG-TS que arma la app. libsrt se compila sin cifrado, así
// que el parámetro passphrase de la URL se acepta pero se ignora.
#include <jni.h>
#include <android/log.h>
#include <srt/srt.h>

#include <arpa/inet.h>
#include <cstdint>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#define TAG "SrtJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::once_flag gInit;
std::string gLastError = "sin error";
std::atomic<bool> gCancelled{false};

void setError(const std::string &message) {
    gLastError = message;
}

void ensureStarted() {
    std::call_once(gInit, []() {
        if (srt_startup() == SRT_ERROR) {
            setError(srt_getlasterror_str());
        }
    });
}

struct SrtUrl {
    std::string host = "127.0.0.1";
    int port = 9000;
    std::string mode = "caller";
    int latency = 200;
    std::string streamId;
};

bool parseUrl(const std::string &raw, SrtUrl &out) {
    std::string rest = raw;
    const std::string scheme = "srt://";
    auto schemePos = rest.find(scheme);
    if (schemePos == std::string::npos) {
        setError("La URL no empieza con srt://");
        return false;
    }
    rest = rest.substr(schemePos + scheme.size());

    std::string query;
    auto queryPos = rest.find('?');
    if (queryPos != std::string::npos) {
        query = rest.substr(queryPos + 1);
        rest = rest.substr(0, queryPos);
    }
    auto slash = rest.find('/');
    std::string hostPort = slash == std::string::npos ? rest : rest.substr(0, slash);

    auto colon = hostPort.rfind(':');
    if (colon != std::string::npos) {
        out.host = hostPort.substr(0, colon);
        out.port = std::atoi(hostPort.c_str() + colon + 1);
    } else {
        out.host = hostPort;
    }
    if (out.host.empty()) {
        setError("La URL no tiene host");
        return false;
    }
    if (out.port <= 0) out.port = 9000;

    size_t start = 0;
    while (start < query.size()) {
        auto amp = query.find('&', start);
        std::string kv = query.substr(start, amp == std::string::npos ? std::string::npos : amp - start);
        auto eq = kv.find('=');
        if (eq != std::string::npos) {
            const std::string key = kv.substr(0, eq);
            const std::string value = kv.substr(eq + 1);
            if (key == "mode") out.mode = value;
            else if (key == "latency") out.latency = std::atoi(value.c_str());
            else if (key == "streamid") out.streamId = value;
        }
        if (amp == std::string::npos) break;
        start = amp + 1;
    }
    if (out.latency < 20) out.latency = 20;
    return true;
}

bool resolve(const std::string &host, int port, sockaddr_storage &storage, socklen_t &length) {
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo *result = nullptr;
    const std::string service = std::to_string(port);
    if (getaddrinfo(host.c_str(), service.c_str(), &hints, &result) != 0 || result == nullptr) {
        setError("No se pudo resolver " + host);
        return false;
    }
    std::memcpy(&storage, result->ai_addr, result->ai_addrlen);
    length = static_cast<socklen_t>(result->ai_addrlen);
    freeaddrinfo(result);
    return true;
}

SRTSOCKET openSocket(const std::string &url, int timeoutMs) {
    ensureStarted();
    SrtUrl parsed;
    if (!parseUrl(url, parsed)) return SRT_INVALID_SOCK;

    SRT_TRANSTYPE type = SRTT_LIVE;
    const int latency = parsed.latency;
    const bool listener = parsed.mode == "listener";

    SRTSOCKET socket = srt_create_socket();
    if (socket == SRT_INVALID_SOCK) {
        setError(srt_getlasterror_str());
        return socket;
    }

    srt_setsockflag(socket, SRTO_TRANSTYPE, &type, sizeof(type));
    srt_setsockflag(socket, SRTO_RCVLATENCY, &latency, sizeof(latency));
    srt_setsockflag(socket, SRTO_PEERLATENCY, &latency, sizeof(latency));
    if (!parsed.streamId.empty()) {
        srt_setsockflag(socket, SRTO_STREAMID, parsed.streamId.c_str(),
                        static_cast<int>(parsed.streamId.size()));
    }

    if (listener) {
        // En modo escucha se atiende en cualquier interfaz: el host de la URL es
        // la dirección que usa el emisor (por ejemplo vMix), no una dirección
        // local que haya que reservar. Si el equipo cambia de IP, igual funciona.
        sockaddr_in any{};
        any.sin_family = AF_INET;
        any.sin_addr.s_addr = htonl(INADDR_ANY);
        any.sin_port = htons(static_cast<uint16_t>(parsed.port));
        if (srt_bind(socket, reinterpret_cast<sockaddr *>(&any), sizeof(any)) == SRT_ERROR) {
            setError(srt_getlasterror_str());
            srt_close(socket);
            return SRT_INVALID_SOCK;
        }
        if (srt_listen(socket, 2) == SRT_ERROR) {
            setError(srt_getlasterror_str());
            srt_close(socket);
            return SRT_INVALID_SOCK;
        }
        // Se espera al emisor en tramos cortos: no hay que abandonar a los pocos
        // segundos porque el llamante puede tardar en conectar.
        const int poll = 250;
        srt_setsockflag(socket, SRTO_RCVTIMEO, &poll, sizeof(poll));
        while (true) {
            if (gCancelled.load()) {
                setError("Recepción cancelada");
                srt_close(socket);
                return SRT_INVALID_SOCK;
            }
            const SRTSOCKET accepted = srt_accept(socket, nullptr, nullptr);
            if (accepted != SRT_INVALID_SOCK) {
                srt_close(socket);
                srt_setsockflag(accepted, SRTO_RCVTIMEO, &poll, sizeof(poll));
                return accepted;
            }
            const std::string message = srt_getlasterror_str();
            const int reason = srt_getlasterror(nullptr);
            // Agotar la espera sólo significa que el emisor todavía no llegó.
            if (reason == SRT_ETIMEOUT || reason == SRT_EASYNCRCV) continue;
            setError(message);
            srt_close(socket);
            return SRT_INVALID_SOCK;
        }
    }

    sockaddr_storage storage{};
    socklen_t length = 0;
    if (!resolve(parsed.host, parsed.port, storage, length)) {
        srt_close(socket);
        return SRT_INVALID_SOCK;
    }

    const int connectTimeout = timeoutMs > 0 ? timeoutMs : 6000;
    srt_setsockflag(socket, SRTO_CONNTIMEO, &connectTimeout, sizeof(connectTimeout));
    if (srt_connect(socket, reinterpret_cast<sockaddr *>(&storage), length) == SRT_ERROR) {
        setError(srt_getlasterror_str());
        srt_close(socket);
        return SRT_INVALID_SOCK;
    }
    return socket;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeConnect(JNIEnv *env, jobject, jstring url, jint timeoutMs) {
    if (url == nullptr) return 0;
    const char *chars = env->GetStringUTFChars(url, nullptr);
    std::string address(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(url, chars);
    gCancelled.store(false);
    const SRTSOCKET socket = openSocket(address, timeoutMs);
    if (socket == SRT_INVALID_SOCK) {
        LOGE("SRT no pudo conectar a %s: %s", address.c_str(), gLastError.c_str());
        return 0;
    }
    return static_cast<jlong>(socket);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeRead(JNIEnv *env, jobject, jlong handle,
                                                jbyteArray target, jint offset, jint length) {
    if (handle == 0 || target == nullptr || length <= 0) return -1;
    auto socket = static_cast<SRTSOCKET>(handle);
    jbyte *bytes = env->GetByteArrayElements(target, nullptr);
    if (bytes == nullptr) {
        setError("Sin memoria para el búfer de lectura");
        return -1;
    }
    if (gCancelled.load()) {
        setError("Recepción cancelada");
        env->ReleaseByteArrayElements(target, bytes, JNI_ABORT);
        return -1;
    }
    const int read = srt_recvmsg(socket, reinterpret_cast<char *>(bytes) + offset, length);
    if (read < 0) {
        const std::string message = srt_getlasterror_str();
        const int reason = srt_getlasterror(nullptr);
        env->ReleaseByteArrayElements(target, bytes, JNI_ABORT);
        if (reason == SRT_EASYNCRCV || reason == SRT_ETIMEOUT) {
            if (gCancelled.load()) {
                setError("Recepción cancelada");
                return -1;
            }
            return 0;
        }
        setError(message);
        return -1;
    }
    env->ReleaseByteArrayElements(target, bytes, 0);
    return read;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeWrite(JNIEnv *env, jobject, jlong handle,
                                                 jbyteArray source, jint offset, jint length) {
    if (handle == 0 || source == nullptr || length <= 0) return -1;
    auto socket = static_cast<SRTSOCKET>(handle);
    jbyte *bytes = env->GetByteArrayElements(source, nullptr);
    if (bytes == nullptr) {
        setError("Sin memoria para el búfer de envío");
        return -1;
    }
    int written = 0;
    while (written < length) {
        // SRT rechaza mensajes mayores que su MTU: se parte en trozos de 1316.
        int chunk = length - written;
        if (chunk > 1316) chunk = 1316;
        const int sent = srt_sendmsg(socket, reinterpret_cast<char *>(bytes) + offset + written,
                                     chunk, -1, 0);
        if (sent < 0) {
            setError(srt_getlasterror_str());
            written = -1;
            break;
        }
        written += sent;
    }
    env->ReleaseByteArrayElements(source, bytes, JNI_ABORT);
    return written;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeClose(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    srt_close(static_cast<SRTSOCKET>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeCancel(JNIEnv *, jobject) {
    gCancelled.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ndiapp_camera_net_SrtNative_nativeLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(gLastError.c_str());
}
