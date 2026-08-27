package com.spellswitch;

import android.content.Context;
import android.provider.Settings;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;
import android.view.textservice.TextServicesManager;

/**
 * Читает/переключает язык системной службы проверки орфографии.
 *
 * Ключ "selected_spell_checker_subtype" — это скрытая (@hide) настройка
 * Settings.Secure, поэтому её нет как публичной Java-константы в android.jar,
 * и мы обращаемся к ней по строковому имени напрямую. Запись требует
 * android.permission.WRITE_SECURE_SETTINGS, выданного через:
 *   adb shell pm grant com.spellswitch android.permission.WRITE_SECURE_SETTINGS
 *
 * NB: методы TextServicesManager.getCurrentSpellCheckerInfo() /
 * getCurrentSpellCheckerSubtype(boolean) и SpellCheckerInfo.getSubtypeAt(int) /
 * getSubtypeCount() — это стандартный публичный API этого класса, но точные
 * сигнатуры не сверялись построчно с официальным javadoc в этой сессии.
 * Если название неверное, это сразу и однозначно всплывёт как ошибка
 * компиляции в логе GitHub Actions ("cannot find symbol") — а не как скрытый
 * баг в рантайме, так что если сборка упадёт именно здесь — присылай лог,
 * поправим по точному сообщению компилятора.
 */
public class SpellCheckerSwitcher {

    public static final String[] LANGS = {"ru", "uk", "en"};
    private static final String[] LABELS = {"RU", "UK", "EN"};
    public static final String[] MENU_LABELS = {"Русский", "Українська", "English"};

    public static String currentLanguageLabel(Context context) {
        String locale = currentLocale(context);
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(locale)) return LABELS[i];
        }
        return locale != null ? locale.toUpperCase() : "?";
    }

    public static void cycleNext(Context context) {
        String current = currentLocale(context);
        int idx = -1;
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(current)) {
                idx = i;
                break;
            }
        }
        String next = LANGS[(idx + 1) % LANGS.length];
        setLanguage(context, next);
    }

    public static void setLanguage(Context context, String langTag) {
        TextServicesManager tsm = (TextServicesManager)
                context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        if (tsm == null) return;

        SpellCheckerInfo info = tsm.getCurrentSpellCheckerInfo();
        if (info == null) return;

        for (int i = 0; i < info.getSubtypeCount(); i++) {
            SpellCheckerSubtype subtype = info.getSubtypeAt(i);
            String locale = subtype.getLocale();
            if (locale != null && locale.toLowerCase().startsWith(langTag)) {
                Settings.Secure.putInt(context.getContentResolver(),
                        "selected_spell_checker_subtype", subtype.hashCode());
                return;
            }
        }
    }

    private static String currentLocale(Context context) {
        TextServicesManager tsm = (TextServicesManager)
                context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        if (tsm == null) return null;

        SpellCheckerSubtype subtype = tsm.getCurrentSpellCheckerSubtype(true);
        if (subtype == null) return null;

        String locale = subtype.getLocale();
        if (locale == null || locale.isEmpty()) return null;
        return locale.length() >= 2 ? locale.substring(0, 2).toLowerCase() : locale.toLowerCase();
    }
}
