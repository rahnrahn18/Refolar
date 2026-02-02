package com.media.camera.preview.activity;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.media.camera.preview.R;
import com.media.camera.preview.adapter.FilterAdapter;
import com.media.camera.preview.controller.CameraController;
import com.media.camera.preview.gesture.SimpleGestureFilter.SwipeDirection;
import com.media.camera.preview.render.VKVideoRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VKActivity extends BaseActivity implements ActivityCompat.OnRequestPermissionsResultCallback {

    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";
    private static final String[] CAMERA_PERMISSIONS = {
            Manifest.permission.CAMERA
    };

    private VKVideoRenderer mVideoRenderer;
    private boolean isPortraitMode = false;
    private boolean isFilterMode = false;
    private float aperture = 5.0f;
    private ErrorDialog mErrorDialog;

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
        // Portrait Controls
        MaterialButton btnPortrait = findViewById(R.id.btn_portrait_toggle);
        MaterialButton btnFilter = findViewById(R.id.btn_filter_toggle);
        LinearLayout layoutAperture = findViewById(R.id.aperture_control_layout);
        Slider sliderAperture = findViewById(R.id.aperture_slider);
        TextView textAperture = findViewById(R.id.aperture_value_text);
        RecyclerView filterList = findViewById(R.id.filter_list);

        // Camera Controls
        ImageButton btnShutter = findViewById(R.id.btn_shutter);
        ImageButton btnSwitch = findViewById(R.id.btn_switch_camera);
        ImageButton btnGallery = findViewById(R.id.btn_gallery);
        ImageButton btnSettings = findViewById(R.id.btn_settings);

        // Portrait Toggle Logic
        btnPortrait.setOnClickListener(v -> {
            isPortraitMode = !isPortraitMode;
            mVideoRenderer.updatePortraitMode(isPortraitMode);

            if (isPortraitMode) {
                // Disable Filter Mode if active
                if (isFilterMode) {
                    btnFilter.performClick();
                }
                layoutAperture.setVisibility(View.VISIBLE);
                btnPortrait.setBackgroundColor(getResources().getColor(R.color.colorPrimary, getTheme()));
                btnPortrait.setTextColor(getResources().getColor(R.color.colorOnPrimary, getTheme()));
            } else {
                layoutAperture.setVisibility(View.GONE);
                btnPortrait.setBackgroundColor(getResources().getColor(R.color.colorSurface, getTheme()));
                btnPortrait.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
            }
        });

        // Filter Toggle Logic
        btnFilter.setOnClickListener(v -> {
            isFilterMode = !isFilterMode;

            if (isFilterMode) {
                // Disable Portrait Mode if active
                if (isPortraitMode) {
                    btnPortrait.performClick();
                }
                filterList.setVisibility(View.VISIBLE);
                btnFilter.setBackgroundColor(getResources().getColor(R.color.colorPrimary, getTheme()));
                btnFilter.setTextColor(getResources().getColor(R.color.colorOnPrimary, getTheme()));
            } else {
                filterList.setVisibility(View.GONE);
                btnFilter.setBackgroundColor(getResources().getColor(R.color.colorSurface, getTheme()));
                btnFilter.setTextColor(getResources().getColor(R.color.colorPrimary, getTheme()));
            }
        });

        // Setup Filter List
        List<FilterAdapter.FilterItem> filters = new ArrayList<>();
        filters.add(new FilterAdapter.FilterItem("Normal", 0, Color.LTGRAY));
        filters.add(new FilterAdapter.FilterItem("Mono", 1, Color.GRAY));
        filters.add(new FilterAdapter.FilterItem("Sepia", 2, Color.rgb(112, 66, 20)));
        filters.add(new FilterAdapter.FilterItem("Invert", 3, Color.WHITE));

        FilterAdapter adapter = new FilterAdapter(filters, filterId -> {
            mVideoRenderer.updateFilter(filterId);
        });
        filterList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        filterList.setAdapter(adapter);


        // Aperture Slider Logic
        sliderAperture.addOnChangeListener((slider, value, fromUser) -> {
            aperture = value;
            mVideoRenderer.updateBlurStrength(aperture);
            textAperture.setText(String.format(Locale.US, "%.1f", (10.0f - value) + 1.4f));
        });

        // Shutter Logic
        btnShutter.setOnClickListener(v -> {
            v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).withEndAction(() -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }).start();

            mCameraController.takePicture();
            Toast.makeText(this, "Picture Taken", Toast.LENGTH_SHORT).show();
        });

        // Switch Camera Logic
        btnSwitch.setOnClickListener(v -> {
            v.animate().rotationBy(180).setDuration(200).start();
            mCameraController.switchCamera();
        });

        // Gallery Logic
        btnGallery.setOnClickListener(v -> {
            mCameraController.getStorageController().openGallery();
        });

        // Settings (Resolution) Logic
        btnSettings.setOnClickListener(v -> {
            showResolutionDialog(mCameraController.getOutputSizes());
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
        if (!hasPermissionsGranted()) {
            requestCameraPermission();
        } else {
            mCameraController.startCamera();
        }
    }

    @Override
    public void onPause() {
        if (hasPermissionsGranted()) {
            mCameraController.stopCamera();
        }
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

    private boolean hasPermissionsGranted() {
        for (String permission : CAMERA_PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new ConfirmationDialog().show(getSupportFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length == CAMERA_PERMISSIONS.length) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        if (null == mErrorDialog || mErrorDialog.isHidden()) {
                            mErrorDialog = ErrorDialog.newInstance(getString(R.string.request_permission));
                            mErrorDialog.show(getSupportFragmentManager(), FRAGMENT_DIALOG);
                        }
                        break;
                    } else {
                        if (null != mErrorDialog) {
                            mErrorDialog.dismiss();
                        } else {
                            // Permission granted, recreate to start camera
                            recreate();
                        }
                    }
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final android.app.Activity activity = getActivity();
            assert activity != null;
            assert getArguments() != null;
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> activity.finish())
                    .create();
        }
    }

    /**
     * Shows OK/Cancel confirmation dialog about camera permission.
     */
    public static class ConfirmationDialog extends DialogFragment {

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final android.app.Activity activity = getActivity();
            assert activity != null;
            return new AlertDialog.Builder(activity)
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> ActivityCompat
                            .requestPermissions(activity, CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSION))
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> activity.finish())
                    .create();
        }
    }
}
