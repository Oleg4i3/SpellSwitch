package com.spellswitch;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView statusView;
    private TextView alphaLabel;
    private LinearLayout orderContainer;
    private int density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = (int) getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 24 * density;
        root.setPadding(pad, pad * 2, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, 0, 0, pad);
        root.addView(statusView);

        Button openAccessibilityBtn = new Button(this);
        openAccessibilityBtn.setText("Открыть Специальные возможности");
        openAccessibilityBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(openAccessibilityBtn);

        addSpacer(root, pad);
        addAlphaSection(root, pad);

        addSpacer(root, pad);
        addOrderSection(root, pad);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        setContentView(scrollView);
    }

    private void addSpacer(LinearLayout parent, int height) {
        View spacer = new View(this);
        parent.addView(spacer, LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private void addAlphaSection(LinearLayout parent, int pad) {
        SharedPreferences prefs = getSharedPreferences(
                OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE);
        int currentAlpha = prefs.getInt(OverlayAccessibilityService.KEY_ALPHA_PERCENT, 80);

        alphaLabel = new TextView(this);
        alphaLabel.setText("Прозрачность ярлыка: " + currentAlpha + "%");
        parent.addView(alphaLabel);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress(currentAlpha);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int clamped = Math.max(15, progress); // ниже — ярлык станет не видно и не потрогать
                alphaLabel.setText("Прозрачность ярлыка: " + clamped + "%");
                getSharedPreferences(OverlayAccessibilityService.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putInt(OverlayAccessibilityService.KEY_ALPHA_PERCENT, clamped)
                        .apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        parent.addView(seekBar);
    }

    private void addOrderSection(LinearLayout parent, int pad) {
        TextView title = new TextView(this);
        title.setText("Порядок переключения (перетащите, чтобы изменить):");
        title.setPadding(0, 0, 0, pad / 2);
        parent.addView(title);

        orderContainer = new LinearLayout(this);
        orderContainer.setOrientation(LinearLayout.VERTICAL);
        parent.addView(orderContainer);

        refreshOrderRows();
    }

    private void refreshOrderRows() {
        orderContainer.removeAllViews();
        String[] order = SpellCheckerSwitcher.getOrder(this);

        for (String code : order) {
            TextView row = new TextView(this);
            row.setText("\u2630  " + SpellCheckerSwitcher.menuNameFor(code)
                    + "  (" + SpellCheckerSwitcher.labelFor(code) + ")");
            row.setTag(code);
            row.setTextSize(16);
            row.setPadding(24 * density, 20 * density, 24 * density, 20 * density);
            row.setBackgroundColor(Color.parseColor("#EEEEEE"));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 4 * density, 0, 4 * density);
            row.setLayoutParams(lp);

            row.setOnLongClickListener(v -> {
                ClipData dragData = ClipData.newPlainText("", "");
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                v.startDragAndDrop(dragData, shadow, v, 0);
                v.setVisibility(View.INVISIBLE);
                return true;
            });

            row.setOnDragListener((targetView, event) -> {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return true;

                    case DragEvent.ACTION_DRAG_ENTERED:
                        targetView.setBackgroundColor(Color.parseColor("#BBDEFB"));
                        return true;

                    case DragEvent.ACTION_DRAG_EXITED:
                        targetView.setBackgroundColor(Color.parseColor("#EEEEEE"));
                        return true;

                    case DragEvent.ACTION_DROP: {
                        View sourceView = (View) event.getLocalState();
                        String sourceCode = (String) sourceView.getTag();
                        String targetCode = (String) targetView.getTag();
                        swapInOrder(sourceCode, targetCode);
                        refreshOrderRows();
                        return true;
                    }

                    case DragEvent.ACTION_DRAG_ENDED:
                        targetView.setVisibility(View.VISIBLE);
                        targetView.setBackgroundColor(Color.parseColor("#EEEEEE"));
                        return true;

                    default:
                        return false;
                }
            });

            orderContainer.addView(row);
        }
    }

    private void swapInOrder(String sourceCode, String targetCode) {
        String[] order = SpellCheckerSwitcher.getOrder(this);
        int srcIdx = indexOf(order, sourceCode);
        int tgtIdx = indexOf(order, targetCode);
        if (srcIdx < 0 || tgtIdx < 0 || srcIdx == tgtIdx) return;

        String tmp = order[srcIdx];
        order[srcIdx] = order[tgtIdx];
        order[tgtIdx] = tmp;
        SpellCheckerSwitcher.setOrder(this, order);
    }

    private int indexOf(String[] arr, String val) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(val)) return i;
        }
        return -1;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean accessibilityOn = isAccessibilityServiceEnabled();
        boolean permissionGranted = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;

        StringBuilder sb = new StringBuilder();
        sb.append("Оверлей (Спец. возможности): ")
                .append(accessibilityOn ? "включён" : "ВЫКЛЮЧЕН — включите вручную")
                .append("\n\n");
        sb.append("Разрешение WRITE_SECURE_SETTINGS: ")
                .append(permissionGranted ? "выдано" : "НЕ выдано — нужен adb grant");
        statusView.setText(sb.toString());
    }

    private boolean isAccessibilityServiceEnabled() {
        String expected = getPackageName() + "/" + OverlayAccessibilityService.class.getName();
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        for (String s : enabledServices.split(":")) {
            if (s.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }
}
