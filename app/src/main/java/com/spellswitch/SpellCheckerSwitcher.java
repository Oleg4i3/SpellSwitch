package com.spellswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;
import android.view.textservice.TextServicesManager;

/**
 * Переключает язык системной службы проверки орфографии.
 *
 * Ключ "selected_spell_checker_subtype" — скрытая (@hide) настройка
 * Settings.Secure, публичной Java-константы для неё нет, поэтому обращаемся
 * по строковому имени напрямую. Запись требует android.permission.WRITE_SECURE_SETTINGS:
 *   adb shell pm grant com.spellswitch android.permission.WRITE_SECURE_SETTINGS
 *
 * TextServicesManager.getCurrentSpellCheckerSubtype(boolean) — метод, который
 * должен был бы отдавать "текущий выбранный язык" — в реальности удалён из
 * публичного API (подтверждено логом сборки + апстрим-коммитом CTS-тестов,
 * переименовавшим/убравшим часть методов этого класса). Поэтому текущий язык
 * мы не спрашиваем у системы, а храним сами: раз это единственный компонент,
 * который его меняет, наша копия в SharedPreferences и есть источник истины.
 * Единственный побочный эффект — если язык поменяли не через это приложение
 * (вручную в системных настройках), наш счётчик разойдётся с реальным до
 * следующего переключения — это не страшно для сценария личного шортката.
 */
public class SpellCheckerSwitcher {

    public static final String[] LANGS = {"ru", "uk", "en"};
    private static final String[] LABELS = {"RU", "UK", "EN"};
    public static final String[] MENU_LABELS = {"Русский", "Українська", "English"};

    private static final String PREFS_NAME = "spellswitch_prefs";
    private static final String KEY_LANG_INDEX = "current_lang_index";

    public static String currentLanguageLabel(Context context) {
        return LABELS[currentIndex(context)];
    }

    public static void cycleNext(Context context) {
        int nextIndex = (currentIndex(context) + 1) % LANGS.length;
        setLanguage(context, LANGS[nextIndex]);
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
                saveIndex(context, indexOf(langTag));
                return;
            }
        }
    }

    private static int indexOf(String langTag) {
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(langTag)) return i;
        }
        return 0;
    }

    private static int currentIndex(Context context) {
        return prefs(context).getInt(KEY_LANG_INDEX, 0);
    }

    private static void saveIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_LANG_INDEX, index).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
