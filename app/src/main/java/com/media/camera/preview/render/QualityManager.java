package com.media.camera.preview.render;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

public class QualityManager {
    private static final String TAG = "QualityManager";

    public static class QualityConfig {
        public int aiResolution;
        public int sampleCount;
        public int aiFpsDivisor; // 1 = every frame, 2 = every 2nd frame, etc.

        public QualityConfig(int aiResolution, int sampleCount, int aiFpsDivisor) {
            this.aiResolution = aiResolution;
            this.sampleCount = sampleCount;
            this.aiFpsDivisor = aiFpsDivisor;
        }
    }

    public static QualityConfig getQualityConfig(Context context) {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }

        long totalMemBytes = memoryInfo.totalMem;
        long totalMemGB = totalMemBytes / (1024 * 1024 * 1024);

        int cores = Runtime.getRuntime().availableProcessors();

        Log.i(TAG, "Device Info: RAM=" + totalMemGB + "GB, Cores=" + cores + ", Model=" + Build.MODEL);

        // Heuristic:
        // High End: > 7GB RAM (approx > 7GB technically covering 8GB devices)
        if (totalMemGB > 7) {
             Log.i(TAG, "Tier: HIGH");
             return new QualityConfig(512, 32, 1);
        } else {
             Log.i(TAG, "Tier: MID/LOW");
             // Helio G99 / Mid range
             return new QualityConfig(256, 16, 2);
        }
    }
}
