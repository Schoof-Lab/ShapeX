package qupath.ext.shapex.core;

import qupath.lib.common.ColorTools;

public final class ShapeXColors {

    public static final int PRIMARY_RED = 253;
    public static final int PRIMARY_GREEN = 151;
    public static final int PRIMARY_BLUE = 8;
    public static final String PRIMARY_HEX = "#FD9708";
    public static final String PRIMARY_STROKE_HEX = "#BD6F00";
    public static final int PRIMARY_RGB = ColorTools.packRGB(PRIMARY_RED, PRIMARY_GREEN, PRIMARY_BLUE);

    public static final int SECONDARY_RED = 30;
    public static final int SECONDARY_GREEN = 144;
    public static final int SECONDARY_BLUE = 255;
    public static final String SECONDARY_HEX = "#1e90ff";
    public static final int SECONDARY_RGB = ColorTools.packRGB(SECONDARY_RED, SECONDARY_GREEN, SECONDARY_BLUE);

    private static volatile int currentPrimaryRgb = PRIMARY_RGB;
    private static volatile int currentSecondaryRgb = SECONDARY_RGB;

    private ShapeXColors() {
    }

    public static int currentPrimaryRgb() {
        return currentPrimaryRgb;
    }

    public static int currentSecondaryRgb() {
        return currentSecondaryRgb;
    }

    public static void setCurrentThemeColors(
            int primaryRed,
            int primaryGreen,
            int primaryBlue,
            int secondaryRed,
            int secondaryGreen,
            int secondaryBlue
    ) {
        currentPrimaryRgb = ColorTools.packRGB(primaryRed, primaryGreen, primaryBlue);
        currentSecondaryRgb = ColorTools.packRGB(secondaryRed, secondaryGreen, secondaryBlue);
    }

    public static void resetCurrentThemeColors() {
        currentPrimaryRgb = PRIMARY_RGB;
        currentSecondaryRgb = SECONDARY_RGB;
    }

    public static String primaryRgba(double opacity) {
        return rgba(currentPrimaryRgb, opacity);
    }

    public static String secondaryRgba(double opacity) {
        return rgba(currentSecondaryRgb, opacity);
    }

    private static String rgba(int rgb, double opacity) {
        return rgba(
                (rgb >> 16) & 0xff,
                (rgb >> 8) & 0xff,
                rgb & 0xff,
                opacity
        );
    }

    private static String rgba(int red, int green, int blue, double opacity) {
        return String.format(
                java.util.Locale.ROOT,
                "rgba(%d,%d,%d,%.3f)",
                red,
                green,
                blue,
                opacity
        );
    }
}
