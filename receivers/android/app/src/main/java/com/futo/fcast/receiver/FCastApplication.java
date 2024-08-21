package com.futo.fcast.receiver;

import android.app.Application;
import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerControlView;

import org.chromium.net.CronetEngine;

import java.lang.reflect.Constructor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public final class FCastApplication extends Application {
    private static CronetEngine cronetEngine;
    private static ExecutorService cronetCallbackExecutorService;

    public static CronetEngine getCronetEngine() {
        return cronetEngine;
    }

    public static ExecutorService getCronetCallbackExecutorService() {
        return cronetCallbackExecutorService;
    }

    @OptIn(markerClass = UnstableApi.class)
    private static void ExoPlayerSpeedSelectorExtender() throws ClassNotFoundException, NoSuchMethodException {
        final float[] PLAYBACK_SPEEDS_ADDITIONAL = {2.25f};
        final String[] PLAYBACK_SPEED_TEXTS_ADDITIONAL = {"2.25x"};

        final Class<?> clsPlaybackSpeedAdapter = Class.forName("androidx.media3.ui.PlayerControlView$PlaybackSpeedAdapter");
        final Constructor<?> m = clsPlaybackSpeedAdapter.getDeclaredConstructor(PlayerControlView.class, String[].class, float[].class);

        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                final String[] playbackSpeedTexts = (String[]) param.args[1];
                final float[] playbackSpeeds = (float[]) param.args[2];

               /*if (playbackSpeedTexts == null || playbackSpeeds == null)
                    return;*/

                final String[] newPlaybackSpeedTexts = new String[playbackSpeedTexts.length + PLAYBACK_SPEED_TEXTS_ADDITIONAL.length];
                final float[] newPlaybackSpeeds = new float[playbackSpeeds.length + PLAYBACK_SPEEDS_ADDITIONAL.length];

                System.arraycopy(playbackSpeeds, 0, newPlaybackSpeeds, 0, playbackSpeeds.length);
                System.arraycopy(PLAYBACK_SPEEDS_ADDITIONAL, 0, newPlaybackSpeeds, playbackSpeeds.length, PLAYBACK_SPEEDS_ADDITIONAL.length);

                System.arraycopy(playbackSpeedTexts, 0, newPlaybackSpeedTexts, 0, playbackSpeedTexts.length);
                System.arraycopy(PLAYBACK_SPEED_TEXTS_ADDITIONAL, 0, newPlaybackSpeedTexts, playbackSpeedTexts.length, PLAYBACK_SPEED_TEXTS_ADDITIONAL.length);

                param.args[1] = newPlaybackSpeedTexts;
                param.args[2] = newPlaybackSpeeds;
            }
        });
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            ExoPlayerSpeedSelectorExtender();
        } catch (final Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        cronetEngine = new CronetEngine.Builder(this).build();
        cronetCallbackExecutorService = Executors.newFixedThreadPool(Math.min(Runtime.getRuntime().availableProcessors(), 4));
    }
}
