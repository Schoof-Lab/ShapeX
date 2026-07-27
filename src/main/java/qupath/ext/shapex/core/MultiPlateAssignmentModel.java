package qupath.ext.shapex.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MultiPlateAssignmentModel {

    private final int rows;
    private final int cols;

    private final List<String> samples;
    private final List<List<String>> fixedPlateSamples;
    private final Set<String> manualExcludedWells = new HashSet<>();
    private final List<Set<String>> manualExcludedWellsByPlate = new ArrayList<>();

    private String seed;

    private final List<PlateAssignmentModel> plates = new ArrayList<>();
    private final Map<String, PlateLocation> sampleLocations = new HashMap<>();
    private final Set<String> disabledSamples = new HashSet<>();

    private int currentPlateIndex = 0;
    private int negativeControlCount = 0;
    private final List<Integer> negativeControlCountsByPlate = new ArrayList<>();
    private int requestedPlateCount = 1;
    private boolean randomizeSamples = true;
    private boolean uniquePlateSettings = false;

    public MultiPlateAssignmentModel(
            Set<String> samples,
            int rows,
            int cols,
            String seed
    ) {
        this(samples, rows, cols, seed, 1, true);
    }

    public MultiPlateAssignmentModel(
            Set<String> samples,
            int rows,
            int cols,
            String seed,
            int desiredPlateCount
    ) {
        this(samples, rows, cols, seed, desiredPlateCount, true);
    }

    public MultiPlateAssignmentModel(
            Collection<String> samples,
            int rows,
            int cols,
            String seed,
            int desiredPlateCount,
            boolean randomizeSamples
    ) {
        this(samples, null, rows, cols, seed, desiredPlateCount, randomizeSamples);
    }

    public MultiPlateAssignmentModel(
            List<? extends Collection<String>> fixedPlateSamples,
            int rows,
            int cols,
            String seed,
            boolean randomizeSamples
    ) {
        this(null, fixedPlateSamples, rows, cols, seed, 1, randomizeSamples);
    }

    private MultiPlateAssignmentModel(
            Collection<String> samples,
            List<? extends Collection<String>> fixedPlateSamples,
            int rows,
            int cols,
            String seed,
            int desiredPlateCount,
            boolean randomizeSamples
    ) {
        this.fixedPlateSamples = normalizeFixedPlateSamples(fixedPlateSamples);
        this.samples = this.fixedPlateSamples.isEmpty()
                ? normalizeSamples(samples)
                : flattenFixedPlateSamples(this.fixedPlateSamples);
        this.rows = rows;
        this.cols = cols;
        this.seed = seed == null || seed.isBlank()
                ? generateRandomSeed(6)
                : seed;
        this.requestedPlateCount = hasFixedPlateAssignments()
                ? Math.max(1, this.fixedPlateSamples.size())
                : Math.max(1, desiredPlateCount);
        this.randomizeSamples = randomizeSamples;

        recompute();
    }

    /* ============================================================
     *  Core recompute logic
     * ============================================================ */

    private void recompute() {

        plates.clear();
        sampleLocations.clear();

        List<String> sampleList = new ArrayList<>(samples);
        disabledSamples.removeIf(sample -> !samples.contains(sample));

        long seedValue = parseSeedValue();

        Random random = new Random(seedValue);
        int plateCount = resolvePlateCount(sampleList);

        List<Map<String, String>> sampleToWellByPlate = new ArrayList<>();
        List<Set<String>> negativeControlsByPlate = new ArrayList<>();
        for (int p = 0; p < plateCount; p++) {
            sampleToWellByPlate.add(new HashMap<>());
            negativeControlsByPlate.add(new HashSet<>());
        }

        if (hasFixedPlateAssignments()) {
            assignFixedPlateSamples(seedValue, sampleToWellByPlate, negativeControlsByPlate);
        } else if (randomizeSamples) {
            assignRandomizedSamples(
                    sampleList,
                    seedValue,
                    random,
                    sampleToWellByPlate,
                    negativeControlsByPlate
            );
        } else {
            assignSequentialSamples(seedValue, sampleList, sampleToWellByPlate, negativeControlsByPlate);
        }

        buildPlateModels(seedValue, sampleToWellByPlate, negativeControlsByPlate, plateCount);
    }

    private int resolvePlateCount(List<String> sampleList) {
        if (hasFixedPlateAssignments()) {
            int plateCount = Math.max(1, fixedPlateSamples.size());
            if (uniquePlateSettings) {
                ensureUniquePlateSettingsCapacity(plateCount);
            }
            return plateCount;
        }

        int minimumRequiredPlateCount = getMinimumRequiredPlateCount(sampleList);
        int maximumAllowedPlateCount = Math.max(1, sampleList.size());
        int plateCount = Math.max(minimumRequiredPlateCount, requestedPlateCount);
        plateCount = Math.min(plateCount, maximumAllowedPlateCount);
        plateCount = Math.max(1, plateCount);
        if (uniquePlateSettings) {
            ensureUniquePlateSettingsCapacity(plateCount);
        }
        return plateCount;
    }

    private void assignFixedPlateSamples(
            long seedValue,
            List<Map<String, String>> sampleToWellByPlate,
            List<Set<String>> negativeControlsByPlate
    ) {
        for (int p = 0; p < fixedPlateSamples.size(); p++) {
            List<String> plateSamples = new ArrayList<>(fixedPlateSamples.get(p));
            if (randomizeSamples) {
                Collections.shuffle(plateSamples, new Random(seedValue + 4001L * p));
            }

            List<String> usableWells = allUsableWells(getExcludedWellsForPlateInternal(p));
            if (randomizeSamples) {
                Collections.shuffle(usableWells, new Random(seedValue + 1009L * p));
            }

            Set<String> plateNegativeWells = negativeControlsByPlate.get(p);
            initializeNegativeWellsForPlate(p, usableWells, plateNegativeWells, randomizeSamples);

            Map<String, String> plateSampleToWell = sampleToWellByPlate.get(p);
            int sampleIndex = 0;
            for (String well : usableWells) {
                if (plateNegativeWells.contains(well)) {
                    continue;
                }
                if (sampleIndex >= plateSamples.size()) {
                    break;
                }

                String sample = plateSamples.get(sampleIndex++);
                plateSampleToWell.put(sample, well);
                sampleLocations.put(sample, new PlateLocation(p, well));
            }
        }
    }

    private void assignRandomizedSamples(
            List<String> sampleList,
            long seedValue,
            Random random,
            List<Map<String, String>> sampleToWellByPlate,
            List<Set<String>> negativeControlsByPlate
    ) {
        Collections.shuffle(sampleList, random);

        List<PlateLocation> allSampleSlots = new ArrayList<>();
        for (int p = 0; p < sampleToWellByPlate.size(); p++) {
            List<String> shuffledWells = allUsableWells(getExcludedWellsForPlateInternal(p));
            Collections.shuffle(shuffledWells, new Random(seedValue + 1009L * p));

            Set<String> plateNegativeWells = negativeControlsByPlate.get(p);
            initializeNegativeWellsForPlate(p, shuffledWells, plateNegativeWells, true);

            for (String well : shuffledWells) {
                if (!plateNegativeWells.contains(well)) {
                    allSampleSlots.add(new PlateLocation(p, well));
                }
            }
        }

        Collections.shuffle(allSampleSlots, random);

        for (int i = 0; i < sampleList.size() && i < allSampleSlots.size(); i++) {
            String sample = sampleList.get(i);
            PlateLocation loc = allSampleSlots.get(i);
            sampleToWellByPlate.get(loc.plateIndex()).put(sample, loc.well());
            sampleLocations.put(sample, loc);
        }
    }

    private void assignSequentialSamples(
            long seedValue,
            List<String> sampleList,
            List<Map<String, String>> sampleToWellByPlate,
            List<Set<String>> negativeControlsByPlate
    ) {
        int sampleIndex = 0;

        for (int p = 0; p < sampleToWellByPlate.size(); p++) {
            List<String> usableWells = allUsableWells(getExcludedWellsForPlateInternal(p));
            Set<String> plateNegativeWells = negativeControlsByPlate.get(p);
            initializeNegativeWellsForPlate(p, usableWells, plateNegativeWells, false);

            Map<String, String> plateSampleToWell = sampleToWellByPlate.get(p);
            for (String well : usableWells) {
                if (plateNegativeWells.contains(well)) {
                    continue;
                }
                if (sampleIndex >= sampleList.size()) {
                    break;
                }

                String sample = sampleList.get(sampleIndex++);
                plateSampleToWell.put(sample, well);
                sampleLocations.put(sample, new PlateLocation(p, well));
            }

            if (sampleIndex >= sampleList.size()) {
                break;
            }
        }
    }

    private void initializeNegativeWellsForPlate(
            int plateIndex,
            List<String> usableWells,
            Set<String> plateNegativeWells,
            boolean fromFront
    ) {
        int negativePerPlate = Math.min(getNegativeControlCountForPlateInternal(plateIndex), usableWells.size());
        if (fromFront) {
            for (int i = 0; i < negativePerPlate; i++) {
                plateNegativeWells.add(usableWells.get(i));
            }
            return;
        }

        for (int i = usableWells.size() - 1; i >= 0 && plateNegativeWells.size() < negativePerPlate; i--) {
            plateNegativeWells.add(usableWells.get(i));
        }
    }

    /* ============================================================
     *  Public API
     * ============================================================ */

    public int getPlateCount() {
        return plates.size();
    }

    public int getRows() {
        return rows;
    }

    public int getColumns() {
        return cols;
    }

    public int getRequestedPlateCount() {
        return requestedPlateCount;
    }

    public boolean canAddPlate() {
        if (hasFixedPlateAssignments()) {
            return false;
        }
        return getPlateCount() < getMaximumAllowedPlateCount();
    }

    public boolean canRemovePlate() {
        if (hasFixedPlateAssignments()) {
            return false;
        }
        return getPlateCount() > getMinimumRequiredPlateCount();
    }

    public boolean canResetPlateCountToMinimumRequired() {
        return !hasFixedPlateAssignments()
                && getPlateCount() > getMinimumRequiredPlateCount();
    }

    public boolean addPlate() {
        if (!canAddPlate()) {
            return false;
        }

        requestedPlateCount = getPlateCount() + 1;
        recompute();
        return true;
    }

    public boolean addBlankPlate() {
        if (!canAddPlate()) {
            return false;
        }

        int newPlateIndex = plates.size();
        requestedPlateCount = newPlateIndex + 1;
        if (uniquePlateSettings) {
            ensureUniquePlateSettingsCapacity(requestedPlateCount);
        }

        long seedValue = parseSeedValue();
        PlateAssignmentModel blankPlate = new PlateAssignmentModel(
                rows,
                cols,
                Long.toString(seedValue + (31L * newPlateIndex)),
                getExcludedWellsForPlateInternal(newPlateIndex),
                Map.of(),
                Set.of()
        );

        plates.add(blankPlate);
        return true;
    }

    public boolean removePlate() {
        if (!canRemovePlate()) {
            return false;
        }

        requestedPlateCount = getPlateCount() - 1;
        recompute();
        return true;
    }

    public void resetPlateCountToMinimumRequired() {
        if (!canResetPlateCountToMinimumRequired()) {
            return;
        }

        int minimumRequiredPlateCount = getMinimumRequiredPlateCount();
        requestedPlateCount = minimumRequiredPlateCount;
        recompute();
    }

    public boolean canRemovePlateInPlace() {
        return buildPlateRemovalPlan(currentPlateIndex) != null;
    }

    public boolean removePlateInPlace() {
        PlateRemovalPlan plan = buildPlateRemovalPlan(currentPlateIndex);
        if (plan == null) {
            return false;
        }

        for (var entry : plan.sampleMapsByPlate().entrySet()) {
            int plateIndex = entry.getKey();
            PlateAssignmentModel plate = plates.get(plateIndex);
            PlateAssignmentModel updated = new PlateAssignmentModel(
                    rows,
                    cols,
                    plate.getSeed(),
                    getExcludedWellsForPlateInternal(plateIndex),
                    entry.getValue(),
                    plate.getNegativeControlWells()
            );
            plates.set(plateIndex, updated);
        }

        plates.remove(plan.removedPlateIndex());
        removePlateSpecificSettings(plan.removedPlateIndex());
        requestedPlateCount = Math.max(1, plates.size());
        if (uniquePlateSettings) {
            ensureUniquePlateSettingsCapacity(requestedPlateCount);
        }
        currentPlateIndex = Math.max(0, plan.removedPlateIndex() - 1);
        if (currentPlateIndex >= plates.size()) {
            currentPlateIndex = Math.max(0, plates.size() - 1);
        }
        rebuildSampleLocations();
        return true;
    }

    public PlateAssignmentModel getPlate(int index) {
        if (index < 0 || index >= plates.size()) {
            return null;
        }
        return plates.get(index);
    }

    public void shuffle() {
        this.seed = generateRandomSeed(6);
        recompute();
    }

    public PlateAssignmentModel getCurrentPlate() {
        return plates.isEmpty()
                ? null
                : plates.get(currentPlateIndex);
    }

    public int getCurrentPlateIndex() {
        return currentPlateIndex;
    }

    public void nextPlate() {
        if (currentPlateIndex < plates.size() - 1) {
            currentPlateIndex++;
        }
    }

    public void previousPlate() {
        if (currentPlateIndex > 0) {
            currentPlateIndex--;
        }
    }

    public void setSeed(String seed) {
        this.seed = seed;
        recompute();
    }

    public String getSeed() {
        return seed;
    }

    public void setUniquePlateSettings(boolean uniquePlateSettings) {
        if (this.uniquePlateSettings == uniquePlateSettings) {
            return;
        }

        if (uniquePlateSettings) {
            int plateCount = getConfiguredPlateCountForUniqueSettings();
            manualExcludedWellsByPlate.clear();
            negativeControlCountsByPlate.clear();
            for (int p = 0; p < plateCount; p++) {
                manualExcludedWellsByPlate.add(new HashSet<>(manualExcludedWells));
                negativeControlCountsByPlate.add(negativeControlCount);
            }
        } else {
            Set<String> currentExcluded = new HashSet<>(getExcludedWellsForPlateInternal(currentPlateIndex));
            int currentNegativeCount = getNegativeControlCountForPlateInternal(currentPlateIndex);

            manualExcludedWells.clear();
            manualExcludedWells.addAll(currentExcluded);
            negativeControlCount = currentNegativeCount;

            manualExcludedWellsByPlate.clear();
            negativeControlCountsByPlate.clear();
        }

        this.uniquePlateSettings = uniquePlateSettings;
        recompute();
    }

    public boolean isUniquePlateSettings() {
        return uniquePlateSettings;
    }

    public void setSharedPlateDefaults(Set<String> excludedWells, int negativeControlCount) {
        manualExcludedWells.clear();
        if (excludedWells != null) {
            for (String well : excludedWells) {
                if (isWellInBounds(well)) {
                    manualExcludedWells.add(well);
                }
            }
        }

        this.negativeControlCount = Math.max(0, negativeControlCount);

        if (!uniquePlateSettings) {
            recompute();
        }
    }

    public Set<String> getSharedExcludedWellsSnapshot() {
        return new HashSet<>(manualExcludedWells);
    }

    public int getSharedNegativeControlCount() {
        return negativeControlCount;
    }

    public List<Set<String>> getExcludedWellsByPlateSnapshot() {
        List<Set<String>> snapshot = new ArrayList<>();
        int plateCount = Math.max(1, getConfiguredPlateCountForUniqueSettings());
        for (int p = 0; p < plateCount; p++) {
            snapshot.add(new HashSet<>(getExcludedWellsForPlateInternal(p)));
        }
        return snapshot;
    }

    public List<Integer> getNegativeControlCountsByPlateSnapshot() {
        List<Integer> snapshot = new ArrayList<>();
        int plateCount = Math.max(1, getConfiguredPlateCountForUniqueSettings());
        for (int p = 0; p < plateCount; p++) {
            snapshot.add(getNegativeControlCountForPlateInternal(p));
        }
        return snapshot;
    }

    public void setPlateSpecificSettings(
            List<Set<String>> excludedWellsByPlate,
            List<Integer> negativeControlCountsByPlate
    ) {
        if (!uniquePlateSettings) {
            return;
        }

        int plateCount = getConfiguredPlateCountForUniqueSettings();
        ensureUniquePlateSettingsCapacity(plateCount);

        for (int p = 0; p < plateCount; p++) {
            Set<String> targetExcluded = manualExcludedWellsByPlate.get(p);
            targetExcluded.clear();

            if (excludedWellsByPlate != null && p < excludedWellsByPlate.size()) {
                Set<String> provided = excludedWellsByPlate.get(p);
                if (provided != null) {
                    for (String well : provided) {
                        if (isWellInBounds(well)) {
                            targetExcluded.add(well);
                        }
                    }
                }
            } else {
                targetExcluded.addAll(manualExcludedWells);
            }

            int targetNegativeCount = negativeControlCount;
            if (negativeControlCountsByPlate != null && p < negativeControlCountsByPlate.size()) {
                targetNegativeCount = Math.max(0, negativeControlCountsByPlate.get(p));
            }
            this.negativeControlCountsByPlate.set(p, targetNegativeCount);
        }

        recompute();
    }

    public void toggleExclude(String well) {
        if (!isWellInBounds(well)) {
            return;
        }

        Set<String> excludedWells = getMutableExcludedWellsForCurrentPlate();
        if (excludedWells.contains(well)) {
            excludedWells.remove(well);
        } else {
            int maxExcluded = Math.max(0, rows * cols - 1);
            if (excludedWells.size() >= maxExcluded) {
                return;
            }
            excludedWells.add(well);
        }

        recompute();
    }

    public boolean toggleExcludeInPlace(String well) {
        if (!isWellInBounds(well)) {
            return false;
        }

        Set<String> excludedWells = getMutableExcludedWellsForCurrentPlate();
        boolean excluding = !excludedWells.contains(well);

        if (excluding) {
            int maxExcluded = Math.max(0, rows * cols - 1);
            if (excludedWells.size() >= maxExcluded) {
                return false;
            }
            if (!canExcludeWellInPlace(well)) {
                return false;
            }
            excludedWells.add(well);
            applyExcludeInPlace(well);
            return true;
        }

        excludedWells.remove(well);
        return true;
    }

    public void setExcludedWells(Set<String> wells) {
        Set<String> excludedWells = getMutableExcludedWellsForCurrentPlate();
        excludedWells.clear();

        if (wells != null) {
            for (String well : wells) {
                if (isWellInBounds(well)) {
                    excludedWells.add(well);
                }
            }
        }

        recompute();
    }

    public Set<String> getExcludedWells() {
        return getExcludedWells(currentPlateIndex);
    }

    public Set<String> getExcludedWells(int plateIndex) {
        return Collections.unmodifiableSet(new HashSet<>(getExcludedWellsForPlateInternal(plateIndex)));
    }

    public void setNegativeControlCount(int count) {
        int normalized = Math.max(0, count);
        if (uniquePlateSettings) {
            ensureUniquePlateSettingsCapacity(getConfiguredPlateCountForUniqueSettings());
            negativeControlCountsByPlate.set(currentPlateIndex, normalized);
        } else {
            negativeControlCount = normalized;
        }
        recompute();
    }

    public int setNegativeControlCountInPlace(int count) {
        int target = Math.max(0, count);

        if (uniquePlateSettings) {
            PlateAssignmentModel currentPlate = getCurrentPlate();
            if (currentPlate == null) {
                return 0;
            }

            Set<String> excludedWells = getExcludedWellsForPlateInternal(currentPlateIndex);
            Map<String, String> sampleMap = new HashMap<>(currentPlate.getSampleToWell());
            Set<String> negativeWells = new HashSet<>(currentPlate.getNegativeControlWells());
            int current = negativeWells.size();

            if (target == current) {
                return current;
            }

            if (target > current) {
                while (negativeWells.size() < target) {
                    String targetWell = findFirstUnusedWell(sampleMap, negativeWells, null, excludedWells);
                    if (targetWell == null) {
                        break;
                    }
                    negativeWells.add(targetWell);
                }
            } else {
                while (negativeWells.size() > target) {
                    String removeWell = findLastNegativeWell(negativeWells);
                    if (removeWell == null) {
                        break;
                    }
                    negativeWells.remove(removeWell);
                }
            }

            PlateAssignmentModel updated = new PlateAssignmentModel(
                    rows,
                    cols,
                    currentPlate.getSeed(),
                    excludedWells,
                    sampleMap,
                    negativeWells
            );
            plates.set(currentPlateIndex, updated);
            ensureUniquePlateSettingsCapacity(getConfiguredPlateCountForUniqueSettings());
            negativeControlCountsByPlate.set(currentPlateIndex, negativeWells.size());
            rebuildSampleLocations();
            return negativeWells.size();
        }

        int current = Math.max(0, negativeControlCount);
        if (target == current) {
            return current;
        }

        for (int p = 0; p < plates.size(); p++) {
            PlateAssignmentModel plate = plates.get(p);
            Set<String> excludedWells = getExcludedWellsForPlateInternal(p);
            Map<String, String> sampleMap = new HashMap<>(plate.getSampleToWell());
            Set<String> negativeWells = new HashSet<>(plate.getNegativeControlWells());

            if (target > current) {
                while (negativeWells.size() < target) {
                    String targetWell = findFirstUnusedWell(sampleMap, negativeWells, null, excludedWells);
                    if (targetWell == null) {
                        break;
                    }
                    negativeWells.add(targetWell);
                }
            } else {
                while (negativeWells.size() > target) {
                    String removeWell = findLastNegativeWell(negativeWells);
                    if (removeWell == null) {
                        break;
                    }
                    negativeWells.remove(removeWell);
                }
            }

            PlateAssignmentModel updated = new PlateAssignmentModel(
                    rows,
                    cols,
                    plate.getSeed(),
                    excludedWells,
                    sampleMap,
                    negativeWells
            );
            plates.set(p, updated);
        }

        int effective = Integer.MAX_VALUE;
        for (PlateAssignmentModel plate : plates) {
            effective = Math.min(effective, plate.getNegativeControlWells().size());
        }
        if (effective == Integer.MAX_VALUE) {
            effective = 0;
        }

        negativeControlCount = effective;
        rebuildSampleLocations();
        return negativeControlCount;
    }

    public int getNegativeControlCount() {
        return getNegativeControlCount(currentPlateIndex);
    }

    public int getNegativeControlCount(int plateIndex) {
        return getNegativeControlCountForPlateInternal(plateIndex);
    }

    public int getSampleCount() {
        return samples.size();
    }

    public void setRandomizeSamples(boolean randomizeSamples) {
        if (this.randomizeSamples == randomizeSamples) {
            return;
        }
        this.randomizeSamples = randomizeSamples;
        if (!randomizeSamples && !hasFixedPlateAssignments()) {
            requestedPlateCount = getMinimumRequiredPlateCount();
        }
        recompute();
    }

    public boolean isRandomizeSamples() {
        return randomizeSamples;
    }

    public Map<String, PlateLocation> getSampleLocations() {
        return Collections.unmodifiableMap(sampleLocations);
    }

    public Map<String, PlateLocation> getEnabledSampleLocations() {
        return sampleLocations.entrySet().stream()
                .filter(entry -> !disabledSamples.contains(entry.getKey()))
                .collect(HashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue()),
                        HashMap::putAll);
    }

    public Set<String> getDisabledSamples() {
        return Collections.unmodifiableSet(disabledSamples);
    }

    public void setDisabledSamples(Collection<String> samplesToDisable) {
        disabledSamples.clear();
        if (samplesToDisable == null) {
            return;
        }

        for (String sample : samplesToDisable) {
            if (samples.contains(sample)) {
                disabledSamples.add(sample);
            }
        }
    }

    public PlateLocation getLocationForSample(String sample) {
        return sampleLocations.get(sample);
    }

    public boolean isSampleDisabled(String sample) {
        return sample != null && disabledSamples.contains(sample);
    }

    public boolean isDisabledSampleAtCurrentWell(String well) {
        PlateAssignmentModel current = getCurrentPlate();
        if (current == null || well == null) {
            return false;
        }
        for (var entry : current.getSampleToWell().entrySet()) {
            if (well.equals(entry.getValue()) && disabledSamples.contains(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    public boolean disableSampleAtCurrentWell(String well) {
        if (well == null) {
            return false;
        }
        PlateAssignmentModel current = getCurrentPlate();
        if (current == null) {
            return false;
        }
        for (var entry : current.getSampleToWell().entrySet()) {
            if (well.equals(entry.getValue())) {
                return disabledSamples.add(entry.getKey());
            }
        }
        return false;
    }

    public boolean enableSampleAtCurrentWell(String well) {
        if (well == null) {
            return false;
        }
        PlateAssignmentModel current = getCurrentPlate();
        if (current == null) {
            return false;
        }
        for (var entry : current.getSampleToWell().entrySet()) {
            if (well.equals(entry.getValue())) {
                return disabledSamples.remove(entry.getKey());
            }
        }
        return false;
    }

    public boolean enableAllSamples() {
        if (disabledSamples.isEmpty()) {
            return false;
        }
        disabledSamples.clear();
        return true;
    }

    public boolean swapWellsOnCurrentPlate(String sourceWell, String targetWell) {
        if (sourceWell == null || targetWell == null) {
            return false;
        }
        if (sourceWell.equals(targetWell)) {
            return false;
        }
        if (!isWellInBounds(sourceWell) || !isWellInBounds(targetWell)) {
            return false;
        }

        Set<String> excludedWells = getExcludedWellsForPlateInternal(currentPlateIndex);
        if (excludedWells.contains(sourceWell) || excludedWells.contains(targetWell)) {
            return false;
        }

        PlateAssignmentModel current = getCurrentPlate();
        if (current == null) {
            return false;
        }

        Map<String, String> sampleMap = new HashMap<>(current.getSampleToWell());
        Set<String> negativeWells = new HashSet<>(current.getNegativeControlWells());

        WellContent sourceContent = getWellContent(sourceWell, sampleMap, negativeWells);
        WellContent targetContent = getWellContent(targetWell, sampleMap, negativeWells);

        if (isSampleDisabled(sourceContent.sampleName()) || isSampleDisabled(targetContent.sampleName())) {
            return false;
        }
        if (sourceContent.isUnused() && targetContent.isUnused()) {
            return false;
        }

        if (sourceContent.sampleName() != null) {
            sampleMap.remove(sourceContent.sampleName());
        }
        if (targetContent.sampleName() != null) {
            sampleMap.remove(targetContent.sampleName());
        }
        negativeWells.remove(sourceWell);
        negativeWells.remove(targetWell);

        applyContentToWell(targetContent, sourceWell, sampleMap, negativeWells);
        applyContentToWell(sourceContent, targetWell, sampleMap, negativeWells);

        PlateAssignmentModel updatedPlate = new PlateAssignmentModel(
                rows,
                cols,
                current.getSeed(),
                excludedWells,
                sampleMap,
                negativeWells
        );
        plates.set(currentPlateIndex, updatedPlate);
        rebuildSampleLocations();
        return true;
    }

    public boolean moveSampleFromCurrentPlateToPlate(String sourceWell, int targetPlateIndex) {
        if (sourceWell == null || !isWellInBounds(sourceWell)) {
            return false;
        }
        if (targetPlateIndex < 0 || targetPlateIndex >= plates.size()) {
            return false;
        }
        if (targetPlateIndex == currentPlateIndex) {
            return false;
        }

        Set<String> sourceExcludedWells = getExcludedWellsForPlateInternal(currentPlateIndex);
        if (sourceExcludedWells.contains(sourceWell)) {
            return false;
        }

        PlateAssignmentModel sourcePlate = getCurrentPlate();
        PlateAssignmentModel targetPlate = getPlate(targetPlateIndex);
        if (sourcePlate == null || targetPlate == null) {
            return false;
        }

        Map<String, String> sourceSampleMap = new HashMap<>(sourcePlate.getSampleToWell());
        Set<String> sourceNegativeWells = new HashSet<>(sourcePlate.getNegativeControlWells());
        WellContent sourceContent = getWellContent(sourceWell, sourceSampleMap, sourceNegativeWells);

        String sampleName = sourceContent.sampleName();
        if (sampleName == null) {
            return false;
        }
        if (isSampleDisabled(sampleName)) {
            return false;
        }

        Set<String> targetExcludedWells = getExcludedWellsForPlateInternal(targetPlateIndex);
        Map<String, String> targetSampleMap = new HashMap<>(targetPlate.getSampleToWell());
        Set<String> targetNegativeWells = new HashSet<>(targetPlate.getNegativeControlWells());
        String targetWell = findFirstUnusedWell(targetSampleMap, targetNegativeWells, null, targetExcludedWells);
        if (targetWell == null) {
            return false;
        }

        sourceSampleMap.remove(sampleName);
        targetSampleMap.put(sampleName, targetWell);

        PlateAssignmentModel updatedSource = new PlateAssignmentModel(
                rows,
                cols,
                sourcePlate.getSeed(),
                sourceExcludedWells,
                sourceSampleMap,
                sourceNegativeWells
        );
        PlateAssignmentModel updatedTarget = new PlateAssignmentModel(
                rows,
                cols,
                targetPlate.getSeed(),
                targetExcludedWells,
                targetSampleMap,
                targetNegativeWells
        );

        plates.set(currentPlateIndex, updatedSource);
        plates.set(targetPlateIndex, updatedTarget);
        rebuildSampleLocations();
        return true;
    }

    /* ============================================================
     *  Helpers
     * ============================================================ */

    private int getMinimumRequiredPlateCount() {
        return getMinimumRequiredPlateCount(samples);
    }

    private int getMinimumRequiredPlateCount(List<String> sampleList) {
        if (hasFixedPlateAssignments()) {
            return Math.max(1, fixedPlateSamples.size());
        }

        if (sampleList.isEmpty()) {
            return 1;
        }

        if (!uniquePlateSettings) {
            int sampleCapacityPerPlate = computeSharedSampleCapacityPerPlate();
            if (sampleCapacityPerPlate <= 0) {
                return sampleList.size();
            }
            return Math.max(1, (int) Math.ceil((double) sampleList.size() / sampleCapacityPerPlate));
        }

        int remainingSamples = sampleList.size();
        int plateCount = Math.max(1, requestedPlateCount);
        ensureUniquePlateSettingsCapacity(plateCount);

        int requiredPlates = 0;
        for (int p = 0; p < plateCount && remainingSamples > 0; p++) {
            requiredPlates++;
            remainingSamples -= computeSampleCapacityForPlate(p);
        }

        if (remainingSamples <= 0) {
            return Math.max(1, requiredPlates);
        }

        int defaultCapacity = computeSharedSampleCapacityPerPlate();
        if (defaultCapacity <= 0) {
            return Math.max(requiredPlates, sampleList.size());
        }

        requiredPlates += (int) Math.ceil((double) remainingSamples / defaultCapacity);
        return Math.max(1, requiredPlates);
    }

    private int getMaximumAllowedPlateCount() {
        if (hasFixedPlateAssignments()) {
            return Math.max(1, fixedPlateSamples.size());
        }
        return Math.max(1, samples.size());
    }

    private boolean hasFixedPlateAssignments() {
        return !fixedPlateSamples.isEmpty();
    }

    private void buildPlateModels(
            long seedValue,
            List<Map<String, String>> sampleToWellByPlate,
            List<Set<String>> negativeControlsByPlate,
            int plateCount
    ) {
        for (int p = 0; p < plateCount; p++) {
            String plateSeedString = Long.toString(seedValue + (31L * p));

            PlateAssignmentModel plate = new PlateAssignmentModel(
                    rows,
                    cols,
                    plateSeedString,
                    getExcludedWellsForPlateInternal(p),
                    sampleToWellByPlate.get(p),
                    negativeControlsByPlate.get(p)
            );

            plates.add(plate);
        }

        if (currentPlateIndex >= plates.size()) {
            currentPlateIndex = Math.max(0, plates.size() - 1);
        }
    }

    private static List<String> normalizeSamples(Collection<String> samples) {
        LinkedHashSet<String> uniqueSamples = new LinkedHashSet<>();
        if (samples != null) {
            for (String sample : samples) {
                if (sample == null || sample.isBlank()) {
                    continue;
                }
                uniqueSamples.add(sample);
            }
        }

        // A seeded shuffle is deterministic only when its input order is deterministic.
        List<String> normalized = new ArrayList<>(uniqueSamples);
        Collections.sort(normalized);
        return normalized;
    }

    private static List<List<String>> normalizeFixedPlateSamples(
            List<? extends Collection<String>> groupedSamples
    ) {
        if (groupedSamples == null) {
            return List.of();
        }

        List<List<String>> normalized = new ArrayList<>();
        Set<String> seenSamples = new LinkedHashSet<>();

        for (Collection<String> group : groupedSamples) {
            LinkedHashSet<String> uniqueGroup = new LinkedHashSet<>();
            if (group != null) {
                for (String sample : group) {
                    if (sample == null || sample.isBlank()) {
                        continue;
                    }
                    if (seenSamples.add(sample)) {
                        uniqueGroup.add(sample);
                    }
                }
            }
            // Annotation iteration order must not affect a seeded per-image assignment.
            List<String> normalizedGroup = new ArrayList<>(uniqueGroup);
            Collections.sort(normalizedGroup);
            normalized.add(normalizedGroup);
        }

        return normalized;
    }

    private static List<String> flattenFixedPlateSamples(List<List<String>> groupedSamples) {
        List<String> flattened = new ArrayList<>();
        for (List<String> group : groupedSamples) {
            flattened.addAll(group);
        }
        return flattened;
    }

    private PlateRemovalPlan buildPlateRemovalPlan(int plateIndex) {
        if (hasFixedPlateAssignments()) {
            return null;
        }
        if (plates.size() <= 1 || plateIndex < 0 || plateIndex >= plates.size()) {
            return null;
        }

        List<String> samplesToMove = getSamplesInWellOrder(plates.get(plateIndex));
        if (!samplesToMove.isEmpty() && plateIndex == 0) {
            return null;
        }

        Map<Integer, Map<String, String>> sampleMapsByPlate = new HashMap<>();
        int targetPlateIndex = plateIndex - 1;

        for (String sample : samplesToMove) {
            boolean placed = false;
            while (targetPlateIndex >= 0) {
                PlateAssignmentModel targetPlate = plates.get(targetPlateIndex);
                Map<String, String> targetSampleMap = sampleMapsByPlate.computeIfAbsent(
                        targetPlateIndex,
                        p -> new HashMap<>(targetPlate.getSampleToWell())
                );

                String targetWell = findFirstUnusedWell(
                        targetSampleMap,
                        targetPlate.getNegativeControlWells(),
                        null,
                        getExcludedWellsForPlateInternal(targetPlateIndex)
                );
                if (targetWell == null) {
                    targetPlateIndex--;
                    continue;
                }

                targetSampleMap.put(sample, targetWell);
                placed = true;
                break;
            }

            if (!placed) {
                return null;
            }
        }

        return new PlateRemovalPlan(plateIndex, sampleMapsByPlate);
    }

    private List<String> getSamplesInWellOrder(PlateAssignmentModel plate) {
        Map<String, String> sampleByWell = new HashMap<>();
        for (var entry : plate.getSampleToWell().entrySet()) {
            sampleByWell.put(entry.getValue(), entry.getKey());
        }

        List<String> samplesInWellOrder = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String row = PlateLayoutUtils.rowLabel(r);
            for (int c = 1; c <= cols; c++) {
                String sample = sampleByWell.get(row + c);
                if (sample != null) {
                    samplesInWellOrder.add(sample);
                }
            }
        }
        return samplesInWellOrder;
    }

    private void removePlateSpecificSettings(int plateIndex) {
        if (!uniquePlateSettings) {
            return;
        }

        if (plateIndex >= 0 && plateIndex < manualExcludedWellsByPlate.size()) {
            manualExcludedWellsByPlate.remove(plateIndex);
        }
        if (plateIndex >= 0 && plateIndex < negativeControlCountsByPlate.size()) {
            negativeControlCountsByPlate.remove(plateIndex);
        }
    }

    private boolean canExcludeWellInPlace(String excludedWell) {
        if (uniquePlateSettings) {
            PlateAssignmentModel currentPlate = getCurrentPlate();
            if (currentPlate == null) {
                return false;
            }

            Set<String> excludedWells = getExcludedWellsForPlateInternal(currentPlateIndex);
            Map<String, String> sampleMap = new HashMap<>(currentPlate.getSampleToWell());
            Set<String> negativeWells = new HashSet<>(currentPlate.getNegativeControlWells());

            WellContent content = getWellContent(excludedWell, sampleMap, negativeWells);
            if (content.isUnused()) {
                return true;
            }

            return findFirstUnusedWell(sampleMap, negativeWells, excludedWell, excludedWells) != null;
        }

        for (int p = 0; p < plates.size(); p++) {
            PlateAssignmentModel plate = plates.get(p);
            Set<String> excludedWells = getExcludedWellsForPlateInternal(p);
            Map<String, String> sampleMap = new HashMap<>(plate.getSampleToWell());
            Set<String> negativeWells = new HashSet<>(plate.getNegativeControlWells());

            WellContent content = getWellContent(excludedWell, sampleMap, negativeWells);
            if (content.isUnused()) {
                continue;
            }

            String target = findFirstUnusedWell(sampleMap, negativeWells, excludedWell, excludedWells);
            if (target == null) {
                return false;
            }
        }
        return true;
    }

    private void applyExcludeInPlace(String excludedWell) {
        int start = uniquePlateSettings ? currentPlateIndex : 0;
        int end = uniquePlateSettings ? currentPlateIndex + 1 : plates.size();

        for (int p = start; p < end; p++) {
            PlateAssignmentModel plate = plates.get(p);
            Set<String> excludedWells = getExcludedWellsForPlateInternal(p);
            Map<String, String> sampleMap = new HashMap<>(plate.getSampleToWell());
            Set<String> negativeWells = new HashSet<>(plate.getNegativeControlWells());

            WellContent content = getWellContent(excludedWell, sampleMap, negativeWells);
            if (content.sampleName() != null) {
                sampleMap.remove(content.sampleName());
            }
            negativeWells.remove(excludedWell);

            if (!content.isUnused()) {
                String target = findFirstUnusedWell(sampleMap, negativeWells, excludedWell, excludedWells);
                if (target != null) {
                    applyContentToWell(content, target, sampleMap, negativeWells);
                }
            }

            PlateAssignmentModel updated = new PlateAssignmentModel(
                    rows,
                    cols,
                    plate.getSeed(),
                    excludedWells,
                    sampleMap,
                    negativeWells
            );
            plates.set(p, updated);
        }
        rebuildSampleLocations();
    }

    private void rebuildSampleLocations() {
        sampleLocations.clear();
        for (int p = 0; p < plates.size(); p++) {
            PlateAssignmentModel plate = plates.get(p);
            for (var entry : plate.getSampleToWell().entrySet()) {
                sampleLocations.put(entry.getKey(), new PlateLocation(p, entry.getValue()));
            }
        }
    }

    private WellContent getWellContent(
            String well,
            Map<String, String> sampleMap,
            Set<String> negativeWells
    ) {
        for (var entry : sampleMap.entrySet()) {
            if (well.equals(entry.getValue())) {
                return new WellContent(entry.getKey(), false);
            }
        }
        if (negativeWells.contains(well)) {
            return new WellContent(null, true);
        }
        return new WellContent(null, false);
    }

    private String findFirstUnusedWell(
            Map<String, String> sampleMap,
            Set<String> negativeWells,
            String pendingExcludedWell,
            Set<String> excludedWells
    ) {
        Set<String> occupiedWells = new HashSet<>(sampleMap.values());
        occupiedWells.addAll(negativeWells);

        for (int r = 0; r < rows; r++) {
            String row = PlateLayoutUtils.rowLabel(r);
            for (int c = 1; c <= cols; c++) {
                String well = row + c;
                if (well.equals(pendingExcludedWell)) {
                    continue;
                }
                if (excludedWells.contains(well)) {
                    continue;
                }
                if (occupiedWells.contains(well)) {
                    continue;
                }
                return well;
            }
        }
        return null;
    }

    private String findLastNegativeWell(Set<String> negativeWells) {
        for (int r = rows - 1; r >= 0; r--) {
            String row = PlateLayoutUtils.rowLabel(r);
            for (int c = cols; c >= 1; c--) {
                String well = row + c;
                if (negativeWells.contains(well)) {
                    return well;
                }
            }
        }
        return null;
    }

    private void applyContentToWell(
            WellContent content,
            String well,
            Map<String, String> sampleMap,
            Set<String> negativeWells
    ) {
        if (content.sampleName() != null) {
            sampleMap.put(content.sampleName(), well);
        } else if (content.negativeControl()) {
            negativeWells.add(well);
        }
    }

    private int getConfiguredPlateCountForUniqueSettings() {
        if (hasFixedPlateAssignments()) {
            return Math.max(1, fixedPlateSamples.size());
        }
        return Math.max(1, Math.max(requestedPlateCount, plates.size()));
    }

    private void ensureUniquePlateSettingsCapacity(int plateCount) {
        if (!uniquePlateSettings) {
            return;
        }

        int normalizedPlateCount = Math.max(1, plateCount);
        while (manualExcludedWellsByPlate.size() < normalizedPlateCount) {
            manualExcludedWellsByPlate.add(new HashSet<>(manualExcludedWells));
        }
        while (negativeControlCountsByPlate.size() < normalizedPlateCount) {
            negativeControlCountsByPlate.add(Math.max(0, negativeControlCount));
        }

        while (manualExcludedWellsByPlate.size() > normalizedPlateCount) {
            manualExcludedWellsByPlate.remove(manualExcludedWellsByPlate.size() - 1);
        }
        while (negativeControlCountsByPlate.size() > normalizedPlateCount) {
            negativeControlCountsByPlate.remove(negativeControlCountsByPlate.size() - 1);
        }

        if (currentPlateIndex >= normalizedPlateCount) {
            currentPlateIndex = Math.max(0, normalizedPlateCount - 1);
        }
    }

    private Set<String> getMutableExcludedWellsForCurrentPlate() {
        if (!uniquePlateSettings) {
            return manualExcludedWells;
        }

        ensureUniquePlateSettingsCapacity(getConfiguredPlateCountForUniqueSettings());
        return manualExcludedWellsByPlate.get(currentPlateIndex);
    }

    private Set<String> getExcludedWellsForPlateInternal(int plateIndex) {
        if (!uniquePlateSettings) {
            return manualExcludedWells;
        }

        ensureUniquePlateSettingsCapacity(Math.max(getConfiguredPlateCountForUniqueSettings(), plateIndex + 1));
        int safeIndex = Math.max(0, Math.min(plateIndex, manualExcludedWellsByPlate.size() - 1));
        return manualExcludedWellsByPlate.get(safeIndex);
    }

    private int getNegativeControlCountForPlateInternal(int plateIndex) {
        if (!uniquePlateSettings) {
            return Math.max(0, negativeControlCount);
        }

        ensureUniquePlateSettingsCapacity(Math.max(getConfiguredPlateCountForUniqueSettings(), plateIndex + 1));
        int safeIndex = Math.max(0, Math.min(plateIndex, negativeControlCountsByPlate.size() - 1));
        return Math.max(0, negativeControlCountsByPlate.get(safeIndex));
    }

    private int computeSharedSampleCapacityPerPlate() {
        int usable = Math.max(0, rows * cols - manualExcludedWells.size());
        int negativePerPlate = Math.min(Math.max(0, negativeControlCount), usable);
        return Math.max(0, usable - negativePerPlate);
    }

    private int computeSampleCapacityForPlate(int plateIndex) {
        int usable = Math.max(0, rows * cols - getExcludedWellsForPlateInternal(plateIndex).size());
        int negativePerPlate = Math.min(getNegativeControlCountForPlateInternal(plateIndex), usable);
        return Math.max(0, usable - negativePerPlate);
    }

    private record WellContent(String sampleName, boolean negativeControl) {
        private boolean isUnused() {
            return sampleName == null && !negativeControl;
        }
    }

    private record PlateRemovalPlan(
            int removedPlateIndex,
            Map<Integer, Map<String, String>> sampleMapsByPlate
    ) {}

    private static String generateRandomSeed(int digits) {
        int min = (int) Math.pow(10, digits - 1);
        int max = (int) Math.pow(10, digits) - 1;
        int value = new Random().nextInt(max - min + 1) + min;
        return Integer.toString(value);
    }

    private long parseSeedValue() {
        try {
            return Long.parseLong(seed);
        } catch (NumberFormatException e) {
            return seed != null ? seed.hashCode() : 0L;
        }
    }

    private boolean isWellInBounds(String well) {
        if (well == null || well.isBlank()) {
            return false;
        }

        PlateLayoutUtils.WellPosition position = PlateLayoutUtils.parseWellLabel(well);
        if (position == null) {
            return false;
        }

        return position.rowIndex() >= 0
                && position.rowIndex() < rows
                && position.columnIndex() >= 1
                && position.columnIndex() <= cols;
    }

    private List<String> allUsableWells(Set<String> excludedWells) {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String row = PlateLayoutUtils.rowLabel(r);
            for (int c = 1; c <= cols; c++) {
                String well = row + c;
                if (!excludedWells.contains(well)) {
                    out.add(well);
                }
            }
        }
        return out;
    }
}
