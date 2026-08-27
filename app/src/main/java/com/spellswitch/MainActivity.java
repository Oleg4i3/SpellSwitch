package com.spellswitch;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
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

        setContentView(root);
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
                .append(permissionGranted ? "выдано" : "НЕ выдано — нужно adb grant");
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
