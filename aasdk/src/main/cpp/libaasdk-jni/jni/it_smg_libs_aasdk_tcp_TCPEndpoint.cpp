#include <tcp/TCPEndpoint.hpp>
#include <Log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include "it_smg_libs_aasdk_tcp_TCPEndpoint.h"
#include "it_smg_libs_aasdk_Runtime.h"

using namespace aasdk::tcp;

jfieldID JTCPEndpoint::handleId = nullptr;

JTCPEndpoint::JTCPEndpoint(JNIEnv *env, jobject jtcpendpoint, std::string ipAddress)
        : JNIBase(env, jtcpendpoint, "JTCPEndpoint"){
    ITCPEndpoint::SocketPointer socket = connect(env, JRuntime::ioService(), std::move(ipAddress));
    if (socket && !env->ExceptionCheck()) {
        tcpEndpoint_ = std::make_shared<TCPEndpoint>(tcpWrapper_, socket);
    }
}

JTCPEndpoint::JTCPEndpoint(JNIEnv *env, jobject jtcpendpoint, int socketFd)
        : JNIBase(env, jtcpendpoint, "JTCPEndpoint"){
    ITCPEndpoint::SocketPointer socket = assignSocketFd(env, JRuntime::ioService(), socketFd);
    if (socket && !env->ExceptionCheck()) {
        tcpEndpoint_ = std::make_shared<TCPEndpoint>(tcpWrapper_, socket);
    }
}

ITCPEndpoint::SocketPointer JTCPEndpoint::assignSocketFd(JNIEnv* env, aasdk::io::ioService& ioService, int socketFd) {
    if (socketFd < 0) {
        throwConnectException(env, const_cast<char*>("Invalid accepted socket descriptor"));
        return nullptr;
    }

    auto socket = std::make_shared<boost::asio::ip::tcp::socket>(ioService);
    const boost::system::error_code ec = tcpWrapper_.assignFd(*socket, socketFd);
    if (ec) {
        std::string msg = ec.message();
        throwConnectException(env, const_cast<char*>(msg.c_str()));
        return nullptr;
    }
    return socket;
}

JTCPEndpoint::~JTCPEndpoint(){
    if (Log::isVerbose()) Log_v("destructor");
    tcpEndpoint_.reset();
}

JTCPEndpoint::Pointer JTCPEndpoint::getJTCPEndpoint(JNIEnv *env, jobject jtcpendpoint) {
    return (JTCPEndpoint::Pointer)env->GetLongField(jtcpendpoint, JTCPEndpoint::handleId);;
}

ITCPEndpoint::SocketPointer JTCPEndpoint::connect(JNIEnv* env, aasdk::io::ioService& ioService, std::string ipAddress){
    auto socket = std::make_shared<boost::asio::ip::tcp::socket>(ioService);

    try {
        boost::system::error_code ec = tcpWrapper_.connect(*socket, ipAddress, 5277);

        if (!ec){
            if(Log::isInfo()) Log_i("Tcp connection established to ipAddress: %s", ipAddress.c_str());
            return socket;
        } else {
            char msg [512] = {0};
            snprintf (msg, sizeof (msg), "Error in tcp connection, ip: %s -> error: %s", ipAddress.c_str(), ec.message().c_str());
            Log_e(msg);
            socket.reset();
            throwConnectException(env, msg);
            return nullptr;
        }

    } catch(const boost::system::system_error& se) {
        char msg [512] = {0};
        snprintf (msg, sizeof (msg), "Error in tcp connection, ip: %s -> error: %s", ipAddress.c_str(), se.what());
        Log_e(msg);
        socket.reset();
        throwConnectException(env, msg);
        return nullptr;
    }
}

void JTCPEndpoint::throwConnectException(JNIEnv *env, char *message ){
    jclass exClass = env->FindClass("it/smg/libs/aasdk/tcp/TCPConnectException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, message);
        env->DeleteLocalRef(exClass);
    }
}

ITCPEndpoint::Pointer JTCPEndpoint::getTCPEnpoint() {
    return tcpEndpoint_;
}

