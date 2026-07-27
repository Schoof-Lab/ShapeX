package qupath.ext.shapex.core;

import qupath.ext.shapex.ShapeXResources;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared project/image helpers used by the UI managers.
 */
public final class ProjectAnnotationService {

    private ProjectAnnotationService() {}

    public static final class SampleTooltipData {
        private String name = ShapeXResources.get("annotation.default_name");
        private String className = ShapeXResources.get("annotation.unclassed");
        private int annotationCount = 0;
        private String objectId;
        private final LinkedHashSet<String> slides = new LinkedHashSet<>();

        private void addContribution(PathObject obj, String slideName) {
            String annotationName = getTooltipAnnotationName(obj);
            if (annotationCount == 0) {
                name = annotationName;
            } else if (!name.equals(annotationName)) {
                name = ShapeXResources.get("annotation.default_name");
            }

            className = getTooltipClassName(obj);
            annotationCount++;

            if (annotationCount == 1) {
                String resolvedObjectId = getPathObjectId(obj);
                objectId = resolvedObjectId.isBlank()
                        ? ShapeXResources.get("common.unknown.capitalized")
                        : resolvedObjectId;
            } else {
                objectId = null;
            }

            if (slideName != null && !slideName.isBlank()) {
                slides.add(slideName);
            }
        }

        public String name() {
            return name;
        }

        public String className() {
            return className;
        }

        public int annotationCount() {
            return annotationCount;
        }

        public String objectId() {
            return objectId;
        }

        public List<String> slides() {
            return List.copyOf(slides);
        }
    }

    /* ============================================================
     * Project/image helpers
     * ============================================================ */

    public static String getCurrentSlideName() {
        var gui = QuPathGUI.getInstance();
        var imageData = gui.getImageData();
        var project = gui.getProject();

        if (imageData != null && project != null) {
            var entry = project.getEntry(imageData);
            if (entry != null) {
                return entry.getImageName();
            }
        }

        return ShapeXResources.get("common.current");
    }

    @SuppressWarnings("unchecked")
    public static ProjectImageEntry<BufferedImage> findProjectEntryByName(
            Project<?> project,
            String slideName
    ) {
        if (project == null || slideName == null) {
            return null;
        }

        for (var entry : project.getImageList()) {
            if (slideName.equals(entry.getImageName())) {
                return (ProjectImageEntry<BufferedImage>) entry;
            }
        }

        return null;
    }

    public static ImageData<BufferedImage> getImageDataForEntry(
            ProjectImageEntry<BufferedImage> entry
    ) throws IOException {
        var gui = QuPathGUI.getInstance();
        var currentImageData = gui.getImageData();
        var project = gui.getProject();

        if (currentImageData != null && project != null) {
            var currentEntry = project.getEntry(currentImageData);
            if (currentEntry == entry) {
                return currentImageData;
            }
        }

        return entry.readImageData();
    }

    /* ============================================================
     * Plate/sample helpers
     * ============================================================ */

    public static void collectSampleViewData(
            PathObjectHierarchy hierarchy,
            String slideName,
            boolean includeUnclassified,
            Map<String, Integer> sampleColors,
            Map<String, SampleTooltipData> sampleTooltipData
    ) {
        collectSampleViewData(
                hierarchy,
                slideName,
                includeUnclassified,
                false,
                true,
                sampleColors,
                sampleTooltipData
        );
    }

    public static void collectSampleViewData(
            PathObjectHierarchy hierarchy,
            String slideName,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses,
            Map<String, Integer> sampleColors,
            Map<String, SampleTooltipData> sampleTooltipData
    ) {
        if (hierarchy == null) {
            return;
        }

        for (PathObject obj : hierarchy.getAnnotationObjects()) {
            String sampleKey = AnnotationUtils.getSampleKey(
                    obj,
                    slideName,
                    includeUnclassified,
                    mapImageToPlate,
                    poolClasses
            );
            if (sampleKey == null) {
                continue;
            }

            Integer color = AnnotationUtils.getAnnotationColor(obj);
            if (color != null) {
                sampleColors.putIfAbsent(sampleKey, color);
            }

            sampleTooltipData
                    .computeIfAbsent(sampleKey, ignored -> new SampleTooltipData())
                    .addContribution(obj, slideName);
        }
    }

