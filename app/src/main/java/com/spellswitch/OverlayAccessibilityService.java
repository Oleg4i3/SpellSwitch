package com.spellswitch;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

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
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setItems(SpellCheckerSwitcher.MENU_LABELS, (d, which) -> {
                    SpellCheckerSwitcher.setLanguage(this, SpellCheckerSwitcher.LANGS[which]);
                    overlayView.setText(SpellCheckerSwitcher.currentLanguageLabel(this));
                })
                .create();

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
