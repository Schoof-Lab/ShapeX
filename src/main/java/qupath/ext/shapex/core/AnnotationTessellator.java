package qupath.ext.shapex.core;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.ext.shapex.ShapeXResources;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AnnotationTessellator {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double DEFAULT_CELL_AREA_PX2 = 10_000.0;
    private static final double MIN_AREA = 1e-3;
    private static final String TRIANGLE_SUFFIX = "_Tri";
    private static final String SQUARE_SUFFIX = "_Squ";
    private static final String HEXAGON_SUFFIX = "_Hex";

    private AnnotationTessellator() {}

    public enum TilingShape {
        TRIANGLE,
        SQUARE,
        HEXAGON
    }

    public enum PartialPolygonMode {
        FIT_SHAPE,
        REMOVE_PARTIAL,
        FORCE_PARTIAL
    }

    public record TessellationResult(int sourceAnnotations, int createdAnnotations) {}
    public record TessellationBuildResult(int sourceAnnotations, List<PathObject> generatedAnnotations) {}

    @FunctionalInterface
    public interface PolygonCreationListener {
        void onPolygonCreated(int createdCount);
    }

    public static boolean isGeneratedTessellationAnnotation(PathObject obj) {
        if (obj == null) {
            return false;
        }
        String name = obj.getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.contains(TRIANGLE_SUFFIX)
                || name.contains(SQUARE_SUFFIX)
                || name.contains(HEXAGON_SUFFIX);
    }

    public static TessellationResult applyTessellation(
            TilingShape shape,
            Set<String> classNames,
            PartialPolygonMode partialMode,
            double cellAreaPx2,
            double orientationDegrees,
            boolean replaceOriginal
    ) {

        var imageData = QuPathGUI.getInstance().getImageData();
        if (imageData == null) {
            throw new IllegalStateException(ShapeXResources.get("error.image.none.open"));
        }

        return applyTessellation(
                imageData,
                resolveCurrentSlideName(imageData),
                classNames,
                false,
                shape,
                partialMode,
                cellAreaPx2,
                orientationDegrees,
                replaceOriginal
        );
    }

    public static TessellationResult applyTessellation(
            ImageData<BufferedImage> imageData,
            String slideName,
            Set<String> sampleKeys,
            boolean includeUnclassified,
            TilingShape shape,
            PartialPolygonMode partialMode,
            double cellAreaPx2,
            double orientationDegrees,
            boolean replaceOriginal
    ) {
        if (imageData == null) {
            throw new IllegalStateException(ShapeXResources.get("error.image.none.open"));
        }

        var hierarchy = imageData.getHierarchy();
        var allAnnotations = hierarchy.getAnnotationObjects();
        if (allAnnotations.isEmpty()) {
            return new TessellationResult(0, 0);
        }

        Set<String> sampleFilter = sampleKeys == null ? Set.of() : new HashSet<>(sampleKeys);

        List<PathObject> sourceAnnotations = allAnnotations.stream()
                .filter(obj -> obj.getROI() != null && obj.getROI().isArea())
                .filter(obj -> {
                    if (sampleFilter.isEmpty())
                        return true;
                    String sampleKey = AnnotationUtils.getSampleKey(obj, slideName, includeUnclassified);
                    return sampleKey != null && sampleFilter.contains(sampleKey);
                })
                .toList();

        if (sourceAnnotations.isEmpty()) {
            return new TessellationResult(0, 0);
        }

        return applyTessellation(
                imageData,
                sourceAnnotations,
                shape,
                partialMode,
                cellAreaPx2,
                orientationDegrees,
                replaceOriginal
        );
    }

    public static TessellationResult applyTessellation(
            ImageData<BufferedImage> imageData,
            Collection<PathObject> sourceAnnotations,
            TilingShape shape,
            PartialPolygonMode partialMode,
            double cellAreaPx2,
            double orientationDegrees,
            boolean replaceOriginal
    ) {
        if (imageData == null) {
            throw new IllegalStateException(ShapeXResources.get("error.image.none.open"));
        }

        if (sourceAnnotations == null || sourceAnnotations.isEmpty()) {
            return new TessellationResult(0, 0);
        }

        List<PathObject> filteredSources = sourceAnnotations.stream()
                .filter(obj -> obj != null && obj.getROI() != null && obj.getROI().isArea())
                .toList();

        if (filteredSources.isEmpty()) {
            return new TessellationResult(0, 0);
        }

        TessellationBuildResult build = buildTessellation(
                filteredSources,
                shape,
                partialMode,
                cellAreaPx2,
                orientationDegrees
        );
        List<PathObject> generatedAnnotations = build.generatedAnnotations();

        if (generatedAnnotations.isEmpty()) {
            return new TessellationResult(filteredSources.size(), 0);
        }

        var hierarchy = imageData.getHierarchy();
        if (replaceOriginal) {
            hierarchy.removeObjects(filteredSources, true);
        }

        hierarchy.addObjects(generatedAnnotations);

        return new TessellationResult(filteredSources.size(), generatedAnnotations.size());
    }

    public static TessellationBuildResult buildTessellation(
            Collection<PathObject> sourceAnnotations,
            TilingShape shape,
            PartialPolygonMode partialMode,
            double cellAreaPx2,
            double orientationDegrees
    ) {
        return buildTessellation(
                sourceAnnotations,
                shape,
                partialMode,
                cellAreaPx2,
                orientationDegrees,
                0.0,
                0.0,
                false,
                null
        );
    }

    public static TessellationBuildResult buildTessellation(
            Collection<PathObject> sourceAnnotations,
            TilingShape shape,
            PartialPolygonMode partialMode,
            double cellAreaPx2,
            double orientationDegrees,
            double gridOffsetX,
            double gridOffsetY,
            PolygonCreationListener listener
    ) {
        return buildTessellation(
                sourceAnnotations,
                shape,
                partialMode,
                cellAreaPx2,
                orientationDegrees,
                gridOffsetX,
                gridOffsetY,
                false,
                listener
        );
    }

    public static TessellationBuildResult buildTessellation(
            Collection<PathObject> sourceAnnotations,
            TilingShape shape,
            PartialPolygonMode partialMode,
            double cellAreaPx2,
            double orientationDegrees,
            double gridOffsetX,
            double gridOffsetY,
            boolean assignClasses,
            PolygonCreationListener listener
    ) {
        if (sourceAnnotations == null || sourceAnnotations.isEmpty()) {
            return new TessellationBuildResult(0, List.of());
        }

        List<PathObject> filteredSources = sourceAnnotations.stream()
                .filter(obj -> obj != null && obj.getROI() != null && obj.getROI().isArea())
                .toList();

        if (filteredSources.isEmpty()) {
            return new TessellationBuildResult(0, List.of());
        }

        List<PathObject> generatedAnnotations = new ArrayList<>();
        int[] createdCounter = new int[]{0};

        for (PathObject source : filteredSources) {
            ROI roi = source.getROI();
            Geometry sourceGeometry = roi.getGeometry();
            if (sourceGeometry == null || sourceGeometry.isEmpty()) {
                continue;
            }

            Geometry fixedGeometry = sourceGeometry.buffer(0);
            if (fixedGeometry == null || fixedGeometry.isEmpty()) {
                continue;
            }

            var generatedCells = generateCellsForAnnotation(
                    fixedGeometry,
                    roi,
                    source.getName(),
                    source.getPathClass(),
                    shape,
                    partialMode == null ? PartialPolygonMode.FIT_SHAPE : partialMode,
                    cellAreaPx2 > 0.0 ? cellAreaPx2 : DEFAULT_CELL_AREA_PX2,
                    orientationDegrees,
                    gridOffsetX,
                    gridOffsetY,
                    assignClasses,
                    listener,
                    createdCounter
            );

            generatedAnnotations.addAll(generatedCells);
        }

        return new TessellationBuildResult(filteredSources.size(), generatedAnnotations);
    }

    private static String resolveCurrentSlideName(ImageData<BufferedImage> imageData) {
        var gui = QuPathGUI.getInstance();
        var project = gui.getProject();
        if (project != null && imageData != null) {
            var entry = project.getEntry(imageData);
            if (entry != null) {
                return entry.getImageName();
            }
        }
        return ShapeXResources.get("common.current");
    }

    private static Collection<PathObject> generateCellsForAnnotation(
            Geometry sourceGeometry,
            ROI sourceROI,
            String sourceName,
            PathClass pathClass,
            TilingShape shape,
            PartialPolygonMode partialMode,
            double cellAreaPx2,
            double orientationDegrees,
            double gridOffsetX,
            double gridOffsetY,
            boolean assignClasses,
            PolygonCreationListener listener,
            int[] createdCounter
    ) {

        List<PathObject> created = new ArrayList<>();

        Envelope envelope = sourceGeometry.getEnvelopeInternal();
        double sideLength = computeSideLength(shape, cellAreaPx2);
        double centerX = envelope.centre().x;
        double centerY = envelope.centre().y;

        double halfWidth = Math.max(0.0, envelope.getWidth() / 2.0);
        double halfHeight = Math.max(0.0, envelope.getHeight() / 2.0);
        double radians = Math.toRadians(orientationDegrees);
        double absCos = Math.abs(Math.cos(radians));
        double absSin = Math.abs(Math.sin(radians));
        double rotatedHalfWidth = absCos * halfWidth + absSin * halfHeight;
        double rotatedHalfHeight = absSin * halfWidth + absCos * halfHeight;
        double pad = sideLength * 3.0;

        double minX = centerX - rotatedHalfWidth - pad;
        double maxX = centerX + rotatedHalfWidth + pad;
        double minY = centerY - rotatedHalfHeight - pad;
        double maxY = centerY + rotatedHalfHeight + pad;

        PreparedGeometry preparedSource = PreparedGeometryFactory.prepare(sourceGeometry);
        AffineTransformation rotation = AffineTransformation.rotationInstance(
                radians,
                centerX,
                centerY
        );
        AffineTransformation translation = AffineTransformation.translationInstance(
                gridOffsetX,
                gridOffsetY
        );

        for (Geometry cell : generateLattice(shape, sideLength, minX, maxX, minY, maxY)) {
            Geometry rotatedCell = rotation.transform(cell);
            if (gridOffsetX != 0.0 || gridOffsetY != 0.0) {
                rotatedCell = translation.transform(rotatedCell);
            }
            try {
                switch (partialMode) {
                    case FIT_SHAPE -> {
                        Geometry intersection = rotatedCell.intersection(sourceGeometry);
                        if (intersection != null && !intersection.isEmpty()) {
                            appendGeometryAsAnnotations(
                                    created,
                                    intersection,
                                    sourceROI,
                                    sourceName,
                                    pathClass,
                                    shape,
                                    assignClasses,
                                    listener,
                                    createdCounter
                            );
                        }
                    }
                    case REMOVE_PARTIAL -> {
                        if (preparedSource.covers(rotatedCell)) {
                            appendGeometryAsAnnotations(
                                    created,
                                    rotatedCell,
                                    sourceROI,
                                    sourceName,
                                    pathClass,
                                    shape,
                                    assignClasses,
                                    listener,
                                    createdCounter
                            );
                        }
                    }
                    case FORCE_PARTIAL -> {
                        if (preparedSource.intersects(rotatedCell)) {
                            appendGeometryAsAnnotations(
                                    created,
                                    rotatedCell,
                                    sourceROI,
                                    sourceName,
                                    pathClass,
                                    shape,
                                    assignClasses,
                                    listener,
                                    createdCounter
                            );
                        }
                    }
                }
            } catch (Exception ignored) {
                continue;
            }
        }

        return created;
    }
    private static void appendGeometryAsAnnotations(
            List<PathObject> created,
            Geometry geometry,
            ROI sourceROI,
            String sourceName,
            PathClass sourcePathClass,
            TilingShape shape,
            boolean assignClasses,
            PolygonCreationListener listener,
            int[] createdCounter
    ) {
        String generatedName = buildGeneratedAnnotationName(sourceName, sourcePathClass, shape);
        for (Geometry polygon : extractPolygonalComponents(geometry)) {
            if (polygon == null || polygon.isEmpty() || polygon.getArea() <= MIN_AREA) {
                continue;
            }

            ROI clippedRoi = GeometryTools.geometryToROI(polygon, sourceROI.getImagePlane());
            int generatedIndex = createdCounter[0] + 1;
            PathClass generatedPathClass = assignClasses
                    ? getGeneratedTessellationPathClass(sourceName, sourcePathClass, shape, generatedIndex)
                    : null;
            PathObject obj = PathObjects.createAnnotationObject(clippedRoi, generatedPathClass);
            obj.setName(generatedName);
            created.add(obj);
            createdCounter[0] = generatedIndex;
            if (listener != null) {
                listener.onPolygonCreated(createdCounter[0]);
            }
        }
    }

    public static PathClass getGeneratedTessellationPathClass(
            String sourceName,
            PathClass pathClass,
            TilingShape shape,
            int generatedIndex
    ) {
        return PathClass.fromString(
                getGeneratedTessellationClassName(sourceName, pathClass, shape, generatedIndex)
        );
    }

    public static String getGeneratedTessellationClassName(
            String sourceName,
            PathClass pathClass,
            TilingShape shape,
            int generatedIndex
    ) {
        return buildGeneratedAnnotationName(sourceName, pathClass, shape) + "_" + Math.max(1, generatedIndex);
    }

    public static String getGeneratedTessellationAnnotationName(
            String sourceName,
            PathClass pathClass,
            TilingShape shape
    ) {
        return buildGeneratedAnnotationName(sourceName, pathClass, shape);
    }

    private static String buildGeneratedAnnotationName(
            String sourceName,
            PathClass pathClass,
            TilingShape shape
    ) {
        String baseName = sourceName == null ? "" : sourceName.trim();
        if (baseName.isBlank() && pathClass != null && pathClass.getName() != null) {
            baseName = pathClass.getName().trim();
        }
        if (baseName.isBlank()) {
            baseName = ShapeXResources.get("annotation.unnamed");
        }
        return baseName + getGeneratedNameSuffix(shape);
    }

    private static String getGeneratedNameSuffix(TilingShape shape) {
        return switch (shape) {
            case TRIANGLE -> TRIANGLE_SUFFIX;
            case SQUARE -> SQUARE_SUFFIX;
            case HEXAGON -> HEXAGON_SUFFIX;
        };
    }

    private static List<Geometry> generateLattice(
            TilingShape shape,
            double sideLength,
            double minX,
            double maxX,
            double minY,
            double maxY
    ) {
        return switch (shape) {
            case TRIANGLE -> generateTriangleLattice(sideLength, minX, maxX, minY, maxY);
            case SQUARE -> generateSquareLattice(sideLength, minX, maxX, minY, maxY);
            case HEXAGON -> generateHexagonLattice(sideLength, minX, maxX, minY, maxY);
        };
    }

    private static List<Geometry> generateSquareLattice(
            double sideLength,
            double minX,
            double maxX,
            double minY,
            double maxY
    ) {
        List<Geometry> out = new ArrayList<>();
        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double halfWidth = Math.max(0.0, (maxX - minX) / 2.0);
        double halfHeight = Math.max(0.0, (maxY - minY) / 2.0);
        int nx = (int) Math.ceil(halfWidth / sideLength) + 3;
        int ny = (int) Math.ceil(halfHeight / sideLength) + 3;
        double half = sideLength / 2.0;

        for (int ix = -nx; ix <= nx; ix++) {
            double cx = centerX + ix * sideLength;
            for (int iy = -ny; iy <= ny; iy++) {
                double cy = centerY + iy * sideLength;
                if (!boundsOverlap(
                        cx - half, cx + half, cy - half, cy + half,
                        minX, maxX, minY, maxY
                )) {
                    continue;
                }
                Coordinate[] coords = new Coordinate[]{
                        new Coordinate(cx - half, cy - half),
                        new Coordinate(cx + half, cy - half),
                        new Coordinate(cx + half, cy + half),
                        new Coordinate(cx - half, cy + half),
                        new Coordinate(cx - half, cy - half)
                };
                out.add(GEOMETRY_FACTORY.createPolygon(coords));
            }
        }

        return out;
    }

    private static List<Geometry> generateHexagonLattice(
            double sideLength,
            double minX,
            double maxX,
            double minY,
            double maxY
    ) {
        List<Geometry> out = new ArrayList<>();

        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double halfWidth = Math.max(0.0, (maxX - minX) / 2.0);
        double halfHeight = Math.max(0.0, (maxY - minY) / 2.0);
        double dx = 1.5 * sideLength;
        double dy = Math.sqrt(3.0) * sideLength;
        double hexHalfHeight = (Math.sqrt(3.0) / 2.0) * sideLength;
        int nx = (int) Math.ceil(halfWidth / dx) + 4;
        int ny = (int) Math.ceil(halfHeight / dy) + 4;

        for (int ix = -nx; ix <= nx; ix++) {
            double cx = centerX + ix * dx;
            double yOffset = (Math.abs(ix) % 2 == 0) ? 0.0 : dy / 2.0;
            for (int iy = -ny; iy <= ny; iy++) {
                double cy = centerY + iy * dy + yOffset;
                if (!boundsOverlap(
                        cx - sideLength, cx + sideLength, cy - hexHalfHeight, cy + hexHalfHeight,
                        minX, maxX, minY, maxY
                )) {
                    continue;
                }
                out.add(createRegularPolygon(cx, cy, sideLength, 6, 0.0));
            }
        }

        return out;
    }

    private static List<Geometry> generateTriangleLattice(
            double sideLength,
            double minX,
            double maxX,
            double minY,
            double maxY
    ) {
        List<Geometry> out = new ArrayList<>();

        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double halfWidth = Math.max(0.0, (maxX - minX) / 2.0);
        double halfHeight = Math.max(0.0, (maxY - minY) / 2.0);
        double half = sideLength / 2.0;
        double height = Math.sqrt(3.0) * half;

        int nx = (int) Math.ceil(halfWidth / sideLength) + 6;
        int ny = (int) Math.ceil(halfHeight / height) + 6;

        // Keep the same phase anchor as the preview grid.
        double originX = centerX - sideLength;
        double originY = centerY - (2.0 * height / 3.0);

        for (int j = -ny; j <= ny; j++) {
            for (int i = -nx; i <= nx; i++) {
                double p00x = originX + i * sideLength + j * half;
                double p00y = originY + j * height;

                double p10x = p00x + sideLength;
                double p10y = p00y;

                double p01x = p00x + half;
                double p01y = p00y + height;

                double p11x = p00x + 1.5 * sideLength;
                double p11y = p00y + height;

                if (triangleOverlapsBounds(p00x, p00y, p10x, p10y, p01x, p01y, minX, maxX, minY, maxY)) {
                    out.add(GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                            new Coordinate(p00x, p00y),
                            new Coordinate(p10x, p10y),
                            new Coordinate(p01x, p01y),
                            new Coordinate(p00x, p00y)
                    }));
                }
                if (triangleOverlapsBounds(p10x, p10y, p11x, p11y, p01x, p01y, minX, maxX, minY, maxY)) {
                    out.add(GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                            new Coordinate(p10x, p10y),
                            new Coordinate(p11x, p11y),
                            new Coordinate(p01x, p01y),
                            new Coordinate(p10x, p10y)
                    }));
                }
            }
        }

        return out;
    }

    private static boolean triangleOverlapsBounds(
            double x1, double y1,
            double x2, double y2,
            double x3, double y3,
            double minX,
            double maxX,
            double minY,
            double maxY
    ) {
        double triMinX = Math.min(x1, Math.min(x2, x3));
        double triMaxX = Math.max(x1, Math.max(x2, x3));
        double triMinY = Math.min(y1, Math.min(y2, y3));
        double triMaxY = Math.max(y1, Math.max(y2, y3));
        return boundsOverlap(triMinX, triMaxX, triMinY, triMaxY, minX, maxX, minY, maxY);
    }

    private static boolean boundsOverlap(
            double minAx,
            double maxAx,
            double minAy,
            double maxAy,
            double minBx,
            double maxBx,
            double minBy,
            double maxBy
    ) {
        return maxAx >= minBx && minAx <= maxBx && maxAy >= minBy && minAy <= maxBy;
    }

    private static Polygon createRegularPolygon(
            double centerX,
            double centerY,
            double radius,
            int sides,
            double angleOffsetDegrees
    ) {
        Coordinate[] coords = new Coordinate[sides + 1];
        for (int i = 0; i < sides; i++) {
            double angle = Math.toRadians(angleOffsetDegrees + (360.0 * i / sides));
            double x = centerX + radius * Math.cos(angle);
            double y = centerY + radius * Math.sin(angle);
            coords[i] = new Coordinate(x, y);
        }
        coords[sides] = coords[0];
        return GEOMETRY_FACTORY.createPolygon(coords);
    }

    private static double computeSideLength(TilingShape shape, double areaPx2) {
        return switch (shape) {
            case TRIANGLE -> Math.sqrt((4.0 * areaPx2) / Math.sqrt(3.0));
            case SQUARE -> Math.sqrt(areaPx2);
            case HEXAGON -> Math.sqrt((2.0 * areaPx2) / (3.0 * Math.sqrt(3.0)));
        };
    }

    private static List<Geometry> extractPolygonalComponents(Geometry geometry) {
        List<Geometry> polygons = new ArrayList<>();
        addPolygonal(geometry, polygons);
        return polygons;
    }

    private static void addPolygonal(Geometry geometry, List<Geometry> out) {
        if (geometry == null || geometry.isEmpty()) {
            return;
        }

        if (geometry instanceof Polygon) {
            out.add(geometry);
            return;
        }

        if (geometry instanceof GeometryCollection collection) {
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                addPolygonal(collection.getGeometryN(i), out);
            }
        }
    }
}
