package com.jotapeveloper.fision;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.jotapeveloper.fision.cloud.AuthManager;
import com.jotapeveloper.fision.cloud.AvatarManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Crop activity for the avatar workflow. Reads a picked image via a content
 * Uri, presents the CircularCropView, and on save produces a 512x512 JPEG that
 * is uploaded to the server through AvatarManager.
 */
public class CropAvatarActivity extends AppCompatActivity {

    public static final String EXTRA_SOURCE_URI = "source_uri";
    /** Square playlist cover: the JPEG is returned (EXTRA_RESULT_PATH), not uploaded. */
    public static final String EXTRA_SQUARE_COVER = "square_cover";
    public static final String EXTRA_RESULT_PATH = "result_path";
    private static final int OUT_SIZE_PX = 512;
    private static final int JPEG_QUALITY = 85;
    private static final int MAX_SOURCE_SIDE = 2048;   // safety cap before decoding

    private CircularCropView cropView;
    private ProgressBar spinner;
    private MaterialButton bSave;
    private MaterialButton bCancel;
    private boolean squareCover;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.jotapeveloper.fision.design.AccentColorManager.get(this).applyOverlayTo(this);
        setContentView(R.layout.activity_crop_avatar);
        cropView = findViewById(R.id.v_crop);
        spinner  = findViewById(R.id.pb_cropSpinner);
        bSave    = findViewById(R.id.b_cropSave);
        bCancel  = findViewById(R.id.b_cropCancel);

        Uri src = getIntent().getParcelableExtra(EXTRA_SOURCE_URI);
        if (src == null) {
            Toast.makeText(this, R.string.crop_read_fail, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Bitmap bm = decodeDownsampled(src);
        if (bm == null) {
            Toast.makeText(this, R.string.crop_read_fail, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        squareCover = getIntent().getBooleanExtra(EXTRA_SQUARE_COVER, false);
        cropView.setSquare(squareCover);
        if (squareCover) bSave.setText(R.string.crop_use);
        cropView.setBitmap(bm);

        bCancel.setOnClickListener(v -> finish());
        bSave.setOnClickListener(v -> onSaveClicked());
    }

    private void onSaveClicked() {
        Bitmap out = cropView.produceCrop(OUT_SIZE_PX);
        if (out == null) {
            Toast.makeText(this, R.string.crop_read_fail, Toast.LENGTH_LONG).show();
            return;
        }
        File jpeg = new File(getCacheDir(),
                squareCover ? "playlist_cover_upload.jpg" : "avatar_upload.jpg");
        try (FileOutputStream fos = new FileOutputStream(jpeg)) {
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
        } catch (Exception e) {
            Toast.makeText(this, R.string.crop_save_fail, Toast.LENGTH_LONG).show();
            return;
        } finally {
            out.recycle();
        }

        // Playlist cover: the editor uploads it together with the playlist.
        if (squareCover) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_PATH, jpeg.getAbsolutePath()));
            finish();
            return;
        }

        String user = AuthManager.get(this).getUsername();
        if (user.isEmpty()) { finish(); return; }

        setBusy(true);
        AvatarManager.get(this).uploadAvatar(user, jpeg, new AvatarManager.UploadCallback() {
            @Override public void onSuccess() {
                setBusy(false);
                Toast.makeText(CropAvatarActivity.this,
                        R.string.crop_upload_ok, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
            @Override public void onFailure(int code, String reason) {
                setBusy(false);
                int msg;
                switch (reason) {
                    case "too_large":     msg = R.string.crop_err_too_large; break;
                    case "bad_format":    msg = R.string.crop_err_bad_format; break;
                    case "network_error": msg = R.string.login_err_network; break;
                    default:              msg = R.string.login_err_server; break;
                }
                Toast.makeText(CropAvatarActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setBusy(boolean busy) {
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        bSave.setEnabled(!busy);
        bCancel.setEnabled(!busy);
    }

    /** Reads the incoming URI with an inSampleSize that keeps the longer side
     *  under MAX_SOURCE_SIDE, then applies the EXIF orientation so pictures
     *  taken with the phone in portrait mode do not arrive rotated. */
    private Bitmap decodeDownsampled(Uri uri) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, probe);
            }
            int sample = 1;
            int longer = Math.max(probe.outWidth, probe.outHeight);
            while ((longer / sample) > MAX_SOURCE_SIDE) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap raw;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                raw = BitmapFactory.decodeStream(in, null, opts);
            }
            if (raw == null) return null;
            return applyExifOrientation(uri, raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** Applies the EXIF orientation stored in the image metadata. Content
     *  pickers usually return the raw file without baking the rotation into
     *  the pixels, so the preview would look sideways / upside-down without
     *  this step. Recycles the input bitmap on success. */
    private Bitmap applyExifOrientation(Uri uri, Bitmap src) {
        int orientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                androidx.exifinterface.media.ExifInterface exif =
                        new androidx.exifinterface.media.ExifInterface(in);
                orientation = exif.getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {}

        android.graphics.Matrix m = new android.graphics.Matrix();
        switch (orientation) {
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1f, 1f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1f, -1f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90); m.postScale(-1f, 1f); break;
            case androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270); m.postScale(-1f, 1f); break;
            default:
                return src;
        }
        Bitmap fixed = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (fixed != src) src.recycle();
        return fixed;
    }
}
