package com.alexzab.simplepix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private interface ColorCallback {
        void onColor(int color);
    }

    private static final int PICK_IMAGES = 1001;

    private static final int BG = Color.rgb(15, 17, 22);
    private static final int PANEL = Color.rgb(22, 25, 34);
    private static final int CHIP = Color.rgb(42, 46, 58);
    private static final int CHIP_ACTIVE = Color.rgb(77, 84, 164);
    private static final int ACCENT = Color.rgb(124, 131, 255);
    private static final int TEXT = Color.rgb(246, 247, 251);
    private static final int MUTED = Color.rgb(168, 174, 190);

    private CollageView collageView;
    private ImageButton undoButton;
    private ImageButton redoButton;
    private TextView eraserChip;

    private HorizontalScrollView paletteScroller;
    private LinearLayout paletteRow;
    private final List<TextView> groupButtons = new ArrayList<>();
    private String activeGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62)));

        FrameLayout canvasFrame = new FrameLayout(this);
        canvasFrame.setBackground(roundedDrawable(Color.rgb(28, 31, 40), 20));
        LinearLayout.LayoutParams canvasParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
        canvasParams.setMargins(dp(8), dp(2), dp(8), dp(8));

        collageView = new CollageView(this);
        collageView.setOnSelectionChangedListener(() -> {
            if (activeGroup != null
                    && paletteScroller != null
                    && paletteScroller.getVisibility() == View.VISIBLE) {
                populatePalette(activeGroup);
            }
        });
        FrameLayout.LayoutParams collageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        collageParams.setMargins(dp(2), dp(2), dp(2), dp(2));
        canvasFrame.addView(collageView, collageParams);
        root.addView(canvasFrame, canvasParams);

        root.addView(buildBottomPanel(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(shell);
        showStartupOverlay(shell);

        updateHistoryButtons();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(7), dp(8), dp(7));
        bar.setBackgroundColor(PANEL);
        bar.setElevation(dp(5));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_simplepix_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.setPadding(dp(8), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText("SimplePix");
        title.setTextColor(TEXT);
        title.setTextSize(19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText("COLLAGE EDITOR");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(9);
        subtitle.setLetterSpacing(0.12f);

        titles.addView(title);
        titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, dp(46), 1f));

        undoButton = topIconButton(android.R.drawable.ic_menu_revert, "Отменить");
        redoButton = topIconButton(android.R.drawable.ic_menu_recent_history, "Повторить");
        ImageButton save = topIconButton(android.R.drawable.ic_menu_save, "Сохранить");

        undoButton.setOnClickListener(v -> {
            if (!collageView.undo()) {
                Toast.makeText(this, "Отменять нечего", Toast.LENGTH_SHORT).show();
            }
            updateHistoryButtons();
            updateEraserChip();
        });

        redoButton.setOnClickListener(v -> {
            if (!collageView.redo()) {
                Toast.makeText(this, "Повторять нечего", Toast.LENGTH_SHORT).show();
            }
            updateHistoryButtons();
            updateEraserChip();
        });

        save.setOnClickListener(v -> saveCollage());

        bar.addView(undoButton);
        bar.addView(redoButton);
        bar.addView(save);
        return bar;
    }

    private View buildBottomPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(4), dp(8), dp(8));
        panel.setBackground(roundedTopDrawable(PANEL, 22));
        panel.setElevation(dp(8));

        paletteScroller = new HorizontalScrollView(this);
        paletteScroller.setHorizontalScrollBarEnabled(false);
        paletteScroller.setFillViewport(false);
        paletteScroller.setVisibility(View.GONE);
        paletteScroller.setAlpha(0f);

        paletteRow = new LinearLayout(this);
        paletteRow.setOrientation(LinearLayout.HORIZONTAL);
        paletteRow.setGravity(Gravity.CENTER_VERTICAL);
        paletteRow.setPadding(dp(3), dp(5), dp(3), dp(7));
        paletteScroller.addView(paletteRow);

        panel.addView(paletteScroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62)));

        LinearLayout groups = new LinearLayout(this);
        groups.setOrientation(LinearLayout.HORIZONTAL);
        groups.setGravity(Gravity.CENTER);

        TextView add = groupButton(android.R.drawable.ic_input_add, "Добавить");
        TextView edit = groupButton(android.R.drawable.ic_menu_edit, "Правка");
        TextView layers = groupButton(android.R.drawable.arrow_up_float, "Слои");
        TextView canvas = groupButton(android.R.drawable.ic_menu_crop, "Холст");

        add.setOnClickListener(v -> togglePalette("add", add));
        edit.setOnClickListener(v -> togglePalette("edit", edit));
        layers.setOnClickListener(v -> togglePalette("layers", layers));
        canvas.setOnClickListener(v -> togglePalette("canvas", canvas));

        groups.addView(add, weightedGroupParams());
        groups.addView(edit, weightedGroupParams());
        groups.addView(layers, weightedGroupParams());
        groups.addView(canvas, weightedGroupParams());

        panel.addView(groups, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)));

        return panel;
    }

    private LinearLayout.LayoutParams weightedGroupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private ImageButton topIconButton(int drawableRes, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawableRes);
        button.setColorFilter(TEXT);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackground(roundedDrawable(CHIP, 13));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.setMargins(dp(4), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView groupButton(int drawableRes, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(MUTED);
        view.setTextSize(11);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(4), dp(6), dp(4), dp(5));
        view.setBackground(roundedDrawable(Color.TRANSPARENT, 14));
        view.setContentDescription(label);

        Drawable icon = getDrawable(drawableRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(MUTED);
            icon.setBounds(0, 0, dp(22), dp(22));
            view.setCompoundDrawables(null, icon, null, null);
            view.setCompoundDrawablePadding(dp(3));
        }

        groupButtons.add(view);
        return view;
    }

    private TextView toolChip(int drawableRes, String label, View.OnClickListener listener) {
        return toolChip(drawableRes, label, true, null, listener);
    }

    private TextView toolChip(
            int drawableRes,
            String label,
            boolean available,
            String unavailableHint,
            View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(available ? TEXT : MUTED);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(13), 0, dp(15), 0);
        view.setMinHeight(dp(46));
        view.setBackground(roundedDrawable(
                available ? CHIP : Color.rgb(31, 34, 43),
                15));
        view.setContentDescription(label);
        view.setAlpha(available ? 1f : 0.42f);

        Drawable icon = getDrawable(drawableRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(available ? TEXT : MUTED);
            icon.setBounds(0, 0, dp(20), dp(20));
            view.setCompoundDrawables(icon, null, null, null);
            view.setCompoundDrawablePadding(dp(8));
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(46));
        params.setMargins(dp(4), 0, dp(4), 0);
        view.setLayoutParams(params);

        view.setOnClickListener(v -> {
            if (available) {
                listener.onClick(v);
            } else if (unavailableHint != null && !unavailableHint.isEmpty()) {
                Toast.makeText(this, unavailableHint, Toast.LENGTH_SHORT).show();
            }
        });
        return view;
    }

    private void togglePalette(String group, TextView pressed) {
        if (group.equals(activeGroup) && paletteScroller.getVisibility() == View.VISIBLE) {
            closePalette();
            return;
        }

        activeGroup = group;
        updateGroupStyles(pressed);

        if (paletteScroller.getVisibility() == View.VISIBLE) {
            paletteScroller.animate()
                    .alpha(0f)
                    .translationY(dp(8))
                    .setDuration(85)
                    .withEndAction(() -> {
                        populatePalette(group);
                        paletteScroller.setTranslationY(dp(14));
                        paletteScroller.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(170)
                                .start();
                    })
                    .start();
        } else {
            populatePalette(group);
            paletteScroller.setVisibility(View.VISIBLE);
            paletteScroller.setAlpha(0f);
            paletteScroller.setTranslationY(dp(18));
            paletteScroller.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(190)
                    .start();
        }
    }

    private void closePalette() {
        activeGroup = null;
        updateGroupStyles(null);
        paletteScroller.animate()
                .alpha(0f)
                .translationY(dp(16))
                .setDuration(140)
                .withEndAction(() -> {
                    paletteScroller.setVisibility(View.GONE);
                    paletteScroller.setTranslationY(0f);
                })
                .start();
    }

    private void updateGroupStyles(TextView active) {
        for (TextView view : groupButtons) {
            boolean selected = view == active;
            view.setTextColor(selected ? TEXT : MUTED);
            view.setBackground(roundedDrawable(
                    selected ? CHIP_ACTIVE : Color.TRANSPARENT,
                    14));

            Drawable[] drawables = view.getCompoundDrawables();
            if (drawables[1] != null) {
                drawables[1].setTint(selected ? TEXT : MUTED);
            }
        }
    }

    private void populatePalette(String group) {
        paletteRow.removeAllViews();
        eraserChip = null;

        switch (group) {
            case "add":
                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_gallery,
                        "Фото",
                        v -> chooseImages()));
                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_edit,
                        "Текст",
                        v -> showTextDialog()));
                paletteRow.addView(toolChip(
                        android.R.drawable.ic_input_add,
                        "Фигура",
                        v -> showShapeDialog()));
                break;

            case "edit":
                boolean imageSelected = collageView.isImageSelected();
                boolean anySelected = collageView.hasSelection();
                boolean shapeSelected = collageView.isShapeSelected();

                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_crop,
                        "Обрезка фото",
                        imageSelected,
                        "Сначала выберите фотографию",
                        v -> showCropDialog()));
                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_rotate,
                        "Поворот",
                        anySelected,
                        "Сначала выберите слой",
                        v -> showRotateDialog()));

                eraserChip = toolChip(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Ластик",
                        imageSelected,
                        "Сначала выберите фотографию",
                        v -> toggleEraser());
                paletteRow.addView(eraserChip);

                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_manage,
                        "Заливка",
                        shapeSelected,
                        "Сначала выберите фигуру",
                        v -> editSelectedFill()));
                updateEraserChip();
                break;

            case "layers":
                boolean layerSelected = collageView.hasSelection();

                paletteRow.addView(toolChip(
                        android.R.drawable.arrow_down_float,
                        "Ниже",
                        layerSelected,
                        "Сначала выберите слой",
                        v -> {
                            collageView.sendSelectedBackward();
                            updateHistoryButtons();
                        }));
                paletteRow.addView(toolChip(
                        android.R.drawable.arrow_up_float,
                        "Выше",
                        layerSelected,
                        "Сначала выберите слой",
                        v -> {
                            collageView.bringSelectedForward();
                            updateHistoryButtons();
                        }));
                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_delete,
                        "Удалить",
                        layerSelected,
                        "Сначала выберите слой",
                        v -> {
                            collageView.deleteSelected();
                            collageView.setEraserMode(false);
                            updateHistoryButtons();
                            updateEraserChip();
                        }));
                break;

            case "canvas":
            default:
                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_mapmode,
                        "Формат",
                        v -> showCanvasDialog()));
                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_zoom,
                        "По содержимому",
                        v -> {
                            if (!collageView.cropCanvasToContent()) {
                                Toast.makeText(
                                        this,
                                        "Пустых краёв для обрезки нет",
                                        Toast.LENGTH_SHORT).show();
                            }
                            updateHistoryButtons();
                        }));
                paletteRow.addView(toolChip(
                        android.R.drawable.ic_menu_manage,
                        "Фон",
                        v -> showColorDialog(
                                "Цвет фона",
                                collageView.getBackgroundColorValue(),
                                color -> {
                                    collageView.setBackgroundFill(color);
                                    updateHistoryButtons();
                                })));
                break;
        }
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

    private void showShapeDialog() {
        final int[] color = {ACCENT};

        LinearLayout box = dialogColumn();

        Button colorButton = new Button(this);
        colorButton.setAllCaps(false);
        updateColorButton(colorButton, color[0], "Заливка");
        box.addView(colorButton, fullWidth());

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Добавить фигуру")
                .setView(box)
                .setNegativeButton("Отмена", null)
                .create();

        Button rect = shapeButton("▭  Прямоугольник");
        Button round = shapeButton("▢  Скруглённая");
        Button ellipse = shapeButton("●  Эллипс");
        Button triangle = shapeButton("▲  Треугольник");

        row1.addView(rect, halfWidth());
        row1.addView(round, halfWidth());
        row2.addView(ellipse, halfWidth());
        row2.addView(triangle, halfWidth());

        box.addView(row1, fullWidth());
        box.addView(row2, fullWidth());

        colorButton.setOnClickListener(v -> showColorDialog(
                "Цвет заливки",
                color[0],
                selected -> {
                    color[0] = selected;
                    updateColorButton(colorButton, selected, "Заливка");
                }));

        rect.setOnClickListener(v -> {
            collageView.addShape(CollageView.SHAPE_RECT, color[0]);
            updateHistoryButtons();
            dialog.dismiss();
        });
        round.setOnClickListener(v -> {
            collageView.addShape(CollageView.SHAPE_ROUNDED_RECT, color[0]);
            updateHistoryButtons();
            dialog.dismiss();
        });
        ellipse.setOnClickListener(v -> {
            collageView.addShape(CollageView.SHAPE_ELLIPSE, color[0]);
            updateHistoryButtons();
            dialog.dismiss();
        });
        triangle.setOnClickListener(v -> {
            collageView.addShape(CollageView.SHAPE_TRIANGLE, color[0]);
            updateHistoryButtons();
            dialog.dismiss();
        });

        dialog.show();
    }

    private Button shapeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setPadding(dp(5), dp(2), dp(5), dp(2));
        return button;
    }

    private LinearLayout.LayoutParams halfWidth() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0,
                dp(52),
                1f);
        p.setMargins(dp(3), dp(4), dp(3), 0);
        return p;
    }

    private void editSelectedFill() {
        if (!collageView.isShapeSelected()) {
            Toast.makeText(this, "Выберите фигуру", Toast.LENGTH_SHORT).show();
            return;
        }

        showColorDialog(
                "Заливка фигуры",
                collageView.getSelectedShapeColor(),
                color -> {
                    collageView.setSelectedShapeColor(color);
                    updateHistoryButtons();
                });
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

        if (!collageView.beginCropMode()) {
            return;
        }

        LinearLayout box = dialogColumn();

        TextView hint = dialogLabel("Рамка на фото показывает итоговую область.");
        hint.setTextColor(MUTED);
        box.addView(hint, fullWidth());

        SeekBar left = cropSeekBar();
        SeekBar right = cropSeekBar();
        SeekBar top = cropSeekBar();
        SeekBar bottom = cropSeekBar();

        TextView leftLabel = dialogLabel("");
        TextView rightLabel = dialogLabel("");
        TextView topLabel = dialogLabel("");
        TextView bottomLabel = dialogLabel("");

        box.addView(leftLabel, fullWidth());
        box.addView(left, fullWidth());
        box.addView(rightLabel, fullWidth());
        box.addView(right, fullWidth());
        box.addView(topLabel, fullWidth());
        box.addView(top, fullWidth());
        box.addView(bottomLabel, fullWidth());
        box.addView(bottom, fullWidth());

        Runnable update = () -> {
            leftLabel.setText("Слева: " + left.getProgress() + "%");
            rightLabel.setText("Справа: " + right.getProgress() + "%");
            topLabel.setText("Сверху: " + top.getProgress() + "%");
            bottomLabel.setText("Снизу: " + bottom.getProgress() + "%");

            collageView.updateCropInsets(
                    left.getProgress() / 100f,
                    right.getProgress() / 100f,
                    top.getProgress() / 100f,
                    bottom.getProgress() / 100f);
        };

        left.setOnSeekBarChangeListener(simpleSeekListener(update));
        right.setOnSeekBarChangeListener(simpleSeekListener(update));
        top.setOnSeekBarChangeListener(simpleSeekListener(update));
        bottom.setOnSeekBarChangeListener(simpleSeekListener(update));
        update.run();

        final boolean[] resolved = {false};

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Обрезка фотографии")
                .setView(box)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null)
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                resolved[0] = true;
                collageView.applyCropMode();
                updateHistoryButtons();
                dialog.dismiss();
            });

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                resolved[0] = true;
                collageView.cancelCropMode();
                dialog.dismiss();
            });
        });

        dialog.setOnCancelListener(d -> {
            resolved[0] = true;
            collageView.cancelCropMode();
        });

        dialog.setOnDismissListener(d -> {
            if (!resolved[0] && collageView.isCropMode()) {
                collageView.cancelCropMode();
            }
            if (activeGroup != null
                    && paletteScroller != null
                    && paletteScroller.getVisibility() == View.VISIBLE) {
                populatePalette(activeGroup);
            }
        });

        dialog.show();
    }

    private SeekBar cropSeekBar() {
        SeekBar seek = new SeekBar(this);
        seek.setMax(45);
        seek.setProgress(0);
        return seek;
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
        seek.setProgress(Math.max(0, Math.min(360, Math.round(original) + 180)));

        Runnable updateLabel = () ->
                label.setText("Поворот: " + (seek.getProgress() - 180) + "°");
        updateLabel.run();

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateLabel.run();
                if (fromUser) {
                    collageView.setSelectedRotationDegrees(progress - 180f, false);
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
            updateEraserChip();
            return;
        }

        updateEraserChip();

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
                    updateEraserChip();
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
        preview.setBackground(roundedDrawable(initialColor, 14));
        box.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)));

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
            preview.setBackground(roundedDrawable(color, 14));
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

    private LinearLayout dialogColumn() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(6));
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

    private void updateEraserChip() {
        if (eraserChip == null) return;

        boolean available = collageView.isImageSelected() && !collageView.isCropMode();
        eraserChip.setAlpha(available ? 1f : 0.42f);
        eraserChip.setBackground(roundedDrawable(
                available && collageView.isEraserMode()
                        ? CHIP_ACTIVE
                        : (available ? CHIP : Color.rgb(31, 34, 43)),
                15));
    }

    private void updateHistoryButtons() {
        if (undoButton == null || redoButton == null || collageView == null) return;
        undoButton.setAlpha(collageView.canUndo() ? 1f : 0.38f);
        redoButton.setAlpha(collageView.canRedo() ? 1f : 0.38f);
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
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values);

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

    private void showStartupOverlay(FrameLayout shell) {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_simplepix_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("SimplePix");

        int logoSize = dp(132);
        overlay.addView(logo, new LinearLayout.LayoutParams(logoSize, logoSize));

        TextView splashTitle = new TextView(this);
        splashTitle.setText("SimplePix");
        splashTitle.setTextColor(TEXT);
        splashTitle.setTextSize(28);
        splashTitle.setGravity(Gravity.CENTER);
        splashTitle.setTypeface(null, android.graphics.Typeface.BOLD);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(8);
        overlay.addView(splashTitle, titleParams);

        shell.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        new Handler(Looper.getMainLooper()).postDelayed(() ->
                overlay.animate()
                        .alpha(0f)
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .setDuration(160L)
                        .withEndAction(() -> shell.removeView(overlay))
                        .start(), 330L);
    }

    private GradientDrawable roundedDrawable(int color, float radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private GradientDrawable roundedTopDrawable(int color, float radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        float r = dp(radiusDp);
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        return bg;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
