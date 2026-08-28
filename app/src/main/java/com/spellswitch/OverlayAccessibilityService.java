package com.spellswitch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Сервис нужен здесь не ради его "специальных возможностей" в обычном смысле,
 * а как единственный легальный способ рисовать системный оверлей
 * (TYPE_ACCESSIBILITY_OVERLAY) без разрешения SYSTEM_ALERT_WINDOW —
 * пользователь один раз включает его в Настройки → Специальные возможности.
 */
public class OverlayAccessibilityService extends AccessibilityService {

    private static final int LONG_PRESS_MS = 500;
    private static final int DRAG_SLOP_PX = 12;

    private WindowManager windowManager;
    private TextView overlayView;
    private WindowManager.LayoutParams layoutParams;
    private boolean overlayAdded;

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
        updateOverlayVisibility();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void prepareOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = new TextView(this);
        overlayView.setText(SpellCheckerSwitcher.currentLanguageLabel(this));
        overlayView.setTextColor(Color.WHITE);
        overlayView.setBackgroundColor(Color.parseColor("#CC1565C0"));
        overlayView.setPadding(28, 20, 28, 20);
        overlayView.setTextSize(14);

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        loadSavedPosition();

        overlayView.setOnTouchListener(this::onOverlayTouch);
    }

    /**
     * Показывает/прячет оверлей в зависимости от того, есть ли сейчас среди
     * окон экрана окно с типом TYPE_INPUT_METHOD (то есть открыта клавиатура).
     * Вызывается при каждом относящемся к окнам accessibility-событии.
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
        SharedPreferences prefs = getSharedPreferences("spellswitch_prefs", MODE_PRIVATE);
        layoutParams.x = prefs.getInt("overlay_x", 40);
        layoutParams.y = prefs.getInt("overlay_y", 200);
    }

    private void savePosition() {
        getSharedPreferences("spellswitch_prefs", MODE_PRIVATE)
                .edit()
                .putInt("overlay_x", layoutParams.x)
                .putInt("overlay_y", layoutParams.y)
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
                } else {
                    if (heldMs >= LONG_PRESS_MS) {
                        showLanguageMenu();
                    } else {
                        cycleLanguage();
                    }
                }
                return true;
            }
        }
        return false;
    }

    private void cycleLanguage() {
        SpellCheckerSwitcher.cycleNext(this);
        overlayView.setText(SpellCheckerSwitcher.currentLanguageLabel(this));

        if (isTapThroughEnabled()) {
            dispatchTapThrough();
        }
    }

    /**
     * Синтетический тап ровно в то место экрана, где сейчас лежит оверлей —
     * если пользователь заранее перетащил индикатор точно на кнопку
     * переключения раскладки JBak2, этот тап попадёт на неё. Требует
     * android:canPerformGestures="true" в конфиге сервиса.
     *
     * dispatchGesture() — задокументированный публичный API AccessibilityService
     * с API 24, тот же механизм, которым пользуются инструменты автоматизации
     * экрана. Прячем оверлей на короткое время перед тапом, иначе синтетический
     * тап попадёт на само наше окно (оно и так лежит поверх всего остального).
     */
    private void dispatchTapThrough() {
        int[] loc = new int[2];
        overlayView.getLocationOnScreen(loc);
        float x = loc[0] + overlayView.getWidth() / 2f;
        float y = loc[1] + overlayView.getHeight() / 2f;

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
                    overlayView.setVisibility(View.VISIBLE);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    overlayView.setVisibility(View.VISIBLE);
                }
            }, null);
        }, 60);
    }

    private boolean isTapThroughEnabled() {
        return getSharedPreferences("spellswitch_prefs", MODE_PRIVATE)
                .getBoolean("tap_through_enabled", false);
    }

    private void setTapThroughEnabled(boolean enabled) {
        getSharedPreferences("spellswitch_prefs", MODE_PRIVATE)
                .edit().putBoolean("tap_through_enabled", enabled).apply();
    }

    private void showLanguageMenu() {
        boolean tapThroughOn = isTapThroughEnabled();
        String[] items = {
                SpellCheckerSwitcher.MENU_LABELS[0],
                SpellCheckerSwitcher.MENU_LABELS[1],
                SpellCheckerSwitcher.MENU_LABELS[2],
                "Диагностика клавиатуры (IME tree)",
                "Проверить getCurrentInputMethodSubtype()",
                "Синхро-тап по раскладке: " + (tapThroughOn ? "ВКЛ (нажмите, чтобы выключить)" : "ВЫКЛ (нажмите, чтобы включить)")
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setItems(items, (d, which) -> {
                    if (which < SpellCheckerSwitcher.LANGS.length) {
                        SpellCheckerSwitcher.setLanguage(this, SpellCheckerSwitcher.LANGS[which]);
                        overlayView.setText(SpellCheckerSwitcher.currentLanguageLabel(this));
                    } else if (which == 3) {
                        showImeDump();
                    } else if (which == 4) {
                        showImeSubtypeCheck();
                    } else {
                        setTapThroughEnabled(!tapThroughOn);
                    }
                })
                .create();

        showAsOverlay(dialog);
    }

    private void showImeSubtypeCheck() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        String result;
        if (imm == null) {
            result = "InputMethodManager недоступен";
        } else {
            InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
            result = subtype == null
                    ? "getCurrentInputMethodSubtype() вернул null — JBak2, похоже, "
                        + "не регистрирует свои языки как системные InputMethodSubtype."
                    : "locale=" + subtype.getLocale()
                        + "\nmode=" + subtype.getMode()
                        + "\nlanguageTag=" + subtype.getLanguageTag()
                        + "\nextraValue=" + subtype.getExtraValue();
        }

        TextView textView = new TextView(this);
        textView.setText(result);
        textView.setTextIsSelectable(true);
        textView.setPadding(24, 24, 24, 24);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("getCurrentInputMethodSubtype()")
                .setView(textView)
                .setPositiveButton("Закрыть", null)
                .create();

        showAsOverlay(dialog);
    }

    /**
     * Диагностика: показывает дерево accessibility-узлов текущего окна
     * клавиатуры (TYPE_INPUT_METHOD) — текст, contentDescription и координаты
     * каждого узла. Нужно, чтобы понять, виден ли вообще индикатор языка
     * JBak2 для accessibility API, и если да — как его отличить от прочих
     * узлов (по тексту/позиции).
     */
    private void showImeDump() {
        String dump = buildImeDump();

        TextView textView = new TextView(this);
        textView.setText(dump);
        textView.setTextIsSelectable(true);
        textView.setTextSize(11);
        textView.setPadding(24, 24, 24, 24);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(textView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("IME accessibility tree")
                .setView(scroll)
                .setPositiveButton("Закрыть", null)
                .create();

        showAsOverlay(dialog);
    }

    private String buildImeDump() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) {
            return "getWindows() вернул пусто. Открыта ли клавиатура прямо сейчас?";
        }

        StringBuilder sb = new StringBuilder();
        boolean foundIme = false;

        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            foundIme = true;

            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                sb.append("Окно TYPE_INPUT_METHOD найдено, но root == null "
                        + "(вероятно, canRetrieveWindowContent/flags не сработали).\n");
            } else {
                dumpNode(root, 0, sb);
            }
        }

        if (!foundIme) {
            sb.append("Окно с типом TYPE_INPUT_METHOD не найдено среди ")
                    .append(windows.size())
                    .append(" окон. Список типов: ");
            for (AccessibilityWindowInfo w : windows) {
                sb.append(w.getType()).append(" ");
            }
        }

        return sb.length() == 0 ? "(пусто)" : sb.toString();
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth, StringBuilder sb) {
        if (node == null) return;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(node.getClassName())
                .append(" text=\"").append(node.getText()).append("\"")
                .append(" desc=\"").append(node.getContentDescription()).append("\"")
                .append(" bounds=").append(bounds)
                .append("\n");

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            dumpNode(node.getChild(i), depth + 1, sb);
        }
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
}
