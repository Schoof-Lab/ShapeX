package qupath.ext.shapex.core;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.ext.shapex.ShapeXResources;

import java.awt.geom.AffineTransform;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.stream.Collectors;

public final class AnnotationUtils {

    private AnnotationUtils() {}

    private static final String UNCLASSIFIED_SAMPLE_PREFIX =
            ShapeXResources.get("annotation.unclassified.prefix");
    private static final String MAPPED_SAMPLE_PREFIX = "__slide_mapped__";
    private static final String ANNOTATION_SAMPLE_PREFIX = "__annotation__";

    /* ============================================================
     *  Transform annotations (used by TransformApplier)
     * ============================================================ */

    public static PathObjectHierarchy transformHierarchy(
            PathObjectHierarchy original,
            AffineTransform transform
    ) {
        PathObjectHierarchy newHierarchy = new PathObjectHierarchy();

        for (PathObject obj : original.getRootObject().getChildObjects()) {
            PathObject copy =
                    PathObjectTools.transformObjectRecursive(
                            obj, transform, true, true
                    );
            newHierarchy.addObject(copy);
        }

        return newHierarchy;
    }

    public static Map<String, Integer> getAnnotationClassColorsSafe() {

        var imageData = qupath.lib.gui.QuPathGUI.getInstance().getImageData();
        if (imageData == null)
            return Map.of();

        return getAnnotationClassColors(imageData.getHierarchy());
    }

    /* ============================================================
     *  Sample extraction
     * ============================================================ */

    public static Set<String> getAnnotationClassNames(PathObjectHierarchy hierarchy) {

        return hierarchy.getAnnotationObjects()
                .stream()
                .filter(obj -> isEnclosedROI(obj))
                .filter(obj -> obj.getPathClass() != null)
                .map(obj -> obj.getPathClass().getName())
                .collect(Collectors.toSet());
    }

    public static Set<String> getAnnotationSampleKeys(
            PathObjectHierarchy hierarchy,
            String slideName,
            boolean includeUnclassified
    ) {
        return getAnnotationSampleKeys(hierarchy, slideName, includeUnclassified, false, true);
    }

    public static Set<String> getAnnotationSampleKeys(
            PathObjectHierarchy hierarchy,
            String slideName,
            boolean includeUnclassified,
            boolean mapImageToPlate
    ) {
        return getAnnotationSampleKeys(
                hierarchy,
                slideName,
                includeUnclassified,
                mapImageToPlate,
                true
        );
    }

