package qupath.ext.shapex.core;

import qupath.ext.shapex.ShapeXResources;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies the final ShapeX plate assignment to QuPath annotation metadata.
 */
public final class ShapeXAnnotationMetadata {

    public static final String PLATE_KEY = "Plate";
    public static final String WELL_KEY = "Well";
    public static final String HEX_KEY = "Hex";
    public static final String SEED_KEY = "ShapeX Seed";

    private static final String NONE_VALUE = "None";
    private static final String OWNED_KEYS_METADATA_KEY = "_ShapeX Metadata Keys";
    private static final String OWNED_KEYS_SEPARATOR = "|";
    private static final List<String> OWNED_KEYS = List.of(
            PLATE_KEY,
            WELL_KEY,
            HEX_KEY,
            SEED_KEY
    );
    private static final Object HIERARCHY_EVENT_SOURCE = new Object();

    private ShapeXAnnotationMetadata() {}

    /**
     * Updates metadata for assigned area annotations and generated calibration points.
     * Stale ShapeX-owned keys are removed from annotations that are no longer assigned.
     *
     * @return the number of annotations whose metadata changed
     */
    public static int apply(
            PathObjectHierarchy hierarchy,
            String slideName,
            MultiPlateAssignmentModel plateSet,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        if (hierarchy == null || plateSet == null) {
            return 0;
        }

        Map<String, PlateLocation> sampleLocations = plateSet.getEnabledSampleLocations();
        String seedValue = getSeedValue(plateSet);
        List<PathObject> changedObjects = new ArrayList<>();

        for (PathObject obj : hierarchy.getAnnotationObjects()) {
            Map<String, String> desiredMetadata = buildDesiredMetadata(
                    obj,
                    slideName,
                    plateSet,
                    sampleLocations,
                    seedValue,
                    includeAllAnnotations,
                    mapImageToPlate,
                    poolClasses
            );

            if (applyOwnedMetadata(obj, desiredMetadata)) {
                changedObjects.add(obj);
            }
        }

        if (!changedObjects.isEmpty()) {
            hierarchy.fireObjectsChangedEvent(HIERARCHY_EVENT_SOURCE, changedObjects);
        }
        return changedObjects.size();
    }

    /**
     * Identifies the metadata-only hierarchy event so Plate Designer can keep its
     * current custom assignments while QuPath refreshes the Key/Value table.
     */
    public static boolean isShapeXMetadataEvent(Object source) {
        return source == HIERARCHY_EVENT_SOURCE;
    }

    private static Map<String, String> buildDesiredMetadata(
            PathObject obj,
            String slideName,
            MultiPlateAssignmentModel plateSet,
            Map<String, PlateLocation> sampleLocations,
            String seedValue,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        Map<String, String> desired = new LinkedHashMap<>();

        if (isGeneratedCalibrationPoint(obj)) {
            desired.put(HEX_KEY, getHexValue(obj));
            desired.put(SEED_KEY, seedValue);
            return desired;
        }

        String sampleKey = AnnotationUtils.getSampleKey(
                obj,
                slideName,
                includeAllAnnotations,
                mapImageToPlate,
                poolClasses
        );
        if (sampleKey == null || sampleKey.isBlank()) {
            return desired;
        }

        PlateLocation location = sampleLocations.get(sampleKey);
        if (location == null
                || plateSet.getExcludedWells(location.plateIndex()).contains(location.well())) {
            return desired;
        }

        desired.put(PLATE_KEY, ShapeXResources.format("plate.name.pretty", location.plateIndex() + 1));
        desired.put(WELL_KEY, location.well());
        desired.put(HEX_KEY, getHexValue(obj));
        desired.put(SEED_KEY, seedValue);
        return desired;
    }

    private static boolean isGeneratedCalibrationPoint(PathObject obj) {
        return obj != null
                && obj.getROI() != null
                && obj.getROI().isPoint()
                && obj.getPathClass() != null
                && CalibrationResolver.AUTO_CLASS.equals(obj.getPathClass().getName());
    }

    private static String getSeedValue(MultiPlateAssignmentModel plateSet) {
        if (!plateSet.isRandomizeSamples()) {
            return NONE_VALUE;
        }
        String seed = plateSet.getSeed();
        return seed == null || seed.isBlank() ? NONE_VALUE : seed;
    }

    private static String getHexValue(PathObject obj) {
        Integer color = AnnotationUtils.getAnnotationColor(obj);
        if (color == null) {
            return NONE_VALUE;
        }
        return String.format(Locale.ROOT, "#%06X", color & 0x00FFFFFF);
    }

    private static boolean applyOwnedMetadata(
            PathObject obj,
            Map<String, String> desiredMetadata
    ) {
        if (obj == null) {
            return false;
        }

        Set<String> previouslyOwnedKeys = getPreviouslyOwnedKeys(obj);
        boolean hasOwnershipMarker = obj.hasMetadata()
                && obj.getMetadata().containsKey(OWNED_KEYS_METADATA_KEY);
        if (desiredMetadata.isEmpty()
                && previouslyOwnedKeys.isEmpty()
                && !hasOwnershipMarker) {
            return false;
        }

        Map<String, String> metadata = obj.getMetadata();
        boolean changed = false;
        for (String key : OWNED_KEYS) {
            if (desiredMetadata.containsKey(key)) {
                String desiredValue = desiredMetadata.get(key);
                if (!Objects.equals(metadata.get(key), desiredValue)) {
                    metadata.put(key, desiredValue);
                    changed = true;
                }
            } else if (previouslyOwnedKeys.contains(key) && metadata.containsKey(key)) {
                metadata.remove(key);
                changed = true;
            }
        }

        String desiredOwnershipValue = String.join(
                OWNED_KEYS_SEPARATOR,
                desiredMetadata.keySet()
        );
        if (desiredOwnershipValue.isEmpty()) {
            if (metadata.containsKey(OWNED_KEYS_METADATA_KEY)) {
                metadata.remove(OWNED_KEYS_METADATA_KEY);
                changed = true;
            }
        } else if (!Objects.equals(
                metadata.get(OWNED_KEYS_METADATA_KEY),
                desiredOwnershipValue
        )) {
            metadata.put(OWNED_KEYS_METADATA_KEY, desiredOwnershipValue);
            changed = true;
        }
        return changed;
    }

    private static Set<String> getPreviouslyOwnedKeys(PathObject obj) {
        if (!obj.hasMetadata()) {
            return Set.of();
        }
        Map<String, String> metadata = obj.getMetadata();
        String storedKeys = metadata.get(OWNED_KEYS_METADATA_KEY);
        if (storedKeys == null || storedKeys.isBlank()) {
            return Set.of();
        }

        Set<String> knownKeys = new LinkedHashSet<>(OWNED_KEYS);
        Set<String> ownedKeys = new LinkedHashSet<>();
        Arrays.stream(storedKeys.split("\\|"))
                .filter(knownKeys::contains)
                .forEach(ownedKeys::add);
        return ownedKeys;
    }
}
