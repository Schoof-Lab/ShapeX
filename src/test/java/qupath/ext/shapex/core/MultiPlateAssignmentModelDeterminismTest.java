package qupath.ext.shapex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MultiPlateAssignmentModelDeterminismTest {

    private static final String SEED = "424242";

    @Test
    void randomizedAssignmentUsesCanonicalSampleOrderAcrossCollectionImplementations() {
        List<String> samples = sampleNames("Sample", 34);
        MultiPlateAssignmentModel expectedModel = configuredSharedModel(samples, true);
        LayoutState expected = snapshot(expectedModel);

        List<Collection<String>> equivalentInputs = List.of(
                reversed(samples),
                new LinkedHashSet<>(reversed(samples)),
                new HashSet<>(samples)
        );

        for (Collection<String> equivalentInput : equivalentInputs) {
            MultiPlateAssignmentModel actualModel = configuredSharedModel(equivalentInput, true);

            // This guards the original failure mode: the occupied slots matched while
            // the sample identities assigned to those slots changed.
            assertEquals(
                    Set.copyOf(expectedModel.getSampleLocations().values()),
                    Set.copyOf(actualModel.getSampleLocations().values()),
                    "Equivalent inputs must occupy the same plate slots"
            );
            assertEquals(
                    expected,
                    snapshot(actualModel),
                    "The complete sample-to-plate/well assignment must not depend on collection order"
            );
        }
    }

    @Test
    void repeatedRecomputesAndSeedRestorationReproduceTheCompleteLayout() {
        MultiPlateAssignmentModel model = configuredSharedModel(sampleNames("Sample", 34), true);
        LayoutState expected = snapshot(model);

        model.setSeed(SEED);
        assertEquals(expected, snapshot(model), "Reapplying the seed must be idempotent");

        model.setSharedPlateDefaults(linkedSet("D6", "B3", "A1"), 2);
        assertEquals(expected, snapshot(model), "Reapplying equivalent settings must be idempotent");

        model.setNegativeControlCount(2);
        assertEquals(expected, snapshot(model), "Reapplying the negative-control count must be idempotent");

        model.setSeed("8675309");
        model.setSeed(SEED);
        assertEquals(expected, snapshot(model), "Restoring a seed must restore every sample and control well");
    }

    @Test
    void temporaryCapacityPressureDoesNotReplaceTheRequestedPlateCount() {
        List<String> samples = sampleNames("Sample", 8);
        MultiPlateAssignmentModel baselineModel = new MultiPlateAssignmentModel(
                samples,
                2,
                3,
                SEED,
                2,
                true
        );
        LayoutState baseline = snapshot(baselineModel);

        MultiPlateAssignmentModel excludedWellModel = new MultiPlateAssignmentModel(
                samples,
                2,
                3,
                SEED,
                2,
                true
        );
        excludedWellModel.setSharedPlateDefaults(linkedSet("A1", "A2", "A3"), 0);
        assertEquals(3, excludedWellModel.getPlateCount(), "Reduced capacity must add an effective plate");
        assertEquals(2, excludedWellModel.getRequestedPlateCount());
        excludedWellModel.setSharedPlateDefaults(Set.of(), 0);
        assertEquals(
                baseline,
                snapshot(excludedWellModel),
                "Restoring exclusions must restore the layout requested before the capacity change"
        );

        MultiPlateAssignmentModel negativeControlModel = new MultiPlateAssignmentModel(
                samples,
                2,
                3,
                SEED,
                2,
                true
        );
        negativeControlModel.setSharedPlateDefaults(Set.of(), 3);
        assertEquals(3, negativeControlModel.getPlateCount(), "Controls must add an effective plate when required");
        assertEquals(2, negativeControlModel.getRequestedPlateCount());
        negativeControlModel.setSharedPlateDefaults(Set.of(), 0);
        assertEquals(
                baseline,
                snapshot(negativeControlModel),
                "Restoring controls must restore the layout requested before the capacity change"
        );

        negativeControlModel.setSharedPlateDefaults(Set.of(), 3);
        assertTrue(negativeControlModel.addPlate(), "Add must operate on the effective plate count");
        assertEquals(4, negativeControlModel.getPlateCount());
        assertEquals(4, negativeControlModel.getRequestedPlateCount());
        negativeControlModel.setSharedPlateDefaults(Set.of(), 0);
        assertEquals(4, negativeControlModel.getPlateCount(), "An explicitly added plate must remain requested");
        assertTrue(negativeControlModel.removePlate());
        assertEquals(3, negativeControlModel.getPlateCount());
        assertEquals(3, negativeControlModel.getRequestedPlateCount());
        negativeControlModel.resetPlateCountToMinimumRequired();
        assertEquals(2, negativeControlModel.getRequestedPlateCount());
        assertEquals(baseline, snapshot(negativeControlModel));
    }

    @Test
    void nonRandomizedAssignmentUsesCanonicalSampleOrder() {
        List<String> samples = sampleNames("Sample", 34);

        LayoutState expected = snapshot(configuredSharedModel(samples, false));
        LayoutState actual = snapshot(configuredSharedModel(reversed(samples), false));

        assertEquals(
                expected,
                actual,
                "Disabling randomization must still produce one canonical layout for the same samples"
        );
    }

    @Test
    void disabledSamplesCanBeRestoredAfterAPlateRebuild() {
        List<String> samples = sampleNames("Sample", 12);
        MultiPlateAssignmentModel original = new MultiPlateAssignmentModel(
                samples,
                4,
                6,
                SEED,
                1,
                true
        );
        var sampleEntry = original.getPlate(0).getSampleToWell().entrySet().iterator().next();
        assertTrue(original.disableSampleAtCurrentWell(sampleEntry.getValue()));

        MultiPlateAssignmentModel rebuilt = new MultiPlateAssignmentModel(
                reversed(samples),
                8,
                12,
                SEED,
                1,
                true
        );
        rebuilt.setDisabledSamples(Set.of(sampleEntry.getKey(), "Sample-not-present"));

        assertEquals(Set.of(sampleEntry.getKey()), rebuilt.getDisabledSamples());
        assertEquals(
                samples.size() - 1,
                rebuilt.getEnabledSampleLocations().size(),
                "Restored disabled samples must remain excluded from enabled output"
        );

        rebuilt.setDisabledSamples(Set.of());
        assertEquals(Set.of(), rebuilt.getDisabledSamples());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fixedPlateGroupsUseCanonicalMemberOrder(boolean randomizeSamples) {
        List<String> firstPlate = sampleNames("Image-A-Sample", 12);
        List<String> secondPlate = sampleNames("Image-B-Sample", 10);

        List<Collection<String>> canonicalGroups = List.of(firstPlate, secondPlate);
        List<Collection<String>> reorderedGroups = List.of(
                reversed(firstPlate),
                new HashSet<>(secondPlate)
        );

        MultiPlateAssignmentModel expectedModel = configuredFixedModel(canonicalGroups, randomizeSamples);
        MultiPlateAssignmentModel actualModel = configuredFixedModel(reorderedGroups, randomizeSamples);

        assertEquals(
                snapshot(expectedModel),
                snapshot(actualModel),
                "Plate membership is significant, but member iteration order within a plate must not be"
        );
    }

    @Test
    void uniquePerPlateSettingsRemainDeterministicAcrossSampleAndSetOrder() {
        List<String> samples = sampleNames("Sample", 30);

        MultiPlateAssignmentModel expectedModel = configuredUniqueModel(
                samples,
                List.of(
                        linkedSet("A1", "B2"),
                        linkedSet("A4", "C3", "D4"),
                        linkedSet("B1")
                )
        );
        MultiPlateAssignmentModel actualModel = configuredUniqueModel(
                reversed(samples),
                List.of(
                        linkedSet("B2", "A1"),
                        linkedSet("D4", "C3", "A4"),
                        new HashSet<>(Set.of("B1"))
                )
        );

        assertEquals(
                snapshot(expectedModel),
                snapshot(actualModel),
                "Per-plate exclusions, controls, and complete sample mappings must be deterministic"
        );
    }

    private static MultiPlateAssignmentModel configuredSharedModel(
            Collection<String> samples,
            boolean randomizeSamples
    ) {
        MultiPlateAssignmentModel model = new MultiPlateAssignmentModel(
                samples,
                4,
                6,
                SEED,
                2,
                randomizeSamples
        );
        model.setSharedPlateDefaults(linkedSet("A1", "B3", "D6"), 2);
        return model;
    }

    private static MultiPlateAssignmentModel configuredFixedModel(
            List<? extends Collection<String>> fixedPlateSamples,
            boolean randomizeSamples
    ) {
        MultiPlateAssignmentModel model = new MultiPlateAssignmentModel(
                fixedPlateSamples,
                4,
                6,
                SEED,
                randomizeSamples
        );
        model.setSharedPlateDefaults(linkedSet("A1", "D6"), 1);
        return model;
    }

    private static MultiPlateAssignmentModel configuredUniqueModel(
            Collection<String> samples,
            List<Set<String>> excludedWellsByPlate
    ) {
        MultiPlateAssignmentModel model = new MultiPlateAssignmentModel(
                samples,
                4,
                6,
                SEED,
                3,
                true
        );
        model.setUniquePlateSettings(true);
        model.setPlateSpecificSettings(excludedWellsByPlate, List.of(1, 2, 3));
        return model;
    }

    private static LayoutState snapshot(MultiPlateAssignmentModel model) {
        List<PlateState> plates = IntStream.range(0, model.getPlateCount())
                .mapToObj(model::getPlate)
                .map(plate -> new PlateState(
                        plate.getSeed(),
                        Map.copyOf(plate.getSampleToWell()),
                        Set.copyOf(plate.getNegativeControlWells()),
                        Set.copyOf(plate.getExcludedWells())
                ))
                .toList();

        return new LayoutState(
                model.getSeed(),
                model.isRandomizeSamples(),
                model.getSampleCount(),
                List.copyOf(plates),
                Map.copyOf(model.getSampleLocations()),
                Set.copyOf(model.getDisabledSamples())
        );
    }

    private static List<String> sampleNames(String prefix, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> "%s-%02d".formatted(prefix, index))
                .toList();
    }

    private static List<String> reversed(List<String> values) {
        List<String> reversed = new ArrayList<>(values);
        Collections.reverse(reversed);
        return reversed;
    }

    private static LinkedHashSet<String> linkedSet(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return result;
    }

    private record LayoutState(
            String seed,
            boolean randomizeSamples,
            int sampleCount,
            List<PlateState> plates,
            Map<String, PlateLocation> sampleLocations,
            Set<String> disabledSamples
    ) {}

    private record PlateState(
            String seed,
            Map<String, String> sampleToWell,
            Set<String> negativeControlWells,
            Set<String> excludedWells
    ) {}
}