    public static Set<String> getAnnotationSampleKeys(
            PathObjectHierarchy hierarchy,
            String slideName,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        if (hierarchy == null)
            return Set.of();

        return hierarchy.getAnnotationObjects().stream()
                .map(obj -> getSampleKey(obj, slideName, includeUnclassified, mapImageToPlate, poolClasses))
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.toSet());
    }

    public static String getSampleKey(
            PathObject obj,
            String slideName,
            boolean includeUnclassified
    ) {
        return getSampleKey(obj, slideName, includeUnclassified, false, true);
    }

    public static String getSampleKey(
            PathObject obj,
            String slideName,
            boolean includeUnclassified,
            boolean mapImageToPlate
    ) {
        return getSampleKey(obj, slideName, includeUnclassified, mapImageToPlate, true);
    }

    public static String getSampleKey(
            PathObject obj,
            String slideName,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        if (!isEnclosedROI(obj))
            return null;

        String normalizedSlide = normalizeSlideName(slideName);
        PathClass pc = obj.getPathClass();
        boolean classified = pc != null && pc.getName() != null && !pc.getName().isBlank();

        if (!classified && !includeUnclassified) {
            return null;
        }

        String objectId = obj.getID() != null
                ? obj.getID().toString()
                : ShapeXResources.get("common.unknown");

        if (!poolClasses) {
            return createAnnotationSampleKey(
                    normalizedSlide,
                    getAnnotationDisplayName(obj),
                    objectId
            );
        }

        if (classified) {
            String className = pc.getName();
            return mapImageToPlate
                    ? createMappedSampleKey(normalizedSlide, className)
                    : className;
        }

        return createAnnotationSampleKey(
                normalizedSlide,
                UNCLASSIFIED_SAMPLE_PREFIX,
                objectId
        );
    }

    public static boolean isMappedSampleKey(String sampleKey) {
        return sampleKey != null && sampleKey.startsWith(MAPPED_SAMPLE_PREFIX + "|");
    }

    public static String getMappedSampleSlideName(String sampleKey) {
        MappedSampleParts parts = parseMappedSampleKey(sampleKey);
        return parts == null ? null : parts.slideName();
    }

    public static String toDisplaySampleName(String sampleKey) {
        AnnotationSampleParts annotationParts = parseAnnotationSampleKey(sampleKey);
        if (annotationParts != null) {
            return annotationParts.displayName();
        }

        MappedSampleParts parts = parseMappedSampleKey(sampleKey);
        return parts == null ? sampleKey : parts.sampleName();
    }

    public static String toDisplaySampleLabel(String sampleKey) {
        AnnotationSampleParts annotationParts = parseAnnotationSampleKey(sampleKey);
        if (annotationParts != null) {
            return annotationParts.displayName()
                    + " ["
                    + annotationParts.slideName()
                    + ", ID: "
                    + annotationParts.objectId()
                    + "]";
        }

        MappedSampleParts parts = parseMappedSampleKey(sampleKey);
        if (parts == null) {
            return sampleKey;
        }
        return parts.sampleName() + " [" + parts.slideName() + "]";
    }

    public static Integer getAnnotationColor(PathObject obj) {
        if (obj == null)
            return null;

        PathClass pc = obj.getPathClass();
        if (pc != null && pc.getColor() != null) {
            return pc.getColor();
        }

        try {
            return obj.getColor();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isEnclosedROI(PathObject obj) {

        return obj != null
                && obj.getROI() != null
                && obj.getROI().isArea();
    }

    public static Map<String, Integer> getAnnotationClassColors(
            PathObjectHierarchy hierarchy
    ) {

        Map<String, Integer> out = new HashMap<>();

        if (hierarchy == null)
            return out;

        for (PathObject obj : hierarchy.getAnnotationObjects()) {

            if (!isEnclosedROI(obj))
                continue;

            PathClass pc = obj.getPathClass();
            if (pc == null || pc.getName() == null)
                continue;

            out.put(pc.getName(), pc.getColor());
        }

        return out;
    }

    public static Map<String, Integer> getAnnotationClassColorsForProject(Project<?> project) {

        Map<String, Integer> colors = new HashMap<>();

        if (project == null)
            return colors;

        project.getImageList().forEach(entry -> {
            try {
                var imageData = entry.readImageData();
                var hierarchy = imageData.getHierarchy();

                for (var obj : hierarchy.getAnnotationObjects()) {

                    if (!isEnclosedROI(obj))
                        continue;

                    var pc = obj.getPathClass();
                    if (pc != null && pc.getName() != null) {
                        colors.putIfAbsent(pc.getName(), pc.getColor());
                    }
                }

            } catch (Exception ignored) {}
        });

        return colors;
    }

    private static String normalizeSlideName(String slideName) {
        return (slideName == null || slideName.isBlank())
                ? ShapeXResources.get("common.current")
                : slideName.replace('|', '/');
    }

    private static String getAnnotationDisplayName(PathObject obj) {
        if (obj != null && obj.getName() != null && !obj.getName().isBlank()) {
            return obj.getName();
        }
        return ShapeXResources.get("annotation.unnamed");
    }

    private static String createMappedSampleKey(String slideName, String sampleName) {
        return MAPPED_SAMPLE_PREFIX
                + "|"
                + encodeComponent(slideName)
                + "|"
                + encodeComponent(sampleName);
    }

    private static String createAnnotationSampleKey(
            String slideName,
            String displayName,
            String objectId
    ) {
        return ANNOTATION_SAMPLE_PREFIX
                + "|"
                + encodeComponent(slideName)
                + "|"
                + encodeComponent(displayName)
                + "|"
                + encodeComponent(objectId);
    }

    private static MappedSampleParts parseMappedSampleKey(String sampleKey) {
        if (!isMappedSampleKey(sampleKey)) {
            return null;
        }

        String[] parts = sampleKey.split("\\|", 3);
        if (parts.length != 3) {
            return null;
        }

        try {
            return new MappedSampleParts(
                    decodeComponent(parts[1]),
                    decodeComponent(parts[2])
            );
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AnnotationSampleParts parseAnnotationSampleKey(String sampleKey) {
        if (sampleKey == null || !sampleKey.startsWith(ANNOTATION_SAMPLE_PREFIX + "|")) {
            return null;
        }

        String[] parts = sampleKey.split("\\|", 4);
        if (parts.length != 4) {
            return null;
        }

        try {
            return new AnnotationSampleParts(
                    decodeComponent(parts[1]),
                    decodeComponent(parts[2]),
                    decodeComponent(parts[3])
            );
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String encodeComponent(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeComponent(String value) {
        return new String(
                Base64.getUrlDecoder().decode(value),
                StandardCharsets.UTF_8
        );
    }

    private record MappedSampleParts(String slideName, String sampleName) {}
    private record AnnotationSampleParts(String slideName, String displayName, String objectId) {}

}
