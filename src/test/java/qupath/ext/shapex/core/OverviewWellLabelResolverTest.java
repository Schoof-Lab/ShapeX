package qupath.ext.shapex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OverviewWellLabelResolverTest {

    @Test
    void classNameHasPriorityEvenForAnIndividualAnnotation() {
        Map<String, String> labels = resolve(Map.of(
                "sample", List.of(source("Tumor", "Biopsy", "id-1"))
        ));

        assertEquals(Map.of("sample", "Tumor"), labels);
    }

    @Test
    void unclassifiedAnnotationFallsBackToItsName() {
        Map<String, String> labels = resolve(Map.of(
                "sample", List.of(source(null, "Biopsy", "id-1"))
        ));

        assertEquals(Map.of("sample", "Biopsy"), labels);
    }

    @Test
    void unnamedUnclassifiedAnnotationFallsBackToItsObjectId() {
        Map<String, String> labels = resolve(Map.of(
                "sample", List.of(source(null, null, "id-1"))
        ));

        assertEquals(Map.of("sample", "id-1"), labels);
    }

    @Test
    void duplicateClassesExpandToAnnotationNameThenClassName() {
        Map<String, String> labels = resolve(Map.of(
                "left", List.of(source("Tumor", "Left", "id-1")),
                "right", List.of(source("Tumor", "Right", "id-2")),
                "stroma", List.of(source("Stroma", "Ignored while unique", "id-3"))
        ));

        assertEquals("Left Tumor", labels.get("left"));
        assertEquals("Right Tumor", labels.get("right"));
        assertEquals("Stroma", labels.get("stroma"));
    }

    @Test
    void identicalClassAndAnnotationNamesAppendTheObjectId() {
        Map<String, String> labels = resolve(Map.of(
                "first", List.of(source("Tumor", "Biopsy", "id-2")),
                "second", List.of(source("Tumor", "Biopsy", "id-1"))
        ));

        assertEquals("Biopsy Tumor [ID: id-2]", labels.get("first"));
        assertEquals("Biopsy Tumor [ID: id-1]", labels.get("second"));
    }

    @Test
    void duplicateClassWithoutAnAnnotationNameCascadesToObjectId() {
        Map<String, String> labels = resolve(Map.of(
                "named", List.of(source("Tumor", "Biopsy", "id-1")),
                "unnamed", List.of(source("Tumor", null, "id-2"))
        ));

        assertEquals("Biopsy Tumor", labels.get("named"));
        assertEquals("Tumor [ID: id-2]", labels.get("unnamed"));
    }

    @Test
    void pooledSamplesAppendAllContributingIdsInDeterministicOrder() {
        Map<String, String> labels = resolve(Map.of(
                "pooled-a", List.of(
                        source("Tumor", "Biopsy", "id-3"),
                        source("Tumor", "Biopsy", "id-1")
                ),
                "pooled-b", List.of(
                        source("Tumor", "Biopsy", "id-4"),
                        source("Tumor", "Biopsy", "id-2")
                )
        ));

        assertEquals("Biopsy Tumor [IDs: id-1, id-3]", labels.get("pooled-a"));
        assertEquals("Biopsy Tumor [IDs: id-2, id-4]", labels.get("pooled-b"));
    }

    @Test
    void duplicateUnclassifiedNamesAppendIdsWithoutAddingAClassPlaceholder() {
        Map<String, String> labels = resolve(Map.of(
                "first", List.of(source(null, "Biopsy", "id-2")),
                "second", List.of(source(null, "Biopsy", "id-1"))
        ));

        assertEquals("Biopsy [ID: id-2]", labels.get("first"));
        assertEquals("Biopsy [ID: id-1]", labels.get("second"));
    }

    @Test
    void labelFormattingHelpersDefineTheExportedPunctuation() {
        assertEquals(
                "Biopsy Tumor",
                OverviewWellLabelResolver.combineAnnotationAndClass("Biopsy", "Tumor")
        );
        assertEquals(
                "Biopsy Tumor [IDs: id-1, id-2]",
                OverviewWellLabelResolver.appendObjectIds(
                        "Biopsy Tumor",
                        List.of("id-1", "id-2")
                )
        );
    }

    private static Map<String, String> resolve(
            Map<String, List<OverviewWellLabelResolver.AnnotationLabelSource>> sources
    ) {
        return OverviewWellLabelResolver.resolve(sources.keySet(), sources);
    }

    private static OverviewWellLabelResolver.AnnotationLabelSource source(
            String className,
            String annotationName,
            String objectId
    ) {
        return new OverviewWellLabelResolver.AnnotationLabelSource(
                className,
                annotationName,
                objectId
        );
    }
}
