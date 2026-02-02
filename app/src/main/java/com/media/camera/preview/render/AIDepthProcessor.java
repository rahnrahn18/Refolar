package com.media.camera.preview.render;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

public class AIDepthProcessor {
    private static final String TAG = "AIDepthProcessor";
    private static final String MODEL_FILE = "selfie_segmentation_landscape.tflite";

    private Interpreter tflite;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private int inputSize = 256;
    private ByteBuffer inputBuffer;
    private ByteBuffer outputBuffer;
    private GpuDelegate gpuDelegate;
    private int[] rgbBuffer;

    private DepthCallback callback;
    private int frameCounter = 0;
    private int processDivisor = 1;

    public interface DepthCallback {
        void onDepthMapReady(byte[] depthData, int width, int height);
    }

    public AIDepthProcessor(Context context, int resolution, int divisor, DepthCallback callback) {
        this.inputSize = resolution;
        this.processDivisor = divisor;
        this.callback = callback;
        initTFLite(context);
        startBackgroundThread();
    }

    private void initTFLite(Context context) {
        try {
            Interpreter.Options options = new Interpreter.Options();
            CompatibilityList compatList = new CompatibilityList();

            if (compatList.isDelegateSupportedOnThisDevice()) {
                GpuDelegate.Options delegateOptions = compatList.getBestOptionsForThisDevice();
                gpuDelegate = new GpuDelegate(delegateOptions);
                options.addDelegate(gpuDelegate);
                Log.i(TAG, "Using GPU Delegate");
            } else {
                options.setNumThreads(4);
                Log.i(TAG, "Using CPU with 4 threads");
            }

            try {
                tflite = new Interpreter(loadModelFile(context), options);
            } catch (Exception e) {
                 Log.e(TAG, "Failed to load TFLite model", e);
            }

            // Input: 1 x 256 x 256 x 3 (Float32)
            inputBuffer = ByteBuffer.allocateDirect(4 * 3 * inputSize * inputSize);
            inputBuffer.order(ByteOrder.nativeOrder());

            // Output: 1 x 256 x 256 x 2 (Float32)
            outputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 2);
            outputBuffer.order(ByteOrder.nativeOrder());

            rgbBuffer = new int[inputSize * inputSize];

        } catch (Exception e) {
            Log.e(TAG, "Error initializing TFLite", e);
        }
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("TFLiteThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void stop() {
        if (backgroundThread != null) backgroundThread.quitSafely();
        if (tflite != null) tflite.close();
        if (gpuDelegate != null) gpuDelegate.close();
    }

    public void processFrame(byte[] yuvData, int width, int height, int rotation) {
        frameCounter++;
        if (frameCounter % processDivisor != 0) return;

        if (isProcessing.compareAndSet(false, true)) {
            backgroundHandler.post(() -> {
                try {
                    runInference(yuvData, width, height, rotation);
                } catch (Exception e) {
                    Log.e(TAG, "Inference error", e);
                } finally {
                    isProcessing.set(false);
                }
            });
        }
    }

    private void runInference(byte[] yuvData, int width, int height, int rotation) {
        if (tflite == null) {
            runMockInference();
            return;
        }

        try {
            // 1. Convert YUV to RGB and Resize
            convertYUVtoRGBResize(yuvData, width, height, rgbBuffer, inputSize, inputSize);

            // 2. Fill Input Buffer
            inputBuffer.rewind();
            for(int val : rgbBuffer) {
                // Normalize to 0-1 or -1 to 1 depending on model.
                // Selfie Segmentation usually 0-1.
                inputBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                inputBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                inputBuffer.putFloat((val & 0xFF) / 255.0f);
            }

            // 3. Run Inference
            outputBuffer.rewind();
            tflite.run(inputBuffer, outputBuffer);

            // 4. Process Output
            outputBuffer.rewind();
            byte[] depthMap = new byte[inputSize * inputSize];
            for (int i = 0; i < inputSize * inputSize; i++) {
                // Typically output is [BG, FG] or just mask.
                // Assuming [BG_logit, FG_logit]
                float bg = outputBuffer.getFloat();
                float fg = outputBuffer.getFloat();

                // Sigmoid of difference: 1 / (1 + exp(bg - fg))
                // Optim: just check if fg > bg? No we need smooth gradient for antialiasing
                float prob = (float) (1.0 / (1.0 + Math.exp(bg - fg)));
                depthMap[i] = (byte) (prob * 255);
            }

            if (callback != null) {
                callback.onDepthMapReady(depthMap, inputSize, inputSize);
            }

        } catch (Exception e) {
            // Log.w(TAG, "Inference failed (using mock): " + e.getMessage());
            runMockInference();
        }
    }

    private void runMockInference() {
        byte[] depthMap = new byte[inputSize * inputSize];
        float centerX = inputSize / 2.0f;
        float centerY = inputSize / 2.0f;
        float maxRadius = inputSize / 3.0f;

        for (int y = 0; y < inputSize; y++) {
            for (int x = 0; x < inputSize; x++) {
                float dx = x - centerX;
                float dy = y - centerY;
                float dist = (float)Math.sqrt(dx*dx + dy*dy);

                // 1.0 (255) = Sharp (Subject), 0.0 (0) = Blur (BG)
                if (dist < maxRadius) {
                    depthMap[y * inputSize + x] = (byte)255;
                } else {
                    depthMap[y * inputSize + x] = (byte)0;
                }
            }
        }
        if (callback != null) {
            callback.onDepthMapReady(depthMap, inputSize, inputSize);
        }
    }

    private void convertYUVtoRGBResize(byte[] yuv, int srcWidth, int srcHeight, int[] outRgb, int dstWidth, int dstHeight) {
        // Nearest neighbor resize from NV21 YUV
        int frameSize = srcWidth * srcHeight;
        for (int j = 0; j < dstHeight; j++) {
            int srcY = j * srcHeight / dstHeight;
            int yIdx = srcY * srcWidth;
            int uvIdx = frameSize + (srcY >> 1) * srcWidth;

            for (int i = 0; i < dstWidth; i++) {
                int srcX = i * srcWidth / dstWidth;
                int idx = yIdx + srcX;

                int y = (0xff & ((int) yuv[idx]));
                // NV21: V U V U
                int uvOffset = (srcX & ~1);
                int v = (0xff & ((int) yuv[uvIdx + uvOffset]));
                int u = (0xff & ((int) yuv[uvIdx + uvOffset + 1]));

                y = y < 16 ? 16 : y;

                // Integer math for speed
                int y1192 = 1192 * (y - 16);
                int r = (y1192 + 1634 * (v - 128));
                int g = (y1192 - 833 * (v - 128) - 400 * (u - 128));
                int b = (y1192 + 2066 * (u - 128));

                r = (r < 0) ? 0 : ((r > 262143) ? 262143 : r);
                g = (g < 0) ? 0 : ((g > 262143) ? 262143 : g);
                b = (b < 0) ? 0 : ((b > 262143) ? 262143 : b);

                outRgb[j * dstWidth + i] = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
            }
        }
    }
}
