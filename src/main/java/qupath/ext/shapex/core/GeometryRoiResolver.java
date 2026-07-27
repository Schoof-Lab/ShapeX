package qupath.ext.shapex.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.locationtech.jts.algorithm.hull.ConcaveHullOfPolygons;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;

import qupath.lib.geom.Point2;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

public final class GeometryRoiResolver {

    private GeometryRoiResolver() {}
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    public record AutomaticCleanupResult(
            ROI roi,
            Geometry originalGeometry,
            Geometry correctedGeometry,
            Geometry removedGeometry,
            Geometry filledGeometry,
            int originalComponentCount,
            int removedComponentCount,
            int remainingComponentCount,
            int filledHoleCount
    ) {
        public boolean changed() {
            return hasPositiveArea(removedGeometry) || hasPositiveArea(filledGeometry);
        }
    }

    public static boolean isGeometryRoi(ROI roi) {
        if (roi == null) {
            return false;
        }

        String roiName = roi.getRoiName() != null
                ? roi.getRoiName().trim().toLowerCase(Locale.ROOT)
                : "";
        String className = roi.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return "geometry".equals(roiName) || className.contains("geometry");
    }

    public static ROI createMergedComponentRoi(ROI roi) {
        if (roi == null || !roi.isArea()) {
            return null;
        }

        List<List<Point2>> contours = extractClosedContours(roi.getGeometry());
        List<Point2> merged = mergePointSetsForSingleCut(contours);
        if (merged.size() < 3) {
            return null;
        }

        return ROIs.createPolygonROI(merged, roi.getImagePlane());
    }

    public static List<ROI> splitIntoComponentRois(ROI roi) {
        if (roi == null || !roi.isArea()) {
            return List.of();
        }

        List<Polygon> polygons = extractPolygonalPolygons(repairGeometry(roi.getGeometry()));
        List<ROI> out = new ArrayList<>();
        for (Polygon polygon : polygons) {
            List<Point2> points = toPointList(polygon.getExteriorRing().getCoordinates());
            if (points.size() >= 3) {
                out.add(ROIs.createPolygonROI(points, roi.getImagePlane()));
            }
        }
        return out;
    }

    public static AutomaticCleanupResult cleanupSmallComponentsAndHoles(
            ROI roi,
            double maxAreaFraction
    ) {
        if (roi == null || !roi.isArea()) {
            return unchangedCleanupResult(roi, null, 0);
        }

        Geometry roiGeometry = roi.getGeometry();
        Geometry originalGeometry = roiGeometry == null ? null : roiGeometry.copy();
        Geometry workingGeometry = repairGeometry(roiGeometry);
        List<Polygon> components = extractPolygonalPolygons(workingGeometry);
        if (components.isEmpty()) {
            return unchangedCleanupResult(roi, originalGeometry, 0);
        }

        if (!Double.isFinite(maxAreaFraction)) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        double largestArea = components.stream()
                .mapToDouble(Polygon::getArea)
                .max()
                .orElse(0.0);
        if (!Double.isFinite(largestArea) || largestArea <= 0.0) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        double areaFraction = Math.max(0.0, maxAreaFraction);
        double componentThreshold = largestArea * areaFraction;
        List<Polygon> kept = components.stream()
                .filter(component -> component.getArea() > componentThreshold)
                .toList();
        int removedCount = components.size() - kept.size();
        if (kept.isEmpty()) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        double retainedAnnotationArea = kept.stream()
                .mapToDouble(Polygon::getArea)
                .sum();
        if (!Double.isFinite(retainedAnnotationArea) || retainedAnnotationArea <= 0.0) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        // Freeze the net-area denominator so every hole is evaluated independently.
        double holeThreshold = retainedAnnotationArea * areaFraction;
        if (!Double.isFinite(holeThreshold)) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        HoleFillResult holeFillResult = fillSmallHoles(kept, holeThreshold);
        if (removedCount == 0 && holeFillResult.filledHoleCount() == 0) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        ROI correctedRoi = createRoiFromPolygons(holeFillResult.polygons(), roi);
        if (correctedRoi == null) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        Geometry correctedGeometry = repairGeometry(correctedRoi.getGeometry());
        if (correctedGeometry == null || correctedGeometry.isEmpty()) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        GeometryDifferenceResult differences = calculateDifferences(workingGeometry, correctedGeometry);
        if (differences == null || !differences.changed()) {
            return unchangedCleanupResult(roi, originalGeometry, components.size());
        }

        return new AutomaticCleanupResult(
                correctedRoi,
                originalGeometry,
                correctedGeometry,
                differences.removedGeometry(),
                differences.filledGeometry(),
                components.size(),
                removedCount,
                kept.size(),
                holeFillResult.filledHoleCount()
        );
    }