    public static List<String> getSamplesInAssignmentOrder(
            Project<?> project,
            List<String> selectedSlidesInOrder,
            boolean includeUnclassified
    ) {
        return getSamplesInAssignmentOrder(project, selectedSlidesInOrder, includeUnclassified, false, true);
    }

    public static List<String> getSamplesInAssignmentOrder(
            Project<?> project,
            List<String> selectedSlidesInOrder,
            boolean includeUnclassified,
            boolean mapImageToPlate
    ) {
        return getSamplesInAssignmentOrder(
                project,
                selectedSlidesInOrder,
                includeUnclassified,
                mapImageToPlate,
                true
        );
    }

    public static List<String> getSamplesInAssignmentOrder(
            Project<?> project,
            List<String> selectedSlidesInOrder,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        if (project == null) {
            return List.of();
        }

        LinkedHashSet<String> orderedSamples = new LinkedHashSet<>();

        if (selectedSlidesInOrder == null || selectedSlidesInOrder.isEmpty()) {
            var imageData = QuPathGUI.getInstance().getImageData();
            if (imageData == null) {
                return List.of();
            }

            addSamplesFromHierarchyInOrder(
                    orderedSamples,
                    imageData.getHierarchy(),
                    getCurrentSlideName(),
                    includeUnclassified,
                    mapImageToPlate,
                    poolClasses
            );
            return new ArrayList<>(orderedSamples);
        }

        for (String selectedSlide : selectedSlidesInOrder) {
            var entry = findProjectEntryByName(project, selectedSlide);
            if (entry == null) {
                continue;
            }

            try {
                var imageData = getImageDataForEntry(entry);
                addSamplesFromHierarchyInOrder(
                        orderedSamples,
                        imageData.getHierarchy(),
                        entry.getImageName(),
                        includeUnclassified,
                        mapImageToPlate,
                        poolClasses
                );
            } catch (Exception ignored) {}
        }

        return new ArrayList<>(orderedSamples);
    }

    public static List<String> getSamplesForEntriesInAssignmentOrder(
            Project<?> project,
            List<ProjectImageEntry<BufferedImage>> selectedEntriesInOrder,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        if (project == null) {
            return List.of();
        }

        LinkedHashSet<String> orderedSamples = new LinkedHashSet<>();

        if (selectedEntriesInOrder == null || selectedEntriesInOrder.isEmpty()) {
            var imageData = QuPathGUI.getInstance().getImageData();
            if (imageData == null) {
                return List.of();
            }

            addSamplesFromHierarchyInOrder(
                    orderedSamples,
                    imageData.getHierarchy(),
                    getCurrentSlideName(),
                    includeUnclassified,
                    mapImageToPlate,
                    poolClasses
            );
            return new ArrayList<>(orderedSamples);
        }

        for (ProjectImageEntry<BufferedImage> entry : selectedEntriesInOrder) {
            if (entry == null) {
                continue;
            }

            try {
                var imageData = getImageDataForEntry(entry);
                addSamplesFromHierarchyInOrder(
                        orderedSamples,
                        imageData.getHierarchy(),
                        entry.getImageName(),
                        includeUnclassified,
                        mapImageToPlate,
                        poolClasses
                );
            } catch (Exception ignored) {}
        }

        return new ArrayList<>(orderedSamples);
    }

    public static List<List<String>> getSamplesGroupedBySlide(
            Project<?> project,
            List<String> selectedSlidesInOrder,
            boolean includeUnclassified,
            boolean mapImageToPlate
    ) {
        return getSamplesGroupedBySlide(
                project,
                selectedSlidesInOrder,
                includeUnclassified,
                mapImageToPlate,
                true
        );
    }

