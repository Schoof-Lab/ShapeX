package qupath.ext.shapex.core;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.roi.PointsROI;
import qupath.lib.roi.ROIs;
import qupath.lib.geom.Point2;
import qupath.ext.shapex.ShapeXResources;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CalibrationResolver {

    private CalibrationResolver() {}

    public static final String AUTO_CLASS = "Calibration_Points";

    // ============================================================
    // ENUM FOR CLEAN VALIDATION CONTROL
    // ============================================================

    public enum CalibrationStatus {
        VALID,
        NEEDS_SELECTION,
        MISSING
    }

    // ============================================================
    // PURE VALIDATION (NO DIALOGS HERE)
    // ============================================================

    public static CalibrationStatus checkCalibrationStatus(
            PathObjectHierarchy hierarchy
    ) {

        List<PathObject> annotationPoints = hierarchy.getAnnotationObjects()
                .stream()
                .filter(p -> p.getROI() instanceof PointsROI)
                .toList();

        if (annotationPoints.isEmpty())
            return CalibrationStatus.MISSING;

        // Case 1 — multipoint with exactly 3
        for (PathObject obj : annotationPoints) {
            PointsROI roi = (PointsROI) obj.getROI();
            if (roi.getAllPoints().size() == 3)
                return CalibrationStatus.VALID;
        }

        // Case 2 — single points
        List<PathObject> singlePoints = annotationPoints.stream()
                .filter(p -> ((PointsROI) p.getROI()).getAllPoints().size() == 1)
                .toList();

        if (singlePoints.size() == 3)
            return CalibrationStatus.VALID;

        if (singlePoints.size() > 3)
            return CalibrationStatus.NEEDS_SELECTION;

        return CalibrationStatus.MISSING;
    }

    public static int countCalibrationPoints(PathObjectHierarchy hierarchy) {
        if (hierarchy == null) {
            return 0;
        }

        return hierarchy.getAnnotationObjects().stream()
                .filter(p -> p.getROI() instanceof PointsROI)
                .mapToInt(p -> ((PointsROI) p.getROI()).getAllPoints().size())
                .sum();
    }

    public static List<PathObject> resolveUserSelection(
            PathObjectHierarchy hierarchy,
            String slideName
    ) throws Exception {

        List<PathObject> annotationPoints = hierarchy.getAnnotationObjects()
                .stream()
                .filter(p -> p.getROI() instanceof PointsROI)
                .toList();

        List<PathObject> singlePointObjects = annotationPoints.stream()
                .filter(p -> ((PointsROI) p.getROI()).getAllPoints().size() == 1)
                .toList();

        if (singlePointObjects.size() <= 3)
            return singlePointObjects;

        return showSelectionDialog(singlePointObjects, slideName);
    }

    // ============================================================
    // Calibration-point standardization
    // ============================================================

    public static void resolveAndStandardize(
            PathObjectHierarchy hierarchy,
            List<PathObject> manualSelection
    ) throws Exception {

        List<PathObject> annotationPoints = hierarchy.getAnnotationObjects()
                .stream()
                .filter(p -> p.getROI() instanceof PointsROI)
                .toList();

        if (annotationPoints.isEmpty())
            throw new Exception(ShapeXResources.get("error.calibration.no_pointsroi"));

        List<PointData> candidatePoints = new ArrayList<>();

        // ============================================================
        // CASE 0 — USER MANUAL SELECTION (highest priority)
        // ============================================================

        if (manualSelection != null && manualSelection.size() == 3) {

            for (PathObject obj : manualSelection) {
                Point2 pt = ((PointsROI) obj.getROI()).getAllPoints().get(0);
                candidatePoints.add(new PointData(pt.getX(), pt.getY()));
            }

        } else {

            // ============================================================
            // Case 1 — multipoint with exactly 3
            // ============================================================

            for (PathObject obj : annotationPoints) {

                PointsROI roi = (PointsROI) obj.getROI();
                List<Point2> pts = roi.getAllPoints();

                if (pts.size() == 3) {
                    for (Point2 pt : pts)
                        candidatePoints.add(new PointData(pt.getX(), pt.getY()));
                    break;
                }
            }

            // ============================================================
            // Case 2 — exactly 3 single point annotations
            // ============================================================

            if (candidatePoints.isEmpty()) {

                List<PathObject> singlePoints = annotationPoints.stream()
                        .filter(p -> ((PointsROI) p.getROI()).getAllPoints().size() == 1)
                        .toList();

                if (singlePoints.size() != 3) {
                    throw new Exception(
                            ShapeXResources.get("error.calibration.not_resolved")
                    );
                }

                for (PathObject obj : singlePoints) {
                    Point2 pt = ((PointsROI) obj.getROI()).getAllPoints().get(0);
                    candidatePoints.add(new PointData(pt.getX(), pt.getY()));
                }
            }
        }

        if (candidatePoints.size() != 3)
            throw new Exception(ShapeXResources.get("error.calibration.expected_three"));

        // ============================================================
        // Sort left → right
        // ============================================================

        candidatePoints.sort((a, b) -> {
            int cmp = Double.compare(a.x, b.x);
            if (cmp != 0) return cmp;
            return Double.compare(a.y, b.y);
        });

        // ============================================================
        // Remove previous auto points
        // ============================================================

        List<PathObject> toRemove = hierarchy.getAnnotationObjects()
                .stream()
                .filter(p -> p.getPathClass() != null &&
                        AUTO_CLASS.equals(p.getPathClass().getName()))
                .toList();

        if (!toRemove.isEmpty())
            hierarchy.removeObjects(toRemove, true);

        // ============================================================
        // Create new standardized calibration points
        // ============================================================

        PointsROI referenceROI =
                (PointsROI) annotationPoints.get(0).getROI();

        for (int i = 0; i < 3; i++) {

            PointData pt = candidatePoints.get(i);

            ROI newROI = ROIs.createPointsROI(
                    pt.x,
                    pt.y,
                    referenceROI.getImagePlane()
            );

            PathObject newObj =
                    PathObjects.createAnnotationObject(newROI);

            newObj.setName(ShapeXResources.format("calibration.point.name", i + 1));
            int calibrationColor = ShapeXColors.currentPrimaryRgb();
            newObj.setPathClass(PathClass.fromString(AUTO_CLASS, calibrationColor));
            newObj.setColor(calibrationColor);

            hierarchy.addObject(newObj);
        }
    }

    // ============================================================
    // Manual calibration-point selection
    // ============================================================

    private static List<PathObject> showSelectionDialog(
            List<PathObject> candidates,
            String slideName
    ) throws Exception {

        while (true) {

            List<PathObject> selected = new ArrayList<>();

            Dialog<List<PathObject>> dialog = new Dialog<>();
            dialog.setTitle(ShapeXResources.format("calibration.dialog.title", slideName));
            dialog.initModality(Modality.APPLICATION_MODAL);

            VBox box = new VBox(10);
            List<CheckBox> checkBoxes = new ArrayList<>();

            for (PathObject obj : candidates) {

                String name = obj.getName() != null
                        ? obj.getName()
                        : ShapeXResources.get("annotation.unnamed.short");

                String label = ShapeXResources.format("annotation.label.with_id", name, obj.getID());

                CheckBox cb = new CheckBox(label);

                checkBoxes.add(cb);
                box.getChildren().add(cb);
            }

            dialog.getDialogPane().setContent(box);
            dialog.getDialogPane().getButtonTypes()
                    .addAll(ButtonType.OK, ButtonType.CANCEL);

            dialog.setResultConverter(btn -> {
                if (btn == ButtonType.OK) {
                    for (int i = 0; i < checkBoxes.size(); i++) {
                        if (checkBoxes.get(i).isSelected())
                            selected.add(candidates.get(i));
                    }
                    return selected;
                }
                return null;
            });

            Optional<List<PathObject>> result = dialog.showAndWait();

            if (result.isEmpty())
                throw new Exception(ShapeXResources.get("error.calibration.selection.cancelled"));

            if (result.get().size() == 3)
                return result.get();

            new Alert(Alert.AlertType.ERROR,
                    ShapeXResources.get("error.calibration.must_select_three"))
                    .showAndWait();
        }
    }

    // ============================================================
    // HELPER CLASS
    // ============================================================

    private static class PointData {

        final double x;
        final double y;

        PointData(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
