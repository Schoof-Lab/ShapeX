package qupath.ext.shapex.ui;

import javafx.scene.shape.Polygon;

public final class SampleTriangleFactory {

    private static final double TOP_Y_RATIO = 0.26;
    private static final double BOTTOM_Y_RATIO = 0.67;
    private static final double HALF_WIDTH_RATIO = 0.19;
    private static final double STROKE_WIDTH = 1.7;

    private SampleTriangleFactory() {}

    public static Polygon createSampleTriangle(double sideLength) {
        double centerX = sideLength / 2.0;
        double topY = sideLength * TOP_Y_RATIO;
        double bottomY = sideLength * BOTTOM_Y_RATIO;
        double halfWidth = sideLength * HALF_WIDTH_RATIO;

        Polygon triangle = new Polygon(
                centerX, topY,
                centerX - halfWidth, bottomY,
                centerX + halfWidth, bottomY
        );
        triangle.setFill(null);
        triangle.setStrokeWidth(STROKE_WIDTH);
        triangle.getStyleClass().add("shapex-primary-stroke");
        return triangle;
    }
}
