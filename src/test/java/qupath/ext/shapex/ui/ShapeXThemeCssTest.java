package qupath.ext.shapex.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ShapeXThemeCssTest {

    private static final String CSS = loadCss();

    @Test
    void regularTextAndIconsUseJavaFxThemeColors() {
        assertUsesThemeText(".fa-icon");
        assertUsesThemeText(".flip-axis-icon-solid");
        assertUsesThemeText(".plate-nav-icon");
        assertUsesThemeText(".plate-header");
        assertUsesThemeText(".slide-list-container .check-box");
        assertUsesThemeText(".slide-list-view .list-cell");
        assertUsesThemeText(".tessellator-preview-count");
    }

    @Test
    void plateHighlightsContrastWithBothLightAndDarkBackgrounds() {
        assertThemeAwareEffect(".plate-well-hover");
        assertThemeAwareEffect(".plate-well-cross");
    }

    @Test
    void regularPanelsUseJavaFxThemeSurfacesAndBorders() {
        assertThemeAwarePanel(".tessellator-control-card");
        assertThemeAwarePanel(".slide-scroll-pane");
        assertThemeAwarePanel(".slide-list-view");
        assertThemeAwarePanel(".tessellator-preview-count");
    }

    private static void assertUsesThemeText(String selector) {
        String rule = rule(selector);
        assertTrue(
                rule.contains("-fx-text-base-color"),
                () -> selector + " should use the JavaFX theme text color"
        );
        assertFalse(
                rule.matches("(?is).*\\bwhite\\b.*")
                        || rule.matches("(?is).*rgba\\(\\s*255\\s*,\\s*255\\s*,\\s*255.*"),
                () -> selector + " should not force light text"
        );
    }

    private static void assertThemeAwareEffect(String selector) {
        String rule = rule(selector);
        assertTrue(
                rule.contains("-fx-effect: dropshadow") && rule.contains("-fx-text-base-color"),
                () -> selector + " should derive its highlight from the active theme"
        );
    }

    private static void assertThemeAwarePanel(String selector) {
        String rule = rule(selector);
        assertTrue(
                rule.contains("-fx-control-inner-background"),
                () -> selector + " should use the JavaFX theme surface color"
        );
        assertTrue(
                rule.contains("-fx-box-border"),
                () -> selector + " should use the JavaFX theme border color"
        );
    }

    private static String rule(String selector) {
        int selectorStart = CSS.indexOf(selector);
        if (selectorStart < 0) {
            throw new AssertionError("Missing CSS selector " + selector);
        }
        int declarationStart = CSS.indexOf('{', selectorStart);
        int declarationEnd = CSS.indexOf('}', declarationStart);
        if (declarationStart < 0 || declarationEnd < 0) {
            throw new AssertionError("Incomplete CSS rule for " + selector);
        }
        return CSS.substring(declarationStart + 1, declarationEnd);
    }

    private static String loadCss() {
        try (InputStream stream = ShapeXThemeCssTest.class.getResourceAsStream(
                "/qupath/ext/shapex/ui/shapex.css"
        )) {
            if (stream == null) {
                throw new AssertionError("ShapeX stylesheet resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Could not read the ShapeX stylesheet", e);
        }
    }
}
