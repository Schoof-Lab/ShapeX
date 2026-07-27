package qupath.ext.shapex;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Provides centralized access to ShapeX's localized strings and metadata.
 */
public final class ShapeXResources {

    private static final String BUNDLE_NAME = "qupath.ext.shapex.ui.strings";

    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle(BUNDLE_NAME);

    private ShapeXResources() {}

    public static ResourceBundle bundle() {
        return BUNDLE;
    }

    public static String get(String key) {
        try {
            return BUNDLE.getString(key);
        } catch (MissingResourceException e) {
            return '!' + key + '!';
        }
    }

    public static String get(String key, String fallback) {
        try {
            return BUNDLE.getString(key);
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    public static String format(String key, Object... args) {
        return MessageFormat.format(get(key), args);
    }

    public static String formatOrDefault(String key, String fallback, Object... args) {
        return MessageFormat.format(get(key, fallback), args);
    }
}
