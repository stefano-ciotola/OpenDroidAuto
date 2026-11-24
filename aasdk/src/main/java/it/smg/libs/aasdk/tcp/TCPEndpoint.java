package it.smg.libs.aasdk.tcp;

import androidx.annotation.Keep;

import it.smg.libs.common.Log;

public class TCPEndpoint {

    private static final String TAG = "TCPEndpoint";
    public static final int ACCEPT_TIMEOUT = -2;

    private final String ipAddress_;
    private int socketFd_;

    public TCPEndpoint(String ipAddress) throws TCPConnectException{
        ipAddress_ = ipAddress;
        socketFd_ = -1;
    }

    public TCPEndpoint(int socketFd) {
        ipAddress_ = null;
        socketFd_ = socketFd;
    }

    public void connect() throws TCPConnectException {
        if (socketFd_ >= 0) {
            int fd = socketFd_;
            socketFd_ = -1;
            if (Log.isInfo()) Log.i(TAG, "Attach accepted socket fd " + fd);
            handle_ = nativeSetupWithFd(fd);
            return;
        }
        if (Log.isInfo()) Log.i(TAG, "Connect to ip " + ipAddress_);
        handle_ = nativeSetup(ipAddress_);
    }

    public void delete(){
        Log.v(TAG, "delete");
        if (handle_ != 0) {
            nativeDelete();
            handle_ = 0;
        } else if (socketFd_ >= 0) {
            closeFd(socketFd_);
            socketFd_ = -1;
        }
    }

    public static int openServerSocket(int port) {
        return nativeOpenServerSocket(port);
    }

    public static int acceptClient(int serverFd, int timeoutMs) {
        return nativeAcceptClient(serverFd, timeoutMs);
    }

    public static boolean isSocketAlive(int fd) {
        return nativeIsSocketAlive(fd);
    }

    public static void shutdownFd(int fd) {
        nativeShutdownFd(fd);
    }

    public static void closeFd(int fd) {
        nativeCloseFd(fd);
    }

    static {
        nativeInit();
    }

    private static native void nativeInit();
    private native long nativeSetup(String ipAddress) throws TCPConnectException;
    private native long nativeSetupWithFd(int socketFd) throws TCPConnectException;
    private native void nativeDelete();
    private static native int nativeOpenServerSocket(int port);
    private static native int nativeAcceptClient(int serverFd, int timeoutMs);
    private static native boolean nativeIsSocketAlive(int fd);
    private static native void nativeShutdownFd(int fd);
    private static native void nativeCloseFd(int fd);

    @Keep
    private long handle_;
}