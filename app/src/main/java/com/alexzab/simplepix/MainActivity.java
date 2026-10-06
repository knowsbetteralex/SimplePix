package com.alexzab.simplepix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

public class MainActivity extends Activity {

    private interface ColorCallback {
        void onColor(int color);
    }

    private static final int PICK_IMAGES = 1001;

    private CollageView collageView;
    private ImageButton eraserButton;
    private ImageButton undoButton;
    private ImageButton redoButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(18, 18, 20));
        getWindow().setNavigationBarColor(Color.rgb(18, 18, 20));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 20));
        applySystemInsets(root);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(8), dp(8));

        TextView title = new TextView(this);
        title.setText("SimplePix");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(
                0, dp(44), 1f));

        undoButton = iconButton(android.R.drawable.ic_menu_revert, "Отменить");
        redoButton = iconButton(android.R.drawable.ic_menu_recent_history, "Повторить");
        ImageButton saveButton = iconButton(android.R.drawable.ic_menu_save, "Сохранить");

        topBar.addView(undoButton);
        topBar.addView(redoButton);
        topBar.addView(saveButton);

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        collageView = new CollageView(this);
        root.addView(collageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setBackgroundColor(Color.rgb(24, 24, 27));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(8), dp(8), dp(8), dp(10));

        ImageButton addPhoto = iconButton(
                android.R.drawable.ic_menu_gallery, "Добавить фото");
        ImageButton text = iconButton(
                android.R.drawable.ic_menu_edit, "Добавить или изменить текст");
        ImageButton cropPhoto = iconButton(
                android.R.drawable.ic_menu_crop, "Обрезать выбранное фото");
        eraserButton = iconButton(
                android.R.drawable.ic_menu_close_clear_cancel, "Ластик");
        ImageButton rotate = iconButton(
                android.R.drawable.ic_menu_rotate, "Повернуть слой");
        ImageButton fill = iconButton(
                android.R.drawable.ic_menu_manage, "Цвет фона");
        ImageButton canvas = iconButton(
                android.R.drawable.ic_menu_mapmode, "Формат холста");
        ImageButton trimCanvas = iconButton(
                android.R.drawable.ic_menu_zoom, "Обрезать пустой холст");
        ImageButton backward = iconButton(
                android.R.drawable.arrow_down_float, "Слой ниже");
        ImageButton forward = iconButton(
                android.R.drawable.arrow_up_float, "Слой выше");
        ImageButton delete = iconButton(
                android.R.drawable.ic_menu_delete, "Удалить слой");

        tools.addView(addPhoto);
        tools.addView(text);
        tools.addView(cropPhoto);
        tools.addView(eraserButton);
        tools.addView(rotate);
        tools.addView(fill);
        tools.addView(canvas);
        tools.addView(trimCanvas);
        tools.addView(backward);
        tools.addView(forward);
        tools.addView(delete);

        scroller.addView(tools);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        setContentView(root);

        addPhoto.setOnClickListener(v -> chooseImages());
        text.setOnClickListener(v -> showTextDialog());
        cropPhoto.setOnClickListener(v -> showCropDialog());
        rotate.setOnClickListener(v -> showRotateDialog());

        fill.setOnClickListener(v -> showColorDialog(
                "Цвет фона",
                collageView.getBackgroundColorValue(),
                color -> {
                    collageView.setBackgroundFill(color);
                    updateHistoryButtons();
                }));

        canvas.setOnClickListener(v -> showCanvasDialog());

        trimCanvas.setOnClickListener(v -> {
            if (!collageView.cropCanvasToContent()) {
                Toast.makeText(
                        this,
                        "Пустых краёв для обрезки нет",
                        Toast.LENGTH_SHORT).show();
            }
            updateHistoryButtons();
        });

        eraserButton.setOnClickListener(v -> toggleEraser());

        backward.setOnClickListener(v -> {
            collageView.sendSelectedBackward();
            updateHistoryButtons();
        });

        forward.setOnClickListener(v -> {
            collageView.bringSelectedForward();
            updateHistoryButtons();
        });

        delete.setOnClickListener(v -> {
            collageView.deleteSelected();
            collageView.setEraserMode(false);
            updateEraserButton();
            updateHistoryButtons();
        });

        undoButton.setOnClickListener(v -> {
            if (!collageView.undo()) {
                Toast.makeText(this, "Отменять нечего", Toast.LENGTH_SHORT).show();
            }
            updateEraserButton();
            updateHistoryButtons();
        });

        redoButton.setOnClickListener(v -> {
            if (!collageView.redo()) {
                Toast.makeText(this, "Повторять нечего", Toast.LENGTH_SHORT).show();
            }
            updateEraserButton();
            updateHistoryButtons();
        });

        saveButton.setOnClickListener(v -> saveCollage());

        updateHistoryButtons();
        updateEraserButton();
    }

    private ImageButton iconButton(int drawableRes, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawableRes);
        button.setColorFilter(Color.WHITE);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackground(buttonBackground(Color.rgb(55, 55, 62)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(48), dp(48));
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable buttonBackground(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        return bg;
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

        updateHistoryButtons();
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
        final boolean editing = collageView.isTextSelected();
        final int[] chosenColor = {
                editing ? collageView.getSelectedTextColor() : Color.WHITE
        };

        LinearLayout box = dialogColumn();

        EditText input = new EditText(this);
        input.setHint("Текст");
        input.setSingleLine(false);
        input.setText(editing ? collageView.getSelectedText() : "");
        box.addView(input, fullWidth());

        TextView sizeLabel = dialogLabel("");
        box.addView(sizeLabel, fullWidth());

        SeekBar sizeSeek = new SeekBar(this);
        sizeSeek.setMax(104);
        int initialSize = Math.round(editing ? collageView.getSelectedTextSizeSp() : 48f);
        sizeSeek.setProgress(Math.max(0, Math.min(104, initialSize - 16)));
        box.addView(sizeSeek, fullWidth());

        LinearLayout checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.HORIZONTAL);

        CheckBox bold = new CheckBox(this);
        bold.setText("Жирный");
        bold.setChecked(editing && collageView.isSelectedTextBold());

        CheckBox italic = new CheckBox(this);
        italic.setText("Курсив");
        italic.setChecked(editing && collageView.isSelectedTextItalic());

        checks.addView(bold);
        checks.addView(italic);
        box.addView(checks, fullWidth());

        Button colorButton = new Button(this);
        colorButton.setAllCaps(false);
        updateColorButton(colorButton, chosenColor[0], "Цвет текста");
        box.addView(colorButton, fullWidth());

        Runnable updateSizeLabel = () -> sizeLabel.setText(
                "Размер: " + (sizeSeek.getProgress() + 16) + " sp");
        updateSizeLabel.run();

        sizeSeek.setOnSeekBarChangeListener(simpleSeekListener(updateSizeLabel));

        colorButton.setOnClickListener(v -> showColorDialog(
                "Цвет текста",
                chosenColor[0],
                color -> {
                    chosenColor[0] = color;
                    updateColorButton(colorButton, color, "Цвет текста");
                }));

        new AlertDialog.Builder(this)
                .setTitle(editing ? "Изменить текст" : "Добавить текст")
                .setView(box)
                .setPositiveButton(editing ? "Применить" : "Добавить", (dialog, which) -> {
                    float sizeSp = sizeSeek.getProgress() + 16f;
                    if (editing) {
                        collageView.updateSelectedText(
                                input.getText().toString(),
                                chosenColor[0],
                                sizeSp,
                                bold.isChecked(),
                                italic.isChecked());
                    } else {
                        collageView.addText(
                                input.getText().toString(),
                                chosenColor[0],
                                sizeSp,
                                bold.isChecked(),
                                italic.isChecked());
                    }
                    updateHistoryButtons();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showCropDialog() {
        if (!collageView.isImageSelected()) {
            Toast.makeText(this, "Сначала выберите фотографию", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = {
                "1:1 — квадрат",
                "4:5 — портрет",
                "3:4 — портрет",
                "16:9 — широкий",
                "9:16 — вертикальный",
                "Срезать по 5% с каждого края"
        };

        float[] ratios = {
                1f,
                4f / 5f,
                3f / 4f,
                16f / 9f,
                9f / 16f,
                0f
        };

        new AlertDialog.Builder(this)
                .setTitle("Обрезка фотографии")
                .setItems(names, (dialog, which) -> {
                    collageView.cropSelected(ratios[which]);
                    updateHistoryButtons();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showRotateDialog() {
        if (!collageView.hasSelection()) {
            Toast.makeText(this, "Сначала выберите слой", Toast.LENGTH_SHORT).show();
            return;
        }

        final float original = collageView.getSelectedRotationDegrees();
        final boolean[] changed = {false};

        LinearLayout box = dialogColumn();
        TextView label = dialogLabel("");

        SeekBar seek = new SeekBar(this);
        seek.setMax(360);
        seek.setProgress(Math.round(original) + 180);

        Runnable updateLabel = () -> {
            int degrees = seek.getProgress() - 180;
            label.setText("Поворот: " + degrees + "°");
        };
        updateLabel.run();

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int degrees = progress - 180;
                updateLabel.run();
                if (fromUser) {
                    collageView.setSelectedRotationDegrees(degrees, false);
                    changed[0] = true;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        box.addView(label, fullWidth());
        box.addView(seek, fullWidth());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Поворот слоя")
                .setView(box)
                .setPositiveButton("Готово", null)
                .setNegativeButton("Отмена", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (changed[0]) {
                    float finalAngle = seek.getProgress() - 180f;
                    collageView.setSelectedRotationDegrees(original, false);
                    collageView.setSelectedRotationDegrees(finalAngle, true);
                    updateHistoryButtons();
                }
                dialog.dismiss();
            });

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (changed[0]) {
                    collageView.setSelectedRotationDegrees(original, false);
                }
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void toggleEraser() {
        boolean wanted = !collageView.isEraserMode();
        boolean enabled = collageView.setEraserMode(wanted);

        if (wanted && !enabled) {
            Toast.makeText(this, "Сначала выберите фотографию", Toast.LENGTH_SHORT).show();
            updateEraserButton();
            return;
        }

        updateEraserButton();

        if (enabled && wanted) {
            showEraserSizeDialog();
        }
    }

    private void showEraserSizeDialog() {
        LinearLayout box = dialogColumn();

        TextView label = dialogLabel("");
        SeekBar size = new SeekBar(this);
        size.setMax(116);
        size.setProgress(Math.max(
                0,
                Math.min(116, Math.round(collageView.getEraserRadiusDp()) - 4)));

        Runnable update = () -> label.setText(
                "Радиус: " + (size.getProgress() + 4) + " dp");

        update.run();
        size.setOnSeekBarChangeListener(simpleSeekListener(update));

        box.addView(label, fullWidth());
        box.addView(size, fullWidth());

        new AlertDialog.Builder(this)
                .setTitle("Размер ластика")
                .setView(box)
                .setPositiveButton("Готово", (dialog, which) ->
                        collageView.setEraserRadiusDp(size.getProgress() + 4f))
                .setNeutralButton("Выключить", (dialog, which) -> {
                    collageView.setEraserMode(false);
                    updateEraserButton();
                })
                .show();
    }

    private void showCanvasDialog() {
        String[] names = {
                "По размеру экрана",
                "1:1 — квадрат",
                "4:5 — пост",
                "3:4 — фото",
                "9:16 — Stories / Shorts",
                "16:9 — широкий",
                "A4 — портрет"
        };

        float[] ratios = {
                0f,
                1f,
                4f / 5f,
                3f / 4f,
                9f / 16f,
                16f / 9f,
                210f / 297f
        };

        new AlertDialog.Builder(this)
                .setTitle("Формат холста")
                .setItems(names, (dialog, which) -> {
                    collageView.setCanvasAspectRatio(ratios[which]);
                    updateHistoryButtons();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showColorDialog(String title, int initialColor, ColorCallback callback) {
        LinearLayout box = dialogColumn();

        TextView preview = new TextView(this);
        preview.setGravity(Gravity.CENTER);
        preview.setTextColor(contrastText(initialColor));
        preview.setTextSize(16);
        box.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView rLabel = dialogLabel("R");
        SeekBar r = colorSeek(Color.red(initialColor));
        TextView gLabel = dialogLabel("G");
        SeekBar g = colorSeek(Color.green(initialColor));
        TextView bLabel = dialogLabel("B");
        SeekBar b = colorSeek(Color.blue(initialColor));

        box.addView(rLabel, fullWidth());
        box.addView(r, fullWidth());
        box.addView(gLabel, fullWidth());
        box.addView(g, fullWidth());
        box.addView(bLabel, fullWidth());
        box.addView(b, fullWidth());

        Runnable update = () -> {
            int color = Color.rgb(r.getProgress(), g.getProgress(), b.getProgress());
            preview.setBackgroundColor(color);
            preview.setTextColor(contrastText(color));
            preview.setText(String.format(
                    Locale.US,
                    "#%02X%02X%02X",
                    r.getProgress(),
                    g.getProgress(),
                    b.getProgress()));
            rLabel.setText("R: " + r.getProgress());
            gLabel.setText("G: " + g.getProgress());
            bLabel.setText("B: " + b.getProgress());
        };

        update.run();
        r.setOnSeekBarChangeListener(simpleSeekListener(update));
        g.setOnSeekBarChangeListener(simpleSeekListener(update));
        b.setOnSeekBarChangeListener(simpleSeekListener(update));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Выбрать", (dialog, which) ->
                        callback.onColor(Color.rgb(
                                r.getProgress(),
                                g.getProgress(),
                                b.getProgress())))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private SeekBar colorSeek(int value) {
        SeekBar seek = new SeekBar(this);
        seek.setMax(255);
        seek.setProgress(value);
        return seek;
    }

    private SeekBar.OnSeekBarChangeListener simpleSeekListener(Runnable onChange) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                onChange.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    private void updateColorButton(Button button, int color, String prefix) {
        button.setText(String.format(
                Locale.US,
                "%s  #%02X%02X%02X",
                prefix,
                Color.red(color),
                Color.green(color),
                Color.blue(color)));
    }

    private int contrastText(int color) {
        double luminance = 0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
        return luminance > 150 ? Color.BLACK : Color.WHITE;
    }

    private LinearLayout dialogColumn() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        return box;
    }

    private TextView dialogLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setPadding(0, dp(8), 0, 0);
        return label;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void updateEraserButton() {
        if (eraserButton == null) return;

        eraserButton.setBackground(buttonBackground(
                collageView.isEraserMode()
                        ? Color.rgb(84, 92, 190)
                        : Color.rgb(55, 55, 62)));
    }

    private void updateHistoryButtons() {
        if (undoButton == null || redoButton == null) return;

        undoButton.setAlpha(collageView.canUndo() ? 1f : 0.45f);
        redoButton.setAlpha(collageView.canRedo() ? 1f : 0.45f);
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

    private void applySystemInsets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout());
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();

                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }

            v.setPadding(left, top, right, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
