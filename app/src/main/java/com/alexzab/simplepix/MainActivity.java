package com.alexzab.simplepix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int PICK_IMAGES = 1001;
    private CollageView collageView;
    private Button eraserButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(20, 20, 20));
        getWindow().setNavigationBarColor(Color.rgb(20, 20, 20));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 20, 20));

        TextView title = new TextView(this);
        title.setText("SimplePix");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(10), dp(16), dp(6));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(dp(8), dp(4), dp(8), dp(8));

        Button addPhoto = toolButton("Фото");
        Button addText = toolButton("Текст");
        Button fill = toolButton("Фон");
        eraserButton = toolButton("Ластик");
        Button backward = toolButton("Ниже");
        Button forward = toolButton("Выше");
        Button delete = toolButton("Удалить");
        Button save = toolButton("Сохранить");

        tools.addView(addPhoto);
        tools.addView(addText);
        tools.addView(fill);
        tools.addView(eraserButton);
        tools.addView(backward);
        tools.addView(forward);
        tools.addView(delete);
        tools.addView(save);

        scroller.addView(tools);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        collageView = new CollageView(this);
        LinearLayout.LayoutParams canvasParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        canvasParams.setMargins(dp(8), dp(0), dp(8), dp(8));
        root.addView(collageView, canvasParams);

        setContentView(root);

        addPhoto.setOnClickListener(v -> chooseImages());
        addText.setOnClickListener(v -> showTextDialog());
        fill.setOnClickListener(v -> showBackgroundDialog());

        eraserButton.setOnClickListener(v -> {
            boolean wanted = !collageView.isEraserMode();
            boolean enabled = collageView.setEraserMode(wanted);
            if (wanted && !enabled) {
                Toast.makeText(this, "Сначала выберите фотографию", Toast.LENGTH_SHORT).show();
            }
            updateEraserButton();
        });

        backward.setOnClickListener(v -> collageView.sendSelectedBackward());
        forward.setOnClickListener(v -> collageView.bringSelectedForward());
        delete.setOnClickListener(v -> {
            collageView.deleteSelected();
            updateEraserButton();
        });
        save.setOnClickListener(v -> saveCollage());
    }

    private Button toolButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextAllCaps(false);
        button.setTextSize(14);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(14), dp(4), dp(14), dp(4));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void chooseImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMAGES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_IMAGES || resultCode != RESULT_OK || data == null) {
            return;
        }

        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                loadImage(clipData.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            loadImage(data.getData());
        }
        updateEraserButton();
    }

    private void loadImage(Uri uri) {
        try {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (SecurityException ignored) {
            }

            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (max > 2400) {
                    int sample = (int) Math.ceil(max / 2400.0);
                    decoder.setTargetSampleSize(Math.max(1, sample));
                }
            });
            collageView.addImage(bitmap);
        } catch (IOException e) {
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show();
        }
    }

    private void showTextDialog() {
        EditText input = new EditText(this);
        input.setHint("Текст");
        input.setSingleLine(false);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("Добавить текст")
                .setView(input)
                .setPositiveButton("Добавить", (dialog, which) ->
                        collageView.addText(input.getText().toString()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showBackgroundDialog() {
        final String[] names = {
                "Светлый", "Белый", "Чёрный",
                "Тёплый серый", "Нежно-розовый", "Светло-голубой"
        };
        final int[] colors = {
                Color.rgb(238, 238, 238),
                Color.WHITE,
                Color.BLACK,
                Color.rgb(210, 204, 196),
                Color.rgb(244, 216, 224),
                Color.rgb(210, 228, 244)
        };

        new AlertDialog.Builder(this)
                .setTitle("Цвет фона")
                .setItems(names, (dialog, which) ->
                        collageView.setBackgroundFill(colors[which]))
                .show();
    }

    private void updateEraserButton() {
        eraserButton.setText(collageView.isEraserMode() ? "Ластик ✓" : "Ластик");
    }

    private void saveCollage() {
        Bitmap bitmap = collageView.renderToBitmap();
        String fileName = "SimplePix_" + System.currentTimeMillis() + ".png";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SimplePix");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (uri == null) {
            Toast.makeText(this, "Не удалось создать файл", Toast.LENGTH_SHORT).show();
            return;
        }

        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("PNG write failed");
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            Toast.makeText(this, "Сохранено в Pictures/SimplePix", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            getContentResolver().delete(uri, null, null);
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
