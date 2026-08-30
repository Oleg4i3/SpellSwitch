package com.spellswitch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import java.util.List;

/**
 * Сервис нужен здесь не ради его "специальных возможностей" в обычном смысле,
 * а как единственный легальный способ рисовать системный оверлей
 * (TYPE_ACCESSIBILITY_OVERLAY) без разрешения SYSTEM_ALERT_WINDOW —
 * пользователь один раз включает его в Настройки → Специальные возможности.
 */
public class OverlayAccessibilityService extends AccessibilityService {

    static final String PREFS_NAME = "spellswitch_prefs";
    static final String KEY_TAP_THROUGH = "tap_through_enabled";
    static final String KEY_ALPHA_PERCENT = "overlay_alpha_percent";
    static final String KEY_HEIGHT_DP = "overlay_height_dp";
    static final String KEY_OVERLAY_X = "overlay_x";
    static final String KEY_OVERLAY_Y = "overlay_y";
    static final String KEY_SHOW_FLAG = "show_flag";

    private static final int LONG_PRESS_MS = 500;
    private static final int DRAG_SLOP_PX = 12;
    private static final int DEFAULT_ALPHA_PERCENT = 80;
    private static final int DEFAULT_HEIGHT_DP = 48;

    private WindowManager windowManager;
    private TextView overlayView;
    private WindowManager.LayoutParams layoutParams;
    private boolean overlayAdded;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    private float touchStartX;
    private float touchStartY;
    private int paramStartX;
    private int paramStartY;
    private boolean isDragging;
    private long touchDownTime;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prepareOverlay();
        registerPrefsListener();
        updateOverlayVisibility();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void prepareOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = new TextView(this);
        overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));
        overlayView.setTextColor(Color.WHITE);
        overlayView.setBackgroundColor(Color.parseColor("#1565C0"));
        overlayView.setGravity(Gravity.CENTER);
        overlayView.setPadding(28, 0, 28, 0);
        overlayView.setTextSize(14);
        applyAlpha();

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                dpToPx(getHeightDp()),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        loadSavedPosition();

        overlayView.setOnTouchListener(this::onOverlayTouch);
    }

    private void registerPrefsListener() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefsListener = (sp, key) -> {
            if (KEY_ALPHA_PERCENT.equals(key)) {
                applyAlpha();
            } else if (KEY_HEIGHT_DP.equals(key)) {
                applyHeight();
            } else if (KEY_SHOW_FLAG.equals(key)) {
                overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void applyAlpha() {
        int percent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_ALPHA_PERCENT, DEFAULT_ALPHA_PERCENT);
        overlayView.setAlpha(percent / 100f);
    }

    private void applyHeight() {
        layoutParams.height = dpToPx(getHeightDp());
        if (overlayAdded) {
            windowManager.updateViewLayout(overlayView, layoutParams);
        }
    }

    private int getHeightDp() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /**
     * Показывает/прячет оверлей в зависимости от того, есть ли сейчас среди
     * окон экрана окно с типом TYPE_INPUT_METHOD (то есть открыта клавиатура).
     */
    private void updateOverlayVisibility() {
        boolean keyboardVisible = isKeyboardVisible();

        if (keyboardVisible && !overlayAdded) {
            windowManager.addView(overlayView, layoutParams);
            overlayAdded = true;
        } else if (!keyboardVisible && overlayAdded) {
            windowManager.removeView(overlayView);
            overlayAdded = false;
        }
    }

    private boolean isKeyboardVisible() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return true;
            }
        }
        return false;
    }

    private void loadSavedPosition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        layoutParams.x = prefs.getInt(KEY_OVERLAY_X, 40);
        layoutParams.y = prefs.getInt(KEY_OVERLAY_Y, 200);
    }

    private void savePosition() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_OVERLAY_X, layoutParams.x)
                .putInt(KEY_OVERLAY_Y, layoutParams.y)
                .apply();
    }

    private boolean onOverlayTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                paramStartX = layoutParams.x;
                paramStartY = layoutParams.y;
                isDragging = false;
                touchDownTime = System.currentTimeMillis();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - touchStartX;
                float dy = event.getRawY() - touchStartY;
                if (!isDragging && (Math.abs(dx) > DRAG_SLOP_PX || Math.abs(dy) > DRAG_SLOP_PX)) {
                    isDragging = true;
                }
                if (isDragging) {
                    layoutParams.x = paramStartX + (int) dx;
                    layoutParams.y = paramStartY + (int) dy;
                    windowManager.updateViewLayout(overlayView, layoutParams);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                long heldMs = System.currentTimeMillis() - touchDownTime;
                if (isDragging) {
                    savePosition();
                } else if (heldMs >= LONG_PRESS_MS) {
                    showLanguageMenu();
                } else {
                    cycleLanguage();
                }
                return true;
            }
        }
        return false;
    }

    private void cycleLanguage() {
        SpellCheckerSwitcher.cycleNext(this);
        overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));

        if (isTapThroughEnabled()) {
            dispatchTapThrough();
        }
    }

    /**
     * Синтетический тап ровно в то место экрана, где сейчас лежит оверлей —
     * если он откалиброван на кнопку переключения раскладки JBak2, этот тап
     * попадёт на неё. Требует android:canPerformGestures="true" в конфиге
     * сервиса. dispatchGesture() — публичный API AccessibilityService с API 24.
     */
    private void dispatchTapThrough() {
        int[] loc = new int[2];
        overlayView.getLocationOnScreen(loc);
        float x = loc[0] + overlayView.getWidth() / 2f;
        float y = loc[1] + overlayView.getHeight() / 2f;

        float savedAlpha = overlayView.getAlpha();
        overlayView.setVisibility(View.INVISIBLE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 60);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();

            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    overlayView.setAlpha(savedAlpha);
                    overlayView.setVisibility(View.VISIBLE);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    overlayView.setAlpha(savedAlpha);
                    overlayView.setVisibility(View.VISIBLE);
                }
            }, null);
        }, 60);
    }

    private boolean isTapThroughEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_TAP_THROUGH, true);
    }

    private void setTapThroughEnabled(boolean enabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_TAP_THROUGH, enabled).apply();
    }

    private void showLanguageMenu() {
        boolean tapThroughOn = isTapThroughEnabled();
        String[] order = SpellCheckerSwitcher.getOrder(this);

        String[] items = new String[order.length + 1];
        for (int i = 0; i < order.length; i++) {
            items[i] = SpellCheckerSwitcher.menuNameFor(order[i]);
        }
        items[order.length] = "Синхро-тап по раскладке: "
                + (tapThroughOn ? "ВКЛ (нажмите, чтобы выключить)" : "ВЫКЛ (нажмите, чтобы включить)");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setItems(items, (d, which) -> {
                    if (which < order.length) {
                        SpellCheckerSwitcher.setLanguage(this, order[which]);
                        overlayView.setText(SpellCheckerSwitcher.currentDisplayText(this));
                    } else {
                        setTapThroughEnabled(!tapThroughOn);
                    }
                })
                .create();

        showAsOverlay(dialog);
    }

    private void showAsOverlay(AlertDialog dialog) {
        // Диалог из Service/AccessibilityService не имеет своего Activity-окна,
        // поэтому тип окна нужно выставить вручную — тем же типом, что и оверлей.
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        updateOverlayVisibility();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (prefsListener != null) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        return super.onUnbind(intent);
    }
}