    public static List<List<String>> getSamplesGroupedBySlide(
            Project<?> project,
            List<String> selectedSlidesInOrder,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        if (project == null) {
            return List.of();
        }

        List<List<String>> groupedSamples = new ArrayList<>();

        if (selectedSlidesInOrder == null || selectedSlidesInOrder.isEmpty()) {
            var imageData = QuPathGUI.getInstance().getImageData();
            if (imageData == null) {
                return List.of();
            }

            LinkedHashSet<String> slideSamples = new LinkedHashSet<>();
            addSamplesFromHierarchyInOrder(
                    slideSamples,
                    imageData.getHierarchy(),
                    getCurrentSlideName(),
                    includeUnclassified,
                    mapImageToPlate,
                    poolClasses
            );
            groupedSamples.add(new ArrayList<>(slideSamples));
            return groupedSamples;
        }

        for (String selectedSlide : selectedSlidesInOrder) {
            LinkedHashSet<String> slideSamples = new LinkedHashSet<>();
            var entry = findProjectEntryByName(project, selectedSlide);
            if (entry != null) {
                try {
                    var imageData = getImageDataForEntry(entry);
                    addSamplesFromHierarchyInOrder(
                        slideSamples,
                        imageData.getHierarchy(),
                        entry.getImageName(),
                        includeUnclassified,
                        mapImageToPlate,
                        poolClasses
                    );
                } catch (Exception ignored) {}
            }
            groupedSamples.add(new ArrayList<>(slideSamples));
        }

        return groupedSamples;
    }

    public static List<List<String>> getSamplesGroupedByEntry(
            Project<?> project,
            List<ProjectImageEntry<BufferedImage>> selectedEntriesInOrder,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        if (project == null) {
            return List.of();
        }

        List<List<String>> groupedSamples = new ArrayList<>();

        if (selectedEntriesInOrder == null || selectedEntriesInOrder.isEmpty()) {
            var imageData = QuPathGUI.getInstance().getImageData();
            if (imageData == null) {
                return List.of();
            }

            LinkedHashSet<String> imageSamples = new LinkedHashSet<>();
            addSamplesFromHierarchyInOrder(
                    imageSamples,
                    imageData.getHierarchy(),
                    getCurrentSlideName(),
                    includeUnclassified,
                    mapImageToPlate,
                    poolClasses
            );
            groupedSamples.add(new ArrayList<>(imageSamples));
            return groupedSamples;
        }

        for (ProjectImageEntry<BufferedImage> entry : selectedEntriesInOrder) {
            LinkedHashSet<String> imageSamples = new LinkedHashSet<>();
            if (entry != null) {
                try {
                    var imageData = getImageDataForEntry(entry);
                    addSamplesFromHierarchyInOrder(
                            imageSamples,
                            imageData.getHierarchy(),
                            entry.getImageName(),
                            includeUnclassified,
                            mapImageToPlate,
                            poolClasses
                    );
                } catch (Exception ignored) {}
            }
            groupedSamples.add(new ArrayList<>(imageSamples));
        }

        return groupedSamples;
    }

    public static Map<String, Set<String>> getSampleToSlidesMap(
            Project<?> project,
            boolean includeUnclassified
    ) {
        return getSampleToSlidesMap(project, includeUnclassified, false, true);
    }

    public static Map<String, Set<String>> getSampleToSlidesMap(
            Project<?> project,
            boolean includeUnclassified,
            boolean mapImageToPlate
    ) {
        return getSampleToSlidesMap(project, includeUnclassified, mapImageToPlate, true);
    }

    public static Map<String, Set<String>> getSampleToSlidesMap(
            Project<?> project,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        Map<String, Set<String>> sampleToSlides = new HashMap<>();

        if (project == null) {
            return sampleToSlides;
        }

        project.getImageList().forEach(entry -> {
            try {
                var imageData = entry.readImageData();
                String slideName = entry.getImageName();

                Set<String> sampleKeys = AnnotationUtils.getAnnotationSampleKeys(
                        imageData.getHierarchy(),
                        slideName,
                        includeUnclassified,
                        mapImageToPlate,
                        poolClasses
                );

                for (String sampleKey : sampleKeys) {
                    sampleToSlides.computeIfAbsent(sampleKey, ignored -> new HashSet<>())
                            .add(slideName);
                }
            } catch (Exception ignored) {}
        });

        return sampleToSlides;
    }

