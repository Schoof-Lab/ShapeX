package qupath.ext.shapex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

class GeometryRoiResolverTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void solidPolygonNeedsOnlyOneCutContour() {
        ROI roi = roiWithHole(10.0, 0.0);

        assertEquals(1, GeometryRoiResolver.countDisconnectedComponents(roi));
        assertEquals(0, GeometryRoiResolver.countInteriorHoles(roi));
        assertFalse(GeometryRoiResolver.hasInteriorHoles(roi));
        assertEquals(1, GeometryRoiResolver.countClosedCutContours(roi));
        assertFalse(GeometryRoiResolver.requiresSingleCutResolution(roi));
    }

    @Test
    void polygonWithHoleNeedsResolutionDespiteBeingOneConnectedComponent() {
        ROI roi = roiWithHole(10.0, 6.0);

        assertEquals(1, GeometryRoiResolver.countDisconnectedComponents(roi));
        assertEquals(1, GeometryRoiResolver.countInteriorHoles(roi));
        assertTrue(GeometryRoiResolver.hasInteriorHoles(roi));
        assertEquals(2, GeometryRoiResolver.countClosedCutContours(roi));
        assertTrue(GeometryRoiResolver.requiresSingleCutResolution(roi));
    }

    @Test
    void disconnectedPolygonsNeedResolution() {
        Polygon first = GEOMETRY_FACTORY.createPolygon(squareRing(0.0, 0.0, 4.0));
        Polygon second = GEOMETRY_FACTORY.createPolygon(squareRing(6.0, 0.0, 4.0));
        MultiPolygon geometry = GEOMETRY_FACTORY.createMultiPolygon(new Polygon[] {first, second});
        ROI roi = GeometryTools.geometryToROI(geometry);

        assertEquals(2, GeometryRoiResolver.countDisconnectedComponents(roi));
        assertEquals(0, GeometryRoiResolver.countInteriorHoles(roi));
        assertFalse(GeometryRoiResolver.hasInteriorHoles(roi));
        assertEquals(2, GeometryRoiResolver.countClosedCutContours(roi));
        assertTrue(GeometryRoiResolver.requiresSingleCutResolution(roi));
    }

    @Test
    void disconnectedPolygonsWithHoleReportBothTopologyIssues() {
        Polygon polygonWithHole = polygonWithHole(0.0, 0.0, 10.0, 4.0);
        Polygon solidPolygon = GEOMETRY_FACTORY.createPolygon(squareRing(12.0, 0.0, 4.0));
        ROI roi = GeometryTools.geometryToROI(
                GEOMETRY_FACTORY.createMultiPolygon(new Polygon[] {polygonWithHole, solidPolygon})
        );

        assertEquals(2, GeometryRoiResolver.countDisconnectedComponents(roi));
        assertEquals(1, GeometryRoiResolver.countInteriorHoles(roi));
        assertTrue(GeometryRoiResolver.hasInteriorHoles(roi));
        assertEquals(3, GeometryRoiResolver.countClosedCutContours(roi));
        assertTrue(GeometryRoiResolver.requiresSingleCutResolution(roi));
    }

    @Test
    void mergeAndKeepProduceContinuousContoursForDisconnectedPolygons() {
        Polygon first = GEOMETRY_FACTORY.createPolygon(squareRing(0.0, 0.0, 4.0));
        Polygon second = GEOMETRY_FACTORY.createPolygon(squareRing(6.0, 0.0, 4.0));
        ROI roi = GeometryTools.geometryToROI(
                GEOMETRY_FACTORY.createMultiPolygon(new Polygon[] {first, second})
        );

        ROI merged = GeometryRoiResolver.createMergedComponentRoi(roi);
        List<ROI> components = GeometryRoiResolver.splitIntoComponentRois(roi);

        assertNotNull(merged);
        assertFalse(GeometryRoiResolver.requiresSingleCutResolution(merged));
        assertEquals(40.0, merged.getArea(), 1.0e-9);
        assertEquals(2, components.size());
        assertTrue(components.stream()
                .noneMatch(GeometryRoiResolver::requiresSingleCutResolution));
    }

    @Test
    void mergeResolvesDisconnectedPolygonsAndInteriorHoleInOneOperation() {
        Polygon polygonWithHole = polygonWithHole(0.0, 0.0, 10.0, 4.0);
        Polygon solidPolygon = GEOMETRY_FACTORY.createPolygon(squareRing(12.0, 0.0, 4.0));
        ROI source = GeometryTools.geometryToROI(
                GEOMETRY_FACTORY.createMultiPolygon(new Polygon[] {polygonWithHole, solidPolygon})
        );

        ROI merged = GeometryRoiResolver.createMergedComponentRoi(source);

        assertNotNull(merged);
        assertEquals(1, GeometryRoiResolver.countDisconnectedComponents(merged));
        assertEquals(0, GeometryRoiResolver.countInteriorHoles(merged));
        assertEquals(1, GeometryRoiResolver.countClosedCutContours(merged));
        assertTrue(merged.getGeometry().covers(source.getGeometry()));
    }

    @Test
    void automaticCleanupRemovesSmallHoleFromCutTopology() {
        ROI roi = roiWithHole(10.0, 2.0);

        var result = GeometryRoiResolver.cleanupSmallComponentsAndHoles(roi, 0.05);

        assertTrue(result.changed());
        assertEquals(1, result.filledHoleCount());
        assertFalse(GeometryRoiResolver.requiresSingleCutResolution(result.roi()));
    }

    @Test
    void automaticCleanupLeavesLargeHoleForExplicitResolution() {
        ROI roi = roiWithHole(10.0, 6.0);

        var result = GeometryRoiResolver.cleanupSmallComponentsAndHoles(roi, 0.05);

        assertFalse(result.changed());
        assertEquals(0, result.filledHoleCount());
        assertTrue(GeometryRoiResolver.requiresSingleCutResolution(result.roi()));
    }

    @Test
    void mergeFillsHoleAndProducesOneContinuousContour() {
        ROI roi = roiWithHole(10.0, 6.0);

        ROI merged = GeometryRoiResolver.createMergedComponentRoi(roi);

        assertNotNull(merged);
        assertFalse(GeometryRoiResolver.requiresSingleCutResolution(merged));
        assertEquals(100.0, merged.getArea(), 1.0e-9);
    }

    @Test
    void keepComponentsFillsHolesWithoutCreatingPositiveHoleShapes() {
        ROI roi = roiWithHole(10.0, 6.0);

        List<ROI> components = GeometryRoiResolver.splitIntoComponentRois(roi);

        assertEquals(1, components.size());
        assertFalse(GeometryRoiResolver.requiresSingleCutResolution(components.getFirst()));
        assertEquals(100.0, components.getFirst().getArea(), 1.0e-9);
    }

    private static ROI roiWithHole(double outerSize, double holeSize) {
        return GeometryTools.geometryToROI(polygonWithHole(0.0, 0.0, outerSize, holeSize));
    }

    private static Polygon polygonWithHole(
            double x,
            double y,
            double outerSize,
            double holeSize
    ) {
        LinearRing shell = squareRing(x, y, outerSize);
        LinearRing[] holes = holeSize > 0.0
                ? new LinearRing[] {
                        squareRing(
                                x + (outerSize - holeSize) / 2.0,
                                y + (outerSize - holeSize) / 2.0,
                                holeSize
                        )
                }
                : new LinearRing[0];
        return GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    private static LinearRing squareRing(double x, double y, double size) {
        return GEOMETRY_FACTORY.createLinearRing(new Coordinate[] {
                new Coordinate(x, y),
                new Coordinate(x + size, y),
                new Coordinate(x + size, y + size),
                new Coordinate(x, y + size),
                new Coordinate(x, y)
        });
    }
}
