package qupath.ext.shapex.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.Pane;

import qupath.ext.shapex.core.MultiPlateAssignmentModel;
import qupath.ext.shapex.core.PlateAssignmentModel;

import javafx.scene.shape.Circle;

public class LegendManager {

    private final Pane legendSample;
    private final Pane legendUnused;
    private final Pane legendNegative;
    private final Pane legendExcluded;

    private final Label legendSamplesCount;
    private final Label legendUnusedCount;
    private final Label legendExcludedCount;

    private static final int SAMPLE_MODE_RAINBOW = 0;
    private static final int SAMPLE_MODE_ORANGE = 1;

    private int sampleColorMode = SAMPLE_MODE_RAINBOW;

    public LegendManager(
            Pane legendSample,
            Pane legendUnused,
            Pane legendNegative,
            Pane legendExcluded,
            Label legendSamplesCount,
            Label legendUnusedCount,
            Label legendExcludedCount
    ) {
        this.legendSample = legendSample;
        this.legendUnused = legendUnused;
        this.legendNegative = legendNegative;
        this.legendExcluded = legendExcluded;

        this.legendSamplesCount = legendSamplesCount;
        this.legendUnusedCount = legendUnusedCount;
        this.legendExcludedCount = legendExcludedCount;
    }

    /* ============================================================
     * Color Mode
     * ============================================================ */

    public void toggleSampleColorMode() {
        sampleColorMode = (sampleColorMode + 1) % 3;
        updateColorMode();
    }

    public int getSampleColorMode() {
        return sampleColorMode;
    }

    public void updateColorMode() {

        legendSample.getChildren().clear();

        if (sampleColorMode == SAMPLE_MODE_RAINBOW) {
            legendSample.getStyleClass()
                    .setAll("legend-square", "legend-sample-rainbow");
        } else if (sampleColorMode == SAMPLE_MODE_ORANGE) {
            legendSample.getStyleClass()
                    .setAll("legend-square", "legend-sample-mono");
        } else {
            legendSample.getStyleClass()
                    .setAll("legend-square", "legend-sample-grey");
            var triangle = SampleTriangleFactory.createSampleTriangle(14.0);
            legendSample.getChildren().add(triangle);
        }

        legendNegative.getStyleClass()
                .setAll("legend-square", "legend-negative");

        legendExcluded.getStyleClass()
                .setAll("legend-square", "legend-excluded");

        // Re-apply unused style based on current children state
        boolean currentlyHighlighted = !legendUnused.getChildren().isEmpty();
        setUnusedHighlight(currentlyHighlighted);
    }

    /* ============================================================
     * Count Updates
     * ============================================================ */

    public void updateCounts(
            MultiPlateAssignmentModel plateSet,
            int rows,
            int cols
    ) {

        if (plateSet == null)
            return;

        PlateAssignmentModel plate =
                plateSet.getCurrentPlate();

        if (plate == null)
            return;

        int total = rows * cols;

        int excluded = plateSet.getExcludedWells() != null
                ? plateSet.getExcludedWells().size()
                : 0;

        int disabledSamplesOnPlate = 0;
        if (plate.getSampleToWell() != null) {
            for (String sample : plate.getSampleToWell().keySet()) {
                if (plateSet.isSampleDisabled(sample)) {
                    disabledSamplesOnPlate++;
                }
            }
        }

        int mapped = plate.getSampleToWell() != null
                ? Math.max(0, plate.getSampleToWell().size() - disabledSamplesOnPlate)
                : 0;

        excluded += disabledSamplesOnPlate;

        int negative = plate.getNegativeControlWells() != null
                ? plate.getNegativeControlWells().size()
                : 0;

        int unused = Math.max(0, total - mapped - excluded - negative);

        legendSamplesCount.setText(Integer.toString(mapped));
        legendUnusedCount.setText(Integer.toString(unused));
        legendExcludedCount.setText(Integer.toString(excluded));
    }

    public void setUnusedHighlight(boolean highlight) {

        legendUnused.getChildren().clear();

        if (highlight) {

            legendUnused.getStyleClass()
                    .setAll("legend-square", "legend-unused-highlight");

            Circle circle = new Circle(7, 7, 4);
            circle.setFill(null);
            circle.setStrokeWidth(1.5);
            circle.getStyleClass().add("shapex-secondary-stroke");

            legendUnused.getChildren().add(circle);

        } else {

            legendUnused.getStyleClass()
                    .setAll("legend-square", "legend-unused");
        }
    }
}
