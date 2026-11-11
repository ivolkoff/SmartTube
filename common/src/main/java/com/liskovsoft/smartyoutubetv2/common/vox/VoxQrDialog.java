package com.liskovsoft.smartyoutubetv2.common.vox;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
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
    private static final int QR_SIZE_PX = 400;

    public static AlertDialog show(Activity activity, String title, String url, String text, Runnable onDismiss) {
        ImageView qr = new ImageView(activity);
        qr.setImageBitmap(encode(url));

        TextView label = new TextView(activity);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(18);
        label.setPadding(0, 24, 0, 0);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 24, 48, 24);
        layout.addView(qr, new LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX));
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

    static Bitmap encode(String data) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX,
                    Collections.singletonMap(EncodeHintType.MARGIN, 1));
            int[] pixels = new int[QR_SIZE_PX * QR_SIZE_PX];
            for (int y = 0; y < QR_SIZE_PX; y++) {
                for (int x = 0; x < QR_SIZE_PX; x++) {
                    pixels[y * QR_SIZE_PX + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.RGB_565);
        } catch (WriterException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
