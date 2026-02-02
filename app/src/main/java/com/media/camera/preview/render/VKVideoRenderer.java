package com.media.camera.preview.render;

import android.content.Context;
import androidx.annotation.NonNull;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class VKVideoRenderer extends VideoRenderer implements SurfaceHolder.Callback {

    private final Context mContext;
    private AIDepthProcessor mDepthProcessor;
    private QualityManager.QualityConfig mQualityConfig;

    public VKVideoRenderer(Context context) {
        mContext = context;
        mQualityConfig = QualityManager.getQualityConfig(context);

        mDepthProcessor = new AIDepthProcessor(context, mQualityConfig.aiResolution, mQualityConfig.aiFpsDivisor, (depthData, width, height) -> {
            updateDepth(depthData, width, height);
        });
    }

    public void init(SurfaceView surface) {
        surface.getHolder().addCallback(this);
    }

    public void updatePortraitMode(boolean enabled) {
        setPortraitMode(enabled);
    }

    public void updateBlurStrength(float strength) {
        setBlurStrength(strength);
    }

    public void updateFilter(int filterId) {
        setFilter(filterId);
    }

    public void updateDepth(byte[] data, int width, int height) {
        updateDepthData(data, width, height);
    }

    public void updateQuality(int samples) {
        setQualityParams(samples);
    }

    @Override
    public void drawVideoFrame(byte[] data, int width, int height, int rotation, boolean mirror) {
        draw(data, width, height, rotation, mirror);
        if (mDepthProcessor != null) {
            mDepthProcessor.processFrame(data, width, height, rotation);
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        create(Type.VK_YUV420.getValue());
        if (mQualityConfig != null) {
            updateQuality(mQualityConfig.sampleCount);
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        init(holder.getSurface(), mContext.getAssets(), width, height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (mDepthProcessor != null) {
            mDepthProcessor.stop();
        }
    }
}
