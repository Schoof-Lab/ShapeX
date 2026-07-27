package qupath.ext.shapex.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves concise, deterministic labels for the sample wells shown in the
 * per-plate overview sheets.
 */
final class OverviewWellLabelResolver {

    private static final String VALUE_SEPARATOR = ", ";
    private static final String NAME_COMPONENT_SEPARATOR = " ";

    private OverviewWellLabelResolver() {}

    record AnnotationLabelSource(
            String className,
            String annotationName,
            String objectId
    ) {
        AnnotationLabelSource {
            className = normalize(className);
            annotationName = normalize(annotationName);
            objectId = normalize(objectId);
        }
    }

    static Map<String, String> resolve(
            Collection<String> sampleKeys,
            Map<String, ? extends Collection<AnnotationLabelSource>> sourcesBySample
    ) {
        if (sampleKeys == null || sampleKeys.isEmpty()) {
            return Map.of();
        }

        List<String> sortedSampleKeys = new ArrayList<>(sampleKeys);
        Collections.sort(sortedSampleKeys);

        Map<String, LabelParts> partsBySample = new LinkedHashMap<>();
        Map<String, Integer> baseLabelCounts = new LinkedHashMap<>();
        for (String sampleKey : sortedSampleKeys) {
            Collection<AnnotationLabelSource> sources = List.of();
            if (sourcesBySample != null) {
                Collection<AnnotationLabelSource> matchingSources =
                        sourcesBySample.get(sampleKey);
                if (matchingSources != null) {
                    sources = matchingSources;
                }
            }
            LabelParts parts = LabelParts.from(sampleKey, sources);
            partsBySample.put(sampleKey, parts);
            baseLabelCounts.merge(parts.baseLabel(), 1, Integer::sum);
        }

        Map<String, String> expandedLabels = new LinkedHashMap<>();
        Map<String, Integer> expandedLabelCounts = new LinkedHashMap<>();
        for (String sampleKey : sortedSampleKeys) {
            LabelParts parts = partsBySample.get(sampleKey);
            String label = parts.baseLabel();
            if (baseLabelCounts.getOrDefault(label, 0) > 1 && parts.hasClassName()) {
                label = combineAnnotationAndClass(parts.annotationNames(), parts.classNames());
                if (parts.annotationNames().isBlank()) {
                    label = appendObjectIds(label, parts.objectIds());
                }
            }
            expandedLabels.put(sampleKey, label);
            expandedLabelCounts.merge(label, 1, Integer::sum);
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        for (String sampleKey : sortedSampleKeys) {
            String label = expandedLabels.get(sampleKey);
            if (expandedLabelCounts.getOrDefault(label, 0) > 1) {
                label = appendObjectIds(label, partsBySample.get(sampleKey).objectIds());
            }
            resolved.put(sampleKey, label);
        }
        return Collections.unmodifiableMap(resolved);
    }

    static String combineAnnotationAndClass(String annotationNames, String classNames) {
        if (annotationNames == null || annotationNames.isBlank()) {
            return classNames;
        }
        if (classNames == null || classNames.isBlank()) {
            return annotationNames;
        }
        return annotationNames + NAME_COMPONENT_SEPARATOR + classNames;
    }

    static String appendObjectIds(String label, List<String> objectIds) {
        if (objectIds == null || objectIds.isEmpty()) {
            return label;
        }

        String idLabel = objectIds.size() == 1 ? "ID" : "IDs";
        String suffix = " ["
                + idLabel
                + ": "
                + String.join(VALUE_SEPARATOR, objectIds)
                + "]";
        return label.endsWith(suffix) ? label : label + suffix;
    }

    private static String joinValues(Set<String> values) {
        return String.join(VALUE_SEPARATOR, values);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private record LabelParts(
            String classNames,
            String annotationNames,
            List<String> objectIds,
            String baseLabel
    ) {
        private static LabelParts from(
                String sampleKey,
                Collection<AnnotationLabelSource> sources
        ) {
            Set<String> classNames = new TreeSet<>();
            Set<String> annotationNames = new TreeSet<>();
            Set<String> objectIds = new TreeSet<>();

            if (sources != null) {
                for (AnnotationLabelSource source : sources) {
                    if (source == null) {
                        continue;
                    }
                    if (!source.className().isBlank()) {
                        classNames.add(source.className());
                    }
                    if (!source.annotationName().isBlank()) {
                        annotationNames.add(source.annotationName());
                    }
                    if (!source.objectId().isBlank()) {
                        objectIds.add(source.objectId());
                    }
                }
            }

            String joinedClasses = joinValues(classNames);
            String joinedAnnotations = joinValues(annotationNames);
            List<String> sortedObjectIds = List.copyOf(objectIds);

            String baseLabel;
            if (!joinedClasses.isBlank()) {
                baseLabel = joinedClasses;
            } else if (!joinedAnnotations.isBlank()) {
                baseLabel = joinedAnnotations;
            } else if (!sortedObjectIds.isEmpty()) {
                baseLabel = String.join(VALUE_SEPARATOR, sortedObjectIds);
            } else {
                baseLabel = sampleKey == null ? "" : sampleKey;
            }

            return new LabelParts(
                    joinedClasses,
                    joinedAnnotations,
                    sortedObjectIds,
                    baseLabel
            );
        }

        private boolean hasClassName() {
            return !classNames.isBlank();
        }
    }
}