    private static void addSamplesFromHierarchyInOrder(
            LinkedHashSet<String> orderedSamples,
            PathObjectHierarchy hierarchy,
            String slideName,
            boolean includeUnclassified,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        if (hierarchy == null) {
            return;
        }

        for (PathObject obj : hierarchy.getAnnotationObjects()) {
            String sampleKey = AnnotationUtils.getSampleKey(
                    obj,
                    slideName,
                    includeUnclassified,
                    mapImageToPlate,
                    poolClasses
            );
            if (sampleKey != null && !sampleKey.isBlank()) {
                orderedSamples.add(sampleKey);
            }
        }
    }

    private static String getTooltipAnnotationName(PathObject obj) {
        if (obj == null) {
            return ShapeXResources.get("annotation.default_name");
        }

        String annotationName = obj.getName();
        if (annotationName != null && !annotationName.isBlank()) {
            return annotationName;
        }

        return ShapeXResources.get("annotation.default_name");
    }

    private static String getTooltipClassName(PathObject obj) {
        if (obj == null) {
            return ShapeXResources.get("annotation.unclassed");
        }

        var pathClass = obj.getPathClass();
        if (pathClass != null && pathClass.getName() != null && !pathClass.getName().isBlank()) {
            return pathClass.getName();
        }

        return ShapeXResources.get("annotation.unclassed");
    }

    /* ============================================================
     * Tessellator helpers
     * ============================================================ */

    public static List<PathObject> getTessellatorAnnotations(ImageData<BufferedImage> imageData) {
        if (imageData == null) {
            return List.of();
        }

        List<PathObject> annotations = new ArrayList<>();
        for (PathObject obj : imageData.getHierarchy().getAnnotationObjects()) {
            if (obj == null || obj.getROI() == null || !obj.getROI().isArea()) {
                continue;
            }
            if (AnnotationTessellator.isGeneratedTessellationAnnotation(obj)) {
                continue;
            }
            annotations.add(obj);
        }

        annotations.sort(Comparator
                .comparing(ProjectAnnotationService::getTessellatorAnnotationLabel, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ProjectAnnotationService::getPathObjectId));

        return annotations;
    }

    public static String getPathObjectId(PathObject obj) {
        if (obj == null || obj.getID() == null) {
            return "";
        }
        return obj.getID().toString();
    }

    public static String getTessellatorAnnotationLabel(PathObject obj) {
        if (obj == null) {
            return "";
        }

        String name = obj.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }

        var pathClass = obj.getPathClass();
        if (pathClass != null && pathClass.getName() != null && !pathClass.getName().isBlank()) {
            return pathClass.getName();
        }

        return ShapeXResources.get("annotation.unnamed");
    }

    /* ============================================================
     * Annotation state hashing
     * ============================================================ */

    public static long computeAnnotationStateHash(
            ImageData<?> imageData,
            boolean includeUnclassified
    ) {
        if (imageData == null) {
            return 0L;
        }

        var hierarchy = imageData.getHierarchy();
        long combined = 0x9E3779B97F4A7C15L;
        int counted = 0;

        for (PathObject obj : hierarchy.getAnnotationObjects()) {
            if (obj == null || obj.getROI() == null || !obj.getROI().isArea()) {
                continue;
            }

            var pathClass = obj.getPathClass();
            String className = pathClass == null ? null : pathClass.getName();
            boolean classified = className != null && !className.isBlank();
            if (!classified && !includeUnclassified) {
                continue;
            }

            long objectHash = 17L;
            objectHash = 31L * objectHash + (obj.getID() != null ? obj.getID().hashCode() : 0);
            objectHash = 31L * objectHash + (obj.getName() != null ? obj.getName().hashCode() : 0);
            objectHash = 31L * objectHash + (classified ? className.hashCode() : 0);
            Integer color = AnnotationUtils.getAnnotationColor(obj);
            objectHash = 31L * objectHash + (color != null ? color.hashCode() : 0);

            int rotation = (int) (objectHash & 63L);
            combined += Long.rotateLeft(objectHash * 0x9E3779B97F4A7C15L, rotation);
            counted++;
        }

        long hash = 17L;
        hash = 31L * hash + (includeUnclassified ? 1 : 0);
        hash = 31L * hash + counted;
        hash = 31L * hash + combined;
        return hash;
    }
}
