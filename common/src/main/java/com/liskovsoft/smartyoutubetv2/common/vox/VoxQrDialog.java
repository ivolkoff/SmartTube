package com.liskovsoft.smartyoutubetv2.common.vox;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.liskovsoft.smartyoutubetv2.common.R;

import java.util.Collections;

/** Login prompt with a locally generated QR code (no third-party QR service). */
public class VoxQrDialog {
    public static AlertDialog show(Activity activity, String title, String url, String text, Runnable onDismiss) {
        // a fixed size overflowed 720p screens: title, text and button must fit under the code
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int size = Math.min(metrics.widthPixels, metrics.heightPixels) * 2 / 5;
        ImageView qr = new ImageView(activity);
        qr.setImageBitmap(encode(url, size));

        TextView label = new TextView(activity);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(18);
        label.setPadding(0, 24, 0, 0);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 24, 48, 24);
        layout.addView(qr, new LinearLayout.LayoutParams(size, size));
        layout.addView(label);

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setTitle(title)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> onDismiss.run())
                .create();
        dialog.show();
        return dialog;
    }

    static Bitmap encode(String data, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 1));
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565);
        } catch (WriterException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