    private record HoleFillResult(List<Polygon> polygons, int filledHoleCount) {}

    private static HoleFillResult fillSmallHoles(
            List<Polygon> polygons,
            double maxHoleArea
    ) {
        List<Polygon> corrected = new ArrayList<>(polygons.size());
        int filledHoleCount = 0;

        for (Polygon polygon : polygons) {
            List<LinearRing> retainedHoles = new ArrayList<>(polygon.getNumInteriorRing());
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                LinearRing hole = polygon.getInteriorRingN(i);
                try {
                    double holeArea = polygon.getFactory()
                            .createPolygon((LinearRing) hole.copy())
                            .getArea();
                    if (Double.isFinite(holeArea) && holeArea <= maxHoleArea) {
                        filledHoleCount++;
                    } else {
                        retainedHoles.add((LinearRing) hole.copy());
                    }
                } catch (RuntimeException e) {
                    retainedHoles.add((LinearRing) hole.copy());
                }
            }

            if (retainedHoles.size() == polygon.getNumInteriorRing()) {
                corrected.add(polygon);
                continue;
            }

            try {
                corrected.add(polygon.getFactory().createPolygon(
                        (LinearRing) polygon.getExteriorRing().copy(),
                        retainedHoles.toArray(LinearRing[]::new)
                ));
            } catch (RuntimeException e) {
                corrected.add(polygon);
                filledHoleCount -= polygon.getNumInteriorRing() - retainedHoles.size();
            }
        }

