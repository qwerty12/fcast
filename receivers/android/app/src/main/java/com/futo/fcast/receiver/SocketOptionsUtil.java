package com.futo.fcast.receiver;

import android.annotation.SuppressLint;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.net.Socket;

@SuppressLint("DiscouragedPrivateApi")
class SocketOptionsUtil {
    private static final int IPTOS_LOWDELAY = 0x10;
    private static final int TCP_QUICKACK = 12;
    private static final Method socketGetFileDescriptor;

    static {
        Method tmp = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                tmp = Socket.class.getDeclaredMethod("getFileDescriptor$");
            } catch (final Throwable ignored) {}
        }
        socketGetFileDescriptor = tmp;
    }

    @SuppressLint("NewApi")
    private static void enableTcpQuickAck(final Socket socket) throws Throwable {
        Os.setsockoptInt((FileDescriptor) socketGetFileDescriptor.invoke(socket), OsConstants.IPPROTO_TCP, TCP_QUICKACK, 1);
    }

    static void setLowDelay(final Socket socket) {
        if (socket == null)
            return;

        try {
            socket.setTcpNoDelay(true);
        } catch (final Throwable ignored) {}
        try {
            enableTcpQuickAck(socket);
        } catch (final Throwable ignored) {}
        try {
            socket.setKeepAlive(true);
        } catch (final Throwable ignored) {}
        try {
            socket.setTrafficClass(IPTOS_LOWDELAY);
        } catch (final Throwable ignored) {}
    }
}