/////////////////////////////////////////////////////////////////////////////////////////////////////

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeInit(JNIEnv *env, jclass clazz) {
    jclass _class = env->FindClass("it/smg/libs/aasdk/tcp/TCPEndpoint");
    JTCPEndpoint::handleId = env->GetFieldID(_class, "handle_", "J");

    env->DeleteLocalRef(_class);
    _class = nullptr;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeSetup(JNIEnv *env, jobject thiz, jstring jipaddress) {
    if (jipaddress == nullptr) {
        jclass exClass = env->FindClass("it/smg/libs/aasdk/tcp/TCPConnectException");
        if (exClass != nullptr) {
            env->ThrowNew(exClass, "Null IP address");
            env->DeleteLocalRef(exClass);
        }
        return 0;
    }
    const char* ip_ = env->GetStringUTFChars(jipaddress, nullptr);
    if (ip_ == nullptr) {
        return 0;
    }
    std::string ipAddress(ip_);
    env->ReleaseStringUTFChars(jipaddress, ip_);

    auto jTCPEndpoint = new JTCPEndpoint(env, thiz, std::move(ipAddress));
    if (env->ExceptionCheck() || !jTCPEndpoint->getTCPEnpoint()) {
        delete jTCPEndpoint;
        return 0;
    }
    return (jlong)((size_t)jTCPEndpoint);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeSetupWithFd(JNIEnv *env, jobject thiz, jint jsocketFd) {
    auto jTCPEndpoint = new JTCPEndpoint(env, thiz, static_cast<int>(jsocketFd));
    if (env->ExceptionCheck() || !jTCPEndpoint->getTCPEnpoint()) {
        delete jTCPEndpoint;
        return 0;
    }
    return (jlong)((size_t)jTCPEndpoint);
}

extern "C"
JNIEXPORT jint JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeOpenServerSocket(JNIEnv *env, jclass clazz, jint port) {
    (void)env;
    (void)clazz;
    if (port <= 0 || port > 65535) {
        return -1;
    }

    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }

    int reuse = 1;
    ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags < 0 || ::fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        ::close(fd);
        return -1;
    }

    struct sockaddr_in sa{};
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_ANY);
    sa.sin_port = htons(static_cast<uint16_t>(port));

    if (::bind(fd, reinterpret_cast<struct sockaddr*>(&sa), sizeof(sa)) < 0 || ::listen(fd, 4) < 0) {
        ::close(fd);
        return -1;
    }

    return static_cast<jint>(fd);
}

extern "C"
JNIEXPORT jint JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeAcceptClient(JNIEnv *env, jclass clazz, jint serverFd, jint timeoutMs) {
    (void)env;
    (void)clazz;
    if (serverFd < 0) {
        return -1;
    }

    struct pollfd pfd{};
    pfd.fd = static_cast<int>(serverFd);
    pfd.events = POLLIN;
    const int pr = ::poll(&pfd, 1, static_cast<int>(timeoutMs));
    if (pr < 0) {
        return (errno == EINTR) ? -2 : -1;
    }
    if (pr == 0) {
        return -2;
    }
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
        return -1;
    }

    const int clientFd = ::accept(static_cast<int>(serverFd), nullptr, nullptr);
    if (clientFd < 0) {
        return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? -2 : -1;
    }

    const int cflags = ::fcntl(clientFd, F_GETFL, 0);
    if (cflags >= 0 && (cflags & O_NONBLOCK)) {
        if (::fcntl(clientFd, F_SETFL, cflags & ~O_NONBLOCK) < 0) {
            ::close(clientFd);
            return -1;
        }
    }

    return static_cast<jint>(clientFd);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeIsSocketAlive(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    if (fd < 0) {
        return JNI_FALSE;
    }
    struct pollfd pfd{};
    pfd.fd = static_cast<int>(fd);
    pfd.events = POLLIN;
    const int pr = ::poll(&pfd, 1, 0);
    if (pr < 0) {
        return JNI_FALSE;
    }
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
        return JNI_FALSE;
    }
    if (pfd.revents & POLLIN) {
        char peekByte = 0;
        const ssize_t n = ::recv(static_cast<int>(fd), &peekByte, 1, MSG_PEEK | MSG_DONTWAIT);
        if (n == 0) {
            return JNI_FALSE;
        }
        if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeShutdownFd(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    if (fd >= 0) {
        ::shutdown(static_cast<int>(fd), SHUT_RDWR);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeCloseFd(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    if (fd >= 0) {
        ::shutdown(static_cast<int>(fd), SHUT_RDWR);
        ::close(static_cast<int>(fd));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_it_smg_libs_aasdk_tcp_TCPEndpoint_nativeDelete(JNIEnv *env, jobject thiz) {
    JTCPEndpoint::Pointer jTCPEndpoint = JTCPEndpoint::getJTCPEndpoint(env, thiz);
    delete jTCPEndpoint;
}