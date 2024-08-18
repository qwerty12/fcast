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
    static final int IPTOS_LOWDELAY = 0x10;
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
    static void enableTcpQuickAck(final Socket socket) {
        try {
            Os.setsockoptInt((FileDescriptor) socketGetFileDescriptor.invoke(socket), OsConstants.IPPROTO_TCP, TCP_QUICKACK, 1);
        } catch (final Throwable ignored) {}
    }

}
