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
            boolean gpuInitialized = false;

            try {
                if (compatList.isDelegateSupportedOnThisDevice()) {
                    GpuDelegate.Options delegateOptions = compatList.getBestOptionsForThisDevice();
                    gpuDelegate = new GpuDelegate(delegateOptions);
                    options.addDelegate(gpuDelegate);
                    Log.i(TAG, "Using GPU Delegate");
                    gpuInitialized = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "GPU Delegate failed to initialize, falling back to CPU", e);
                if (gpuDelegate != null) {
                    gpuDelegate.close();
                    gpuDelegate = null;
                }
            }

            if (!gpuInitialized) {
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
                    isProcessing.set(false);
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
            // 1. Convert YUV to RGB and Resize with Rotation
            // If rotation is 90 or 270, we are effectively swapping width/height for the sampling logic
            convertYUVtoRGBResize(yuvData, width, height, rgbBuffer, inputSize, inputSize, rotation);

            // 2. Fill Input Buffer
            inputBuffer.rewind();
            for(int val : rgbBuffer) {
                // Normalize to 0-1
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
                // Assuming [BG_logit, FG_logit]
                float bg = outputBuffer.getFloat();
                float fg = outputBuffer.getFloat();
                float prob = (float) (1.0 / (1.0 + Math.exp(bg - fg)));
                depthMap[i] = (byte) (prob * 255);
            }

            // 5. Rotate Mask Back to Original Orientation
            // We rotated INPUT by 'rotation' to be upright.
            // So we must rotate OUTPUT by '-rotation' (or 360-rotation) to match original frame.
            int backRotation = (360 - rotation) % 360;
            byte[] finalMask = rotateMask(depthMap, inputSize, inputSize, backRotation);

            if (callback != null) {
                callback.onDepthMapReady(finalMask, inputSize, inputSize);
            }

        } catch (Exception e) {
            Log.e(TAG, "Inference failed (using mock): " + e.getMessage());
            e.printStackTrace();
            runMockInference();
        }
    }

    private byte[] rotateMask(byte[] input, int width, int height, int rotation) {
        if (rotation == 0) return input;

        byte[] output = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int newX = x, newY = y;
                if (rotation == 90) {
                    newX = height - 1 - y;
                    newY = x;
                } else if (rotation == 180) {
                    newX = width - 1 - x;
                    newY = height - 1 - y;
                } else if (rotation == 270) {
                    newX = y;
                    newY = width - 1 - x;
                }
                output[newY * width + newX] = input[y * width + x];
            }
        }
        return output;
    }

    private void runMockInference() {
        // Fallback: Return a completely white mask (Subject) to avoid static vignette artifacts.
        // If inference fails, it's better to have no blur than a misleading one.
        byte[] depthMap = new byte[inputSize * inputSize];
        for (int i = 0; i < inputSize * inputSize; i++) {
            depthMap[i] = (byte) 255; // All Sharp
        }
        if (callback != null) {
            callback.onDepthMapReady(depthMap, inputSize, inputSize);
        }
    }

    private void convertYUVtoRGBResize(byte[] yuv, int srcWidth, int srcHeight, int[] outRgb, int dstWidth, int dstHeight, int rotation) {
        int frameSize = srcWidth * srcHeight;

        for (int j = 0; j < dstHeight; j++) {
            for (int i = 0; i < dstWidth; i++) {
                // Calculate sampling coordinates based on rotation
                int sampleX = 0, sampleY = 0;

                if (rotation == 0) {
                    sampleX = i * srcWidth / dstWidth;
                    sampleY = j * srcHeight / dstHeight;
                } else if (rotation == 90) {
                    // Rotated 90 CW: Destination (i, j) maps to Source (srcWidth - 1 - Y, X)
                    // Wait, logic check:
                    // Input: WxH. We rotate 90. Output: HxW.
                    // If we want a 256x256 crop from center, or resize whole thing?
                    // We resize the whole thing.
                    // dstWidth is implicitly related to srcHeight now.

                    // i (x) in dst corresponds to y in src.
                    // j (y) in dst corresponds to x in src (inverted).
                    sampleX = j * srcWidth / dstHeight;
                    sampleY = (dstWidth - 1 - i) * srcHeight / dstWidth;
                } else if (rotation == 180) {
                     sampleX = (dstWidth - 1 - i) * srcWidth / dstWidth;
                     sampleY = (dstHeight - 1 - j) * srcHeight / dstHeight;
                } else if (rotation == 270) {
                     // 270 CW (90 CCW)
                     sampleX = (dstHeight - 1 - j) * srcWidth / dstHeight;
                     sampleY = i * srcHeight / dstWidth;
                }

                // Clamp
                if (sampleX < 0) sampleX = 0; if (sampleX >= srcWidth) sampleX = srcWidth - 1;
                if (sampleY < 0) sampleY = 0; if (sampleY >= srcHeight) sampleY = srcHeight - 1;

                int yIdx = sampleY * srcWidth + sampleX;
                int uvIdx = frameSize + (sampleY >> 1) * srcWidth + (sampleX & ~1);

                int y = (0xff & ((int) yuv[yIdx]));
                int v = (0xff & ((int) yuv[uvIdx]));
                int u = (0xff & ((int) yuv[uvIdx + 1]));

                y = y < 16 ? 16 : y;

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
