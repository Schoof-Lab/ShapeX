package qupath.ext.shapex.ui;

import qupath.ext.shapex.ShapeXResources;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/**
 * Small dialog helpers shared by the controller managers.
 */
final class ControllerDialogs {

    private ControllerDialogs() {}

    static void showError(Node ownerNode, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle(ShapeXResources.get("title"));
        alert.setHeaderText(null);
        Window owner = resolveOwner(ownerNode);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    static void showInfo(Node ownerNode, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setTitle(ShapeXResources.get("title"));
        alert.setHeaderText(null);
        Window owner = resolveOwner(ownerNode);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    static boolean showConfirmation(Node ownerNode, String header, String message) {
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                message,
                ButtonType.YES,
                ButtonType.NO
        );
        alert.setTitle(ShapeXResources.get("title"));
        alert.setHeaderText(header);
        Window owner = resolveOwner(ownerNode);
        if (owner != null) {
            alert.initOwner(owner);
        }
        return alert.showAndWait()
                .filter(ButtonType.YES::equals)
                .isPresent();
    }

    static Window resolveOwner(Node ownerNode) {
        if (ownerNode == null || ownerNode.getScene() == null) {
            return null;
        }
        return ownerNode.getScene().getWindow();
    }
}
