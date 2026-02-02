package com.media.camera.preview.activity;

import android.os.Bundle;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.media.camera.preview.R;
import com.media.camera.preview.controller.CameraController;
import com.media.camera.preview.gesture.SimpleGestureFilter.SwipeDirection;
import com.media.camera.preview.render.VKVideoRenderer;

import java.util.Locale;

public class VKActivity extends BaseActivity {

    private VKVideoRenderer mVideoRenderer;
    private boolean isPortraitMode = false;
    private float aperture = 5.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vk);

        SurfaceView surfaceView = findViewById(R.id.preview);
        mVideoRenderer = new VKVideoRenderer(getApplicationContext());
        mVideoRenderer.init(surfaceView);

        mCameraController = new CameraController(this, mVideoRenderer);

        setup(surfaceView);
        setupUI();
    }

    private void setupUI() {
        MaterialButton btnPortrait = findViewById(R.id.btn_portrait_toggle);
        LinearLayout layoutAperture = findViewById(R.id.aperture_control_layout);
        Slider sliderAperture = findViewById(R.id.aperture_slider);
        TextView textAperture = findViewById(R.id.aperture_value_text);

        btnPortrait.setOnClickListener(v -> {
            isPortraitMode = !isPortraitMode;
            mVideoRenderer.updatePortraitMode(isPortraitMode);

            if (isPortraitMode) {
                layoutAperture.setVisibility(View.VISIBLE);
                btnPortrait.setBackgroundColor(getResources().getColor(R.color.colorPrimary, getTheme()));
                btnPortrait.setTextColor(getResources().getColor(R.color.colorOnPrimary, getTheme()));
            } else {
                layoutAperture.setVisibility(View.GONE);
                btnPortrait.setBackgroundColor(getResources().getColor(R.color.colorSurface, getTheme()));
                btnPortrait.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
            }
        });

        sliderAperture.addOnChangeListener((slider, value, fromUser) -> {
            aperture = value;
            mVideoRenderer.updateBlurStrength(aperture);
            // Display fake f-stop: value 0 -> f/11.4, value 10 -> f/1.4
            textAperture.setText(String.format(Locale.US, "f/%.1f", (10.0f - value) + 1.4f));
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mCameraController.destroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        mCameraController.startCamera();
    }

    @Override
    public void onPause() {
        mCameraController.stopCamera();
        super.onPause();
    }

    @Override
    public void onSwipe(SwipeDirection direction) {

        switch (direction) {
            case SWIPE_UP:
                showResolutionDialog(mCameraController.getOutputSizes());
                break;
            case SWIPE_LEFT:
                finish();
                break;
            default:
                break;
        }
    }
}
