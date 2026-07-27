package qupath.ext.shapex.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import qupath.ext.shapex.ShapeXResources;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.PointsROI;
import qupath.lib.roi.interfaces.ROI;

public final class LMDXmlWriter {

    private LMDXmlWriter() {}
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private record XmlShape(
            String sourceId,
            List<Point2> points
    ) {}

    public static void validateCalibrationExists(
            PathObjectHierarchy hierarchy
    ) throws IOException {

        extractCalibrationPoints(hierarchy);
    }

    public static void writeXml(
            Path xmlFile,
            ImageData<?> imageData,
            String slideName,
            int plateNumber,
            int totalPlatesForSlide,
            Map<String, String> sampleToWell,
            boolean mapImageToPlate,
            boolean poolClasses
    ) throws IOException {
        writeXml(
                xmlFile,
                imageData,
                slideName,
                plateNumber,
                totalPlatesForSlide,
                sampleToWell,
                mapImageToPlate,
                poolClasses,
                Set.of(),
                0,
                0
        );
    }

    public static void writeXml(
            Path xmlFile,
            ImageData<?> imageData,
            String slideName,
            int plateNumber,
            int totalPlatesForSlide,
            Map<String, String> sampleToWell,
            boolean mapImageToPlate,
            boolean poolClasses,
            Set<String> skippedAnnotationIds
    ) throws IOException {
        writeXml(
                xmlFile,
                imageData,
                slideName,
                plateNumber,
                totalPlatesForSlide,
                sampleToWell,
                mapImageToPlate,
                poolClasses,
                skippedAnnotationIds,
                0,
                0
        );
    }

    public static void writeXml(
            Path xmlFile,
            ImageData<?> imageData,
            String slideName,
            int plateNumber,
            int totalPlatesForSlide,
            Map<String, String> sampleToWell,
            boolean mapImageToPlate,
            boolean poolClasses,
            Set<String> skippedAnnotationIds,
            int plateRows,
            int plateColumns
    ) throws IOException {

        if (imageData == null)
            throw new IOException(ShapeXResources.get("error.lmd.no_image_data"));

        PathObjectHierarchy hierarchy =
                imageData.getHierarchy();

        double[][] calibrationPoints =
                extractCalibrationPoints(hierarchy);

        /* ------------------------------------------------------------
         * Collect & group annotations by class
         * ------------------------------------------------------------ */

        Map<String, String> sampleDisplayNames = new HashMap<>();
        Set<String> classNames = new HashSet<>();
        Map<String, List<XmlShape>> bySample =
                collectShapesBySample(
                        hierarchy,
                        slideName,
                        sampleToWell,
                        sampleDisplayNames,
                        classNames,
                        true,
                        mapImageToPlate,
                        poolClasses,
                        skippedAnnotationIds
                );

        int totalShapes = bySample.values().stream().mapToInt(List::size).sum();

        // Fallback: Some QuPath ROI wrappers may report unexpected names even for
        // closed shapes. If the strict pass finds none, export all area ROIs.
        if (totalShapes == 0) {
            sampleDisplayNames.clear();
            classNames.clear();
            bySample = collectShapesBySample(
                    hierarchy,
                    slideName,
                    sampleToWell,
                    sampleDisplayNames,
                    classNames,
                    false,
                    mapImageToPlate,
                    poolClasses,
                    skippedAnnotationIds
            );
            totalShapes = bySample.values().stream()
                    .mapToInt(List::size)
                    .sum();
        }

        /* ------------------------------------------------------------
         * Sort samples by well order (A1 → A2 → ... → B1 ...)
         * ------------------------------------------------------------ */

        List<String> sortedSamples =
                bySample.keySet().stream()
                        .sorted(Comparator.comparing(
                                sampleToWell::get,
                                PlateLayoutUtils.wellComparator()
                        ))
                        .toList();

        /* ------------------------------------------------------------
         * Count totals
         * ------------------------------------------------------------ */

        int totalClasses = classNames.size();
        long totalPoints = bySample.values().stream()
                .flatMap(List::stream)
                .mapToLong(shape -> shape.points().size())
                .sum();

        try (BufferedWriter w = Files.newBufferedWriter(xmlFile)) {

            // Header
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write("<!-- ============================================================\n");
            w.write("     Image             : " + imageData.getServer().getMetadata().getName() + "\n");
            w.write("     Plate             : Plate " + plateNumber + " / " + totalPlatesForSlide + "\n");
            w.write("     Generated         : " + ZonedDateTime.now().format(TIME_FMT) + "\n");
            w.write("\n");
            w.write("     Source            : QuPath   " + getCurrentQuPathVersionLabel() + "\n");
            w.write("     Extension         : ShapeX   " + getCurrentShapeXVersionLabel() + "\n");
            w.write("\n");
            w.write("     Classes           : " + totalClasses + "\n");
            w.write("     Annotations       : " + totalShapes + "\n");
            w.write("     Total point count : " + totalPoints + "\n");
            w.write("     ============================================================ -->\n");
            w.write("\n");

            // Data start
            w.write("<ImageData>\n");

            // Calibration points
            w.write("  <!-- Calibration Points -->\n");
            w.write("  <GlobalCoordinates>1</GlobalCoordinates>\n");

            for (int i = 0; i < 3; i++) {

                w.write("  <X_CalibrationPoint_" + (i + 1) + ">");
                w.write(Long.toString(Math.round(calibrationPoints[i][0])));
                w.write("</X_CalibrationPoint_" + (i + 1) + "> ");

                w.write("<Y_CalibrationPoint_" + (i + 1) + ">");
                w.write(Long.toString(roundLmdY(calibrationPoints[i][1])));
                w.write("</Y_CalibrationPoint_" + (i + 1) + ">\n");
            }

            // Shape count
            w.write("\n  <!-- Shape count within file -->\n");
            w.write("  <ShapeCount>" + totalShapes + "</ShapeCount>\n");
            w.write("\n");

            /* ------------------------------------------------------------
             * Write shapes grouped by class, ordered by well
             * ------------------------------------------------------------ */

            int shapeIndex = 1;

            for (String sample : sortedSamples) {

                String well = PlateLayoutUtils.toLmdWellLabel(
                        sampleToWell.get(sample),
                        plateRows,
                        plateColumns
                );
                String displayName = sampleDisplayNames.getOrDefault(sample, sample);

                /* Sample section header comment */
                w.write("  <!-- " + displayName + " (" + well + ") -->\n");

                for (XmlShape shape : bySample.get(sample)) {
                    List<Point2> pts = shape.points();

                    /* Shape ID comment */
                    w.write("  <!-- Shape ID: " + shape.sourceId() + " -->\n");

                    w.write("  <Shape_" + shapeIndex + ">\n");

                    w.write("    <PointCount>");
                    w.write(Integer.toString(pts.size()));
                    w.write("</PointCount>\n");

                    w.write("    <TransferID>" + shapeIndex + "</TransferID>\n");

                    w.write("    <CapID>");
                    w.write(well);
                    w.write("</CapID>\n");

                    for (int i = 0; i < pts.size(); i++) {

                        long x = Math.round(pts.get(i).getX());
                        long y = roundLmdY(pts.get(i).getY());

                        w.write("    <X_" + (i + 1) + ">");
                        w.write(Long.toString(x));
                        w.write("</X_" + (i + 1) + "> ");

                        w.write("<Y_" + (i + 1) + ">");
                        w.write(Long.toString(y));
                        w.write("</Y_" + (i + 1) + ">\n");
                    }

                    w.write("  </Shape_" + shapeIndex + ">\n\n");
                    shapeIndex++;
                }
            }

            // Data stop
            w.write("</ImageData>\n");
        }
    }

