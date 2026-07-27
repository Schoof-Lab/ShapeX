package qupath.ext.shapex.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class PlateAssignmentModel {

    private static final int DEFAULT_SEED_DIGITS = 6;

    private final int rows;
    private final int cols;

    /** Immutable set of sample identifiers (annotation class names) */
    private final Set<String> samples;

    /** Wells explicitly excluded by the user (manual only) */
    private final Set<String> manualExcludedWells = new HashSet<>();

    /** Seed as displayed to the user */
    private String seed;

    /** Deterministic mapping: sample → well */
    private final Map<String, String> sampleToWell = new HashMap<>();

    /** Negative control wells (deterministic) */
    private final Set<String> negativeControlWells = new HashSet<>();

    /** Number of negative controls */
    private int negativeControlCount = 0;

    /* ============================================================
     *  Constructors
     * ============================================================ */

    public PlateAssignmentModel(
            Set<String> samples,
            int rows,
            int cols,
            String seed
    ) {
        this(samples, rows, cols, seed, null);
    }

    public PlateAssignmentModel(
            Set<String> samples,
            int rows,
            int cols,
            String seed,
            Set<String> excludedWells
    ) {
        this.samples = Collections.unmodifiableSet(new HashSet<>(samples));
        this.rows = rows;
        this.cols = cols;

        this.seed = (seed == null || seed.isBlank())
                ? generateRandomSeed(DEFAULT_SEED_DIGITS)
                : seed;

        if (excludedWells != null) {
            this.manualExcludedWells.addAll(excludedWells);
        }

        recompute();
    }

    public PlateAssignmentModel(
            int rows,
            int cols,
            String seed,
            Set<String> excludedWells,
            Map<String, String> sampleToWell,
            Set<String> negativeControlWells
    ) {
        Set<String> sampleSet = sampleToWell != null
                ? sampleToWell.keySet()
                : Set.of();
        this.samples = Collections.unmodifiableSet(new HashSet<>(sampleSet));
        this.rows = rows;
        this.cols = cols;
        this.seed = (seed == null || seed.isBlank())
                ? generateRandomSeed(DEFAULT_SEED_DIGITS)
                : seed;

        if (excludedWells != null) {
            this.manualExcludedWells.addAll(excludedWells);
        }
        if (sampleToWell != null) {
            this.sampleToWell.putAll(sampleToWell);
        }
        if (negativeControlWells != null) {
            this.negativeControlWells.addAll(negativeControlWells);
        }
        this.negativeControlCount = this.negativeControlWells.size();
    }

    /* ============================================================
     *  Core API
     * ============================================================ */

    public void setSeed(String seed) {
        if (seed == null || seed.isBlank())
            return;

        this.seed = seed;
        recompute();
    }

    public void shuffle() {
        this.seed = generateRandomSeed(DEFAULT_SEED_DIGITS);
        recompute();
    }

    public String getSeed() {
        return seed;
    }

    /** Toggle a single well exclusion (manual override) */
    public void toggleExclude(String well) {
        if (well == null)
            return;

        if (manualExcludedWells.contains(well)) {
            manualExcludedWells.remove(well);
        } else {
            manualExcludedWells.add(well);
        }
        recompute();
    }

    /** Deterministic mapping: sample → well */
    public Map<String, String> getSampleToWell() {
        return Collections.unmodifiableMap(sampleToWell);
    }

    /** Manual exclusions only — controller may layer logic on top */
    public Set<String> getExcludedWells() {
        return Collections.unmodifiableSet(manualExcludedWells);
    }

    public Set<String> getSamples() {
        return samples;
    }

    public void setNegativeControlCount(int count) {
        this.negativeControlCount = Math.max(0, count);
        recompute();
    }

    public Set<String> getNegativeControlWells() {
        return Collections.unmodifiableSet(negativeControlWells);
    }

    /* ============================================================
     *  Internal logic
     * ============================================================ */

    private void recompute() {

        List<String> wells = allWells();
        wells.removeAll(manualExcludedWells);

        List<String> sampleList = new ArrayList<>(samples);
        Collections.sort(sampleList);

        long seedValue;
        try {
            seedValue = Long.parseLong(seed);
        } catch (NumberFormatException e) {
            seedValue = seed.hashCode();
        }

        Random random = new Random(seedValue);

        Collections.shuffle(wells, random);

        sampleToWell.clear();
        negativeControlWells.clear();

        // Assign samples first
        int index = 0;
        for (; index < sampleList.size() && index < wells.size(); index++) {
            sampleToWell.put(sampleList.get(index), wells.get(index));
        }

        // Remaining wells available for negative controls
        List<String> remaining = wells.subList(index, wells.size());

        Collections.shuffle(remaining, random);

        for (int i = 0; i < negativeControlCount && i < remaining.size(); i++) {
            negativeControlWells.add(remaining.get(i));
        }
    }

    /* ============================================================
     *  Helpers
     * ============================================================ */

    private static String generateRandomSeed(int digits) {
        int min = (int) Math.pow(10, digits - 1);
        int max = (int) Math.pow(10, digits) - 1;
        int value = new Random().nextInt(max - min + 1) + min;
        return Integer.toString(value);
    }

    private List<String> allWells() {
        return PlateLayoutUtils.allWells(rows, cols);
    }
}
