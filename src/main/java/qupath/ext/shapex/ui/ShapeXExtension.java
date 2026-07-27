package qupath.ext.shapex.ui;

import qupath.ext.shapex.ShapeXResources;

import javafx.beans.property.BooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Registers the ShapeX window and metadata with QuPath.
 */
public final class ShapeXExtension implements QuPathExtension {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ShapeXExtension.class);

    private static final String DEFAULT_QUPATH_VERSION = "0.7.0";

    private static final String EXTENSION_NAME =
            ShapeXResources.get("title", "ShapeX");

    private static final String EXTENSION_DESCRIPTION =
            ShapeXResources.get(
                    "extension.description",
                    "Design plates and export QuPath annotations for laser microdissection and spatial workflows"
            );

    private static final Version EXTENSION_QUPATH_VERSION =
            parseVersion(ShapeXResources.get("extension.qupath.version", DEFAULT_QUPATH_VERSION));

    private boolean isInstalled;

    private final BooleanProperty enableExtensionProperty =
            PathPrefs.createPersistentPreference("enableShapeX", true);

    private Stage stage;

    private static Version parseVersion(String value) {
        try {
            return Version.parse(value);
        } catch (Exception e) {
            LOGGER.warn(
                    "Invalid extension.qupath.version '{}', using {}",
                    value,
                    DEFAULT_QUPATH_VERSION,
                    e
            );
            return Version.parse(DEFAULT_QUPATH_VERSION);
        }
    }

    private void installExtensionMenu(QuPathGUI qupath) {

        var extensionsMenu = qupath.getMenu("Extensions", true);

        MenuItem menuItem = new MenuItem(EXTENSION_NAME);
        menuItem.setOnAction(e -> showWindow(qupath));

        menuItem.disableProperty().bind(enableExtensionProperty.not());
        extensionsMenu.getItems().add(menuItem);
    }

    private void showWindow(QuPathGUI qupath) {

        if (stage == null) {
            stage = new Stage();

            var controllerRoot =
                    Controller.createInstanceWithController();

            Scene scene = new Scene(controllerRoot.root());

            stage.setScene(scene);
            stage.initOwner(qupath.getStage());
            stage.setTitle(EXTENSION_NAME);
            stage.setResizable(true);

            scene.getStylesheets().add(
                    ShapeXExtension.class
                            .getResource("/qupath/ext/shapex/ui/shapex.css")
                            .toExternalForm()
            );
        }

        stage.show();
    }

    @Override
    public void installExtension(QuPathGUI qupath) {

        if (isInstalled) {
            LOGGER.debug("{} is already installed", getName());
            return;
        }

        isInstalled = true;
        installExtensionMenu(qupath);
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }
}