    private static long roundLmdY(double y) {
        return Math.round(-y);
    }

     /* Calibration point extraction*/
     private static double[][] extractCalibrationPoints(
             PathObjectHierarchy hierarchy
     ) throws IOException {

        List<PathObject> calibrationPoints = hierarchy.getAnnotationObjects()
                .stream()
                .filter(p -> p.getPathClass() != null)
                .filter(p -> CalibrationResolver.AUTO_CLASS
                        .equals(p.getPathClass().getName()))
                .toList();

        if (calibrationPoints.size() != 3) {
            throw new IOException(
                    ShapeXResources.format(
                            "error.lmd.calibration.expected_three",
                            CalibrationResolver.AUTO_CLASS,
                            calibrationPoints.size()
                    )
            );
        }

         // Sort by name (Calibration 1, 2, 3)
         calibrationPoints = calibrationPoints.stream()
                 .sorted(Comparator.comparing(PathObject::getName))
                 .toList();

         double[][] out = new double[3][2];

         for (int i = 0; i < 3; i++) {

             var roi = calibrationPoints.get(i).getROI();

            if (!(roi instanceof PointsROI pointsROI)) {
                throw new IOException(
                        ShapeXResources.get("error.lmd.calibration.not_pointsroi")
                );
            }

             var pt = pointsROI.getAllPoints().get(0);

             out[i][0] = pt.getX();
             out[i][1] = pt.getY();
         }

         return out;
     }

