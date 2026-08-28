package com.spellswitch;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
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

    private float touchStartX;
    private float touchStartY;
    private int paramStartX;
    private int paramStartY;
    private boolean isDragging;
    private long touchDownTime;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        showOverlay();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showOverlay() {
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
        layoutParams.x = 40;
        layoutParams.y = 200;

        overlayView.setOnTouchListener(this::onOverlayTouch);

        windowManager.addView(overlayView, layoutParams);
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
                if (!isDragging) {
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
    }

    private void showLanguageMenu() {
        String[] items = {
                SpellCheckerSwitcher.MENU_LABELS[0],
                SpellCheckerSwitcher.MENU_LABELS[1],
                SpellCheckerSwitcher.MENU_LABELS[2],
                "Диагностика клавиатуры (IME tree)"
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setItems(items, (d, which) -> {
                    if (which < SpellCheckerSwitcher.LANGS.length) {
                        SpellCheckerSwitcher.setLanguage(this, SpellCheckerSwitcher.LANGS[which]);
                        overlayView.setText(SpellCheckerSwitcher.currentLanguageLabel(this));
                    } else {
                        showImeDump();
                    }
                })
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
        // Не используется: сервис существует только как хост для оверлея.
    }

    @Override
    public void onInterrupt() {
    }
}
