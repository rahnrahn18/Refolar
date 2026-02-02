package com.media.camera.preview.controller;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class StorageController {
    private static final String TAG = "StorageController";
    private final Context mContext;
    private Uri lastSavedUri;

    public StorageController(Context context) {
        mContext = context;
    }

    public void saveImage(byte[] data, String displayName) {
        if (data == null || data.length == 0) {
            Log.e(TAG, "Save failed: data is empty");
            return;
        }

        try {
            OutputStream out = null;
            File file = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = mContext.getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName + ".jpg");
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Refolar");

                lastSavedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);

                if (lastSavedUri != null) {
                    out = resolver.openOutputStream(lastSavedUri);
                }
            } else {
                File storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Refolar");
                if (!storageDir.exists()) {
                    storageDir.mkdirs();
                }
                file = new File(storageDir, displayName + ".jpg");
                out = new FileOutputStream(file);
                lastSavedUri = FileProvider.getUriForFile(mContext, mContext.getPackageName() + ".fileprovider", file);
            }

            if (out != null) {
                out.write(data);
                out.flush();
                out.close();

                // Notify gallery scanner if using file path
                if (file != null) {
                     Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                     mediaScanIntent.setData(Uri.fromFile(file));
                     mContext.sendBroadcast(mediaScanIntent);
                }

                Log.i(TAG, "Image saved successfully: " + lastSavedUri);
                Toast.makeText(mContext, "Saved to Gallery", Toast.LENGTH_SHORT).show();
            } else {
                Log.e(TAG, "Failed to create output stream");
            }

        } catch (IOException e) {
            Log.e(TAG, "Error saving image", e);
            Toast.makeText(mContext, "Save Failed", Toast.LENGTH_SHORT).show();
        }
    }

    public void openGallery() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setType("image/*");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (lastSavedUri != null) {
            // Open specifically the last image if available
             intent.setDataAndType(lastSavedUri, "image/jpeg");
             intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        try {
            mContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open gallery", e);
            Toast.makeText(mContext, "No Gallery App Found", Toast.LENGTH_SHORT).show();
        }
    }
}