    private static Map<String, List<XmlShape>> collectShapesBySample(
            PathObjectHierarchy hierarchy,
            String slideName,
            Map<String, String> sampleToWell,
            Map<String, String> sampleDisplayNames,
            Set<String> classNames,
            boolean useSupportedTypeFilter,
            boolean mapImageToPlate,
            boolean poolClasses,
            Set<String> skippedAnnotationIds
    ) {
        Map<String, List<XmlShape>> bySample = new HashMap<>();

        for (PathObject obj : hierarchy.getAnnotationObjects()) {
            ROI roi = obj.getROI();
            if (roi == null ||
                !roi.isArea()) {
                continue;
            }

            if (useSupportedTypeFilter && !isSupportedRoiType(roi))
                continue;

            String objectId = sourceObjectId(obj);
            if (skippedAnnotationIds != null && skippedAnnotationIds.contains(objectId))
                continue;

            String sampleName = AnnotationUtils.getSampleKey(
                    obj,
                    slideName,
                    true,
                    mapImageToPlate,
                    poolClasses
            );
            if (sampleName == null)
                continue;

            if (!sampleToWell.containsKey(sampleName))
                continue;

            if (obj.getPathClass() != null &&
                    obj.getPathClass().getName() != null &&
                    !obj.getPathClass().getName().isBlank()) {
                String className = obj.getPathClass().getName();
                classNames.add(className);
                sampleDisplayNames.putIfAbsent(
                        sampleName,
                        poolClasses ? className : AnnotationUtils.toDisplaySampleName(sampleName)
                );
            } else {
                String annotationName = obj.getName();
                if (annotationName == null || annotationName.isBlank()) {
                    annotationName = ShapeXResources.get("annotation.unnamed");
                }
                sampleDisplayNames.putIfAbsent(sampleName, annotationName);
            }

            List<List<Point2>> pointSets = GeometryRoiResolver.extractClosedContours(roi.getGeometry());
            if (pointSets.isEmpty())
                continue;

            List<Point2> points = GeometryRoiResolver.mergePointSetsForSingleCut(pointSets);
            if (points.size() < 3)
                continue;

            bySample.computeIfAbsent(sampleName, k -> new ArrayList<>())
                    .add(new XmlShape(objectId, points));
        }

        return bySample;
    }

    private static boolean isSupportedRoiType(ROI roi) {
        String roiName = roi.getRoiName() != null
                ? roi.getRoiName().toLowerCase(Locale.ROOT)
                : "";
        String className = roi.getClass().getSimpleName().toLowerCase(Locale.ROOT);

        return roiName.contains("rectangle") ||
               roiName.contains("polygon") ||
               roiName.contains("ellipse") ||
               // QuPath often wraps drawn/derived closed shapes as generic area/geometry ROIs.
               roiName.contains("area") ||
               roiName.contains("geometry") ||
               className.contains("rectangle") ||
               className.contains("polygon") ||
               className.contains("ellipse") ||
               className.contains("area") ||
               className.contains("geometry");
    }

    private static String sourceObjectId(PathObject obj) {
        if (obj == null || obj.getID() == null) {
            return "unknown-" + Integer.toHexString(System.identityHashCode(obj));
        }
        return obj.getID().toString();
    }

    private static String getCurrentQuPathVersionLabel() {
        String rawVersion = null;

        Class<?> quPathGuiClass = loadClass("qupath.lib.gui.QuPathGUI");
        if (quPathGuiClass != null) {
            Object gui = invokeNoArgStaticObject(quPathGuiClass, "getInstance");
            if (gui != null) {
                rawVersion = invokeNoArgMethod(gui, "getVersion");
                if (rawVersion == null)
                    rawVersion = invokeNoArgMethod(gui, "getVersionString");
            }

            if (rawVersion == null) {
                rawVersion = invokeNoArgStaticMethod(quPathGuiClass, "getVersion");
                if (rawVersion == null)
                    rawVersion = invokeNoArgStaticMethod(quPathGuiClass, "getVersionString");
            }
        }

        String cleaned = rawVersion == null ? null : rawVersion.trim()
                .replaceFirst("(?i)^qupath\\s*", "")
                .replaceFirst("(?i)^version\\s*", "");

        return normalizeVersionLabel(cleaned);
    }

    private static String getCurrentShapeXVersionLabel() {
        String rawVersion = null;

        Package pkg = LMDXmlWriter.class.getPackage();
        if (pkg != null) {
            rawVersion = pkg.getImplementationVersion();
            if (rawVersion == null || rawVersion.isBlank())
                rawVersion = pkg.getSpecificationVersion();
        }

        if (rawVersion == null || rawVersion.isBlank()) {
            rawVersion = ShapeXResources.get("extension.shapex.version", null);
        }

        return normalizeVersionLabel(rawVersion);
    }

    private static String normalizeVersionLabel(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank())
            return ShapeXResources.get("common.unknown");

        String cleaned = rawVersion.trim()
                .replaceFirst("(?i)^version\\s*", "");

        if (cleaned.isBlank())
            return ShapeXResources.get("common.unknown");

        if (cleaned.startsWith("v") || cleaned.startsWith("V"))
            return cleaned;

        return "v" + cleaned;
    }

    private static String invokeNoArgMethod(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String invokeNoArgStaticMethod(Class<?> targetClass, String methodName) {
        Object value = invokeNoArgStaticObject(targetClass, methodName);
        return value == null ? null : value.toString();
    }

    private static Object invokeNoArgStaticObject(Class<?> targetClass, String methodName) {
        try {
            return targetClass.getMethod(methodName).invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }
}
