package qupath.ext.shapex.core;

import javafx.scene.transform.Scale;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;

/**
 * Applies a reversible display-only flip to the active QuPath viewer.
 */
public final class WindowFlipper {

    private static boolean flipVertical = false;
    private static boolean flipHorizontal = false;

    private WindowFlipper() {}

    public static void flipImageVertically(boolean enabled) {
        flipVertical = enabled;
        applyTransform();
    }

    public static void flipImageHorizontally(boolean enabled) {
        flipHorizontal = enabled;
        applyTransform();
    }

    private static void applyTransform() {

        QuPathViewer viewer = QuPathGUI.getInstance().getViewer();
        if (viewer == null || viewer.getView() == null) {
            return;
        }

        viewer.getView().getTransforms().clear();

        Scale scale = new Scale(
                flipHorizontal ? -1 : 1,
                flipVertical ? -1 : 1,
                viewer.getView().getWidth() / 2,
                viewer.getView().getHeight() / 2
        );

        viewer.getView().getTransforms().add(scale);
    }
}
