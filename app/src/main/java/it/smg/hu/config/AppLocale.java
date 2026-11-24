package it.smg.hu.config;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Language of the app, chosen in the settings and independent of the head
 * unit. Italian is the default; {@link #SYSTEM} follows the head unit.
 *
 * Android 4.0 has no per-context configuration (createConfigurationContext is
 * API 17), so this rewrites the locale of the shared Resources. The system puts
 * its own locale back on every configuration change, which is why it is
 * applied again in ODAApplication.onConfigurationChanged and in every
 * Activity.onCreate before setContentView.
 */
public final class AppLocale {

    public static final String SYSTEM = "system";

    private AppLocale() {}

    /** The language tag stored in the settings: "it", "en", "pt" or "system". */
    public static String preference() {
        Settings settings = Settings.instance();
        String language = settings != null ? settings.appearance.language() : null;
        return language == null || language.length() == 0
                ? Settings.Appearance.LANGUAGE_DEFAULT_VALUE : language;
    }

    /** The locale the app should use right now. */
    public static Locale target() {
        String language = preference();
        if (SYSTEM.equals(language)) {
            return Resources.getSystem().getConfiguration().locale;
        }
        return new Locale(language);
    }

    /**
     * Applies the chosen language to the resources of the context and of its
     * application (on Android 4.0 they are the same object). Returns true when
     * the locale actually changed, so a visible screen knows it must be
     * recreated.
     */
    public static boolean apply(Context context) {
        if (context == null) {
            return false;
        }
        Locale locale = target();
        boolean changed = applyTo(context.getResources(), locale);
        Context application = context.getApplicationContext();
        if (application != null && application != context) {
            changed |= applyTo(application.getResources(), locale);
        }
        if (!locale.equals(Locale.getDefault())) {
            Locale.setDefault(locale);
        }
        return changed;
    }

    private static boolean applyTo(Resources resources, Locale locale) {
        if (resources == null) {
            return false;
        }
        Configuration current = resources.getConfiguration();
        if (locale.equals(current.locale)) {
            return false;
        }
        Configuration config = new Configuration(current);
        config.locale = locale;
        resources.updateConfiguration(config, resources.getDisplayMetrics());
        return true;
    }
}