        return new HoleFillResult(corrected, filledHoleCount);
    }

    private static AutomaticCleanupResult unchangedCleanupResult(
            ROI roi,
            Geometry geometry,
            int componentCount
    ) {
        Geometry originalGeometry = geometry != null
                ? geometry
                : roi == null || roi.getGeometry() == null ? null : roi.getGeometry().copy();
        Geometry empty = createEmptyGeometry(originalGeometry);
        return new AutomaticCleanupResult(
                roi,
                originalGeometry,
                originalGeometry,
                empty,
                empty,
                componentCount,
                0,
                componentCount,
                0
        );
    }

    private record GeometryDifferenceResult(Geometry removedGeometry, Geometry filledGeometry) {
        boolean changed() {
            return hasPositiveArea(removedGeometry) || hasPositiveArea(filledGeometry);
        }
    }

    private static GeometryDifferenceResult calculateDifferences(
            Geometry originalGeometry,
            Geometry correctedGeometry
    ) {
        if (originalGeometry == null || correctedGeometry == null
                || originalGeometry.isEmpty() || correctedGeometry.isEmpty()) {
            return null;
        }

        try {
            Geometry removedGeometry = OverlayNGRobust.overlay(
                    originalGeometry,
                    correctedGeometry,
                    OverlayNG.DIFFERENCE
            );
            Geometry filledGeometry = OverlayNGRobust.overlay(
                    correctedGeometry,
                    originalGeometry,
                    OverlayNG.DIFFERENCE
            );
            if (removedGeometry == null || filledGeometry == null) {
                return null;
            }
            return new GeometryDifferenceResult(removedGeometry, filledGeometry);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasPositiveArea(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return false;
        }
        double area = geometry.getArea();
        return Double.isFinite(area) && area > 0.0;
    }

    private static Geometry createEmptyGeometry(Geometry reference) {
        GeometryFactory factory = reference != null ? reference.getFactory() : GEOMETRY_FACTORY;
        return factory.createGeometryCollection();
    }

    public static int countDisconnectedComponents(ROI roi) {
        if (roi == null || !roi.isArea()) {
            return 0;
        }
        return extractPolygonalPolygons(repairGeometry(roi.getGeometry())).size();
    }

    /**
     * Count the interior holes across all polygonal components of an area ROI.
     *
     * @param roi the area ROI to inspect
     * @return the total number of interior holes, or {@code 0} for a non-area ROI
     */
    public static int countInteriorHoles(ROI roi) {
        if (roi == null || !roi.isArea()) {
            return 0;
        }

        return extractPolygonalPolygons(repairGeometry(roi.getGeometry())).stream()
                .mapToInt(Polygon::getNumInteriorRing)
                .sum();
    }

    /**
     * Determine whether any polygonal component of an area ROI has an interior hole.
     *
     * @param roi the area ROI to inspect
     * @return {@code true} when the ROI contains at least one interior hole
     */
    public static boolean hasInteriorHoles(ROI roi) {
        return countInteriorHoles(roi) > 0;
    }

    /**
     * Count the closed contours that would be required to trace an area ROI exactly.
     * Each polygon contributes one exterior contour and one additional contour for
     * every interior hole.
     *
     * @param roi the area ROI to inspect
     * @return the number of closed cut contours, or {@code 0} for a non-area ROI
     */
    public static int countClosedCutContours(ROI roi) {
        if (roi == null || !roi.isArea()) {
            return 0;
        }

        int contourCount = 0;
        for (Polygon polygon : extractPolygonalPolygons(repairGeometry(roi.getGeometry()))) {
            contourCount += 1 + polygon.getNumInteriorRing();
        }
        return contourCount;
    }

    /**
     * Determine whether an area ROI needs to be resolved before it can be represented
     * by one continuous closed cutting path.
     *
     * @param roi the area ROI to inspect
     * @return {@code true} when the ROI has disconnected components or interior holes
     */
    public static boolean requiresSingleCutResolution(ROI roi) {
        return countClosedCutContours(roi) > 1;
    }

    public static Geometry prepareGeometryForPreview(Geometry geometry) {
        return repairGeometry(geometry);
    }

    public static List<Polygon> extractPolygonalPolygons(Geometry geometry) {
        List<Polygon> out = new ArrayList<>();
        collectPolygonalPolygons(geometry, out);
        return out;
    }

    static List<List<Point2>> extractClosedContours(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return List.of();
        }

        List<List<Point2>> originalContours = new ArrayList<>();
        collectClosedContours(geometry, originalContours);

        Geometry fixed = repairGeometry(geometry);
        if (fixed == geometry) {
            return originalContours;
        }

        List<List<Point2>> contours = new ArrayList<>();
        collectClosedContours(fixed, contours);
        if (contours.size() > 1 && originalContours.size() == 1) {
            return originalContours;
        }
        return contours.isEmpty() ? originalContours : contours;
    }

    static List<Point2> mergePointSetsForSingleCut(List<List<Point2>> pointSets) {
        if (pointSets == null || pointSets.isEmpty()) {
            return List.of();
        }

        List<List<Point2>> contours = new ArrayList<>();
        for (List<Point2> pointSet : pointSets) {
            if (pointSet != null && pointSet.size() >= 3) {
                contours.add(new ArrayList<>(pointSet));
            }
        }
        if (contours.isEmpty()) {
            return List.of();
        }
        if (contours.size() == 1) {
            return contours.get(0);
        }

        return createEnclosingHullPointSet(contours);
    }

    private static Geometry repairGeometry(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return geometry;
        }

        try {
            Geometry repaired = geometry.buffer(0);
            if (repaired != null && !repaired.isEmpty()) {
                return repaired;
            }
        } catch (Exception ignored) {}

        return geometry;
    }

    private static void collectPolygonalPolygons(Geometry geometry, List<Polygon> out) {
        if (geometry == null || geometry.isEmpty()) {
            return;
        }

        if (geometry instanceof Polygon polygon && polygon.getArea() > 0) {
            out.add(polygon);
            return;
        }

        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            collectPolygonalPolygons(geometry.getGeometryN(i), out);
        }
    }

    private static void collectClosedContours(
            Geometry geometry,
            List<List<Point2>> out
    ) {
        if (geometry == null || geometry.isEmpty()) {
            return;
        }

        if (geometry instanceof Polygon polygon) {
            List<Point2> points = toPointList(polygon.getExteriorRing().getCoordinates());
            if (points.size() >= 3) {
                out.add(points);
            }
            return;
        }

        if (geometry instanceof GeometryCollection collection) {
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                collectClosedContours(collection.getGeometryN(i), out);
            }
            return;
        }

        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            collectClosedContours(geometry.getGeometryN(i), out);
        }
    }

    private static List<Point2> createEnclosingHullPointSet(List<List<Point2>> contours) {
        Geometry connectedHull = createConnectedComponentHull(contours);
        List<Point2> hullPoints = toExteriorPointList(connectedHull);
        if (hullPoints.size() >= 3) {
            return hullPoints;
        }

        return toExteriorPointList(createPointConvexHull(contours));
    }

    private static Geometry createConnectedComponentHull(List<List<Point2>> contours) {
        Geometry polygonalGeometry = createPolygonalGeometry(contours);
        if (polygonalGeometry == null || polygonalGeometry.isEmpty()) {
            return null;
        }

        try {
            Geometry hull = ConcaveHullOfPolygons.concaveHullByLengthRatio(
                    polygonalGeometry,
                    1.0,
                    true,
                    false
            );
            if (hull != null && !hull.isEmpty()) {
                return hull;
            }
        } catch (RuntimeException ignored) {}

        return null;
    }

    private static Geometry createPolygonalGeometry(List<List<Point2>> contours) {
        List<Geometry> polygons = new ArrayList<>();
        for (List<Point2> contour : contours) {
            Coordinate[] coordinates = toClosedCoordinates(contour);
            if (coordinates.length < 4) {
                continue;
            }

            try {
                Geometry polygon = repairGeometry(GEOMETRY_FACTORY.createPolygon(coordinates));
                if (polygon != null && !polygon.isEmpty()) {
                    polygons.add(polygon);
                }
            } catch (RuntimeException ignored) {}
        }

        if (polygons.isEmpty()) {
            return null;
        }

        Geometry geometry = GEOMETRY_FACTORY.buildGeometry(polygons);
        try {
            Geometry union = geometry.union();
            if (union != null && !union.isEmpty()) {
                return union;
            }
        } catch (RuntimeException ignored) {}

        return repairGeometry(geometry);
    }

    private static ROI createRoiFromPolygons(List<Polygon> polygons, ROI sourceRoi) {
        if (polygons == null || polygons.isEmpty() || sourceRoi == null) {
            return null;
        }

        GeometryFactory factory = polygons.get(0).getFactory();
        List<Geometry> geometries = new ArrayList<>(polygons);
        Geometry geometry = geometries.size() == 1
                ? geometries.get(0)
                : factory.buildGeometry(geometries);
        try {
            Geometry union = geometry.union();
            if (union != null && !union.isEmpty()) {
                geometry = union;
            }
        } catch (RuntimeException ignored) {}

        geometry = repairGeometry(geometry);
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }

        return GeometryTools.geometryToROI(geometry, sourceRoi.getImagePlane());
    }

    private static Geometry createPointConvexHull(List<List<Point2>> contours) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (List<Point2> contour : contours) {
            for (Point2 point : contour) {
                coordinates.add(new Coordinate(point.getX(), point.getY()));
            }
        }

        if (coordinates.size() < 3) {
            return null;
        }

        return GEOMETRY_FACTORY
                .createMultiPointFromCoords(coordinates.toArray(Coordinate[]::new))
                .convexHull();
    }

    private static List<Point2> toExteriorPointList(Geometry geometry) {
        List<Polygon> polygons = extractPolygonalPolygons(repairGeometry(geometry));
        if (polygons.isEmpty()) {
            return List.of();
        }

        Polygon largest = polygons.stream()
                .max(java.util.Comparator.comparingDouble(Polygon::getArea))
                .orElse(null);
        if (largest == null) {
            return List.of();
        }

        return toPointList(largest.getExteriorRing().getCoordinates());
    }

    private static Coordinate[] toClosedCoordinates(List<Point2> points) {
        if (points == null || points.size() < 3) {
            return new Coordinate[0];
        }

        List<Coordinate> coordinates = new ArrayList<>(points.size() + 1);
        for (Point2 point : points) {
            coordinates.add(new Coordinate(point.getX(), point.getY()));
        }

        Coordinate first = coordinates.get(0);
        Coordinate last = coordinates.get(coordinates.size() - 1);
        if (!first.equals2D(last)) {
            coordinates.add(new Coordinate(first));
        }

        return coordinates.toArray(Coordinate[]::new);
    }

    private static List<Point2> toPointList(Coordinate[] coordinates) {
        List<Point2> out = new ArrayList<>();
        if (coordinates == null || coordinates.length == 0) {
            return out;
        }

        int limit = coordinates.length;
        if (limit > 1 && coordinates[0].equals2D(coordinates[limit - 1])) {
            limit--;
        }

        for (int i = 0; i < limit; i++) {
            out.add(new Point2(coordinates[i].x, coordinates[i].y));
        }

        return out;
    }
}
