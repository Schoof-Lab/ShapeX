package qupath.ext.shapex.core;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.ext.shapex.ShapeXResources;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class IDotCsvWriter {

    private IDotCsvWriter() {}

    // ============================================================
    // Constants (easy to expose later via UI)
    // ============================================================

    private static final String PROTOCOL_NAME = ShapeXResources.get("idot.protocol.name");
    private static final String VERSION = "1.9.1.5";

    private static final String SOURCE_PLATE_MODEL = ShapeXResources.get("idot.source_plate.model");
    private static final String TARGET_PLATE_MODEL = ShapeXResources.get("idot.target_plate.model");

    private static final double SOURCE_WELL_CAPACITY_UL = 80.0;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH.mm");

    // ============================================================
    // Public entry point
    // ============================================================

    public static List<Path> generateCsvFiles(
            Path outputDirectory,
            MultiPlateAssignmentModel plateSet,
            List<IDotRow> idotRows
    ) throws IOException {

        Project<?> project = QuPathGUI.getInstance().getProject();
        if (project == null)
            throw new IOException(ShapeXResources.get("project.none.open"));

        String tmpProjectName;

        try {
            tmpProjectName = project.getPath()
                    .getParent()
                    .getFileName()
                    .toString();
        } catch (Exception e) {
            tmpProjectName = ShapeXResources.get("idot.project.default_name");
        }

        final String projectName = tmpProjectName;

        Files.createDirectories(outputDirectory);

        List<Path> generated = new ArrayList<>();

        int plateCount = plateSet.getPlateCount();
        Set<String> disabledSamples = plateSet.getDisabledSamples();

        for (int plateIndex = 0; plateIndex < plateCount; plateIndex++) {

            PlateAssignmentModel plate = plateSet.getPlate(plateIndex);
            if (plate == null)
                continue;

            Set<String> excluded = plateSet.getExcludedWells(plateIndex);

            // ----------------------------------------------------
            // Collect all target wells (samples + negatives)
            // ----------------------------------------------------

            Set<String> targetWells = new HashSet<>();
            for (var entry : plate.getSampleToWell().entrySet()) {
                if (!disabledSamples.contains(entry.getKey())) {
                    targetWells.add(entry.getValue());
                }
            }
            targetWells.addAll(plate.getNegativeControlWells());
            targetWells.removeAll(excluded);

            if (targetWells.isEmpty())
                continue;

            List<String> sortedTargets = PlateLayoutUtils.sortWells(targetWells);

            // ----------------------------------------------------
            // Create plate subfolder
            // ----------------------------------------------------

            Path plateFolder = outputDirectory.resolve(
                    ShapeXResources.format("plate.name.pretty", plateIndex + 1)
            );

            Files.createDirectories(plateFolder);

            // ----------------------------------------------------
            // One CSV per IDOT row
            // ----------------------------------------------------

            for (IDotRow row : idotRows) {

                if (row == null)
                    continue;

                String name = row.getName();
                double volume = row.getVolume();
                String liquid = row.getLiquidName();

                // Skip invalid rows
                if (name == null || name.isBlank())
                    continue;

                if (volume <= 0)
                    continue;

                if (liquid == null || liquid.isBlank())
                    liquid = ShapeXResources.get("idot.row.default_liquid");

                // Sanitize filename (remove unsafe characters)
                String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");

                Path csvPath = plateFolder.resolve(
                        projectName
                                + "_Plate"
                                + (plateIndex + 1)
                                + "_"
                                + safeName
                                + ShapeXResources.get("idot.file.suffix")
                );

                writeSingleCsv(
                        csvPath,
                        projectName,
                        sortedTargets,
                        volume,
                        liquid,
                        plateSet.getRows(),
                        plateSet.getColumns()
                );

                generated.add(csvPath);
            }
        }

        return generated;
    }

    // ============================================================
    // CSV Writer
    // ============================================================

    public static void writeSingleCsv(
            Path csvPath,
            String projectName,
            List<String> targetWells,
            double volume,
            String liquidName
    ) throws IOException {
        writeSingleCsv(
                csvPath,
                projectName,
                targetWells,
                volume,
                liquidName,
                0,
                0
        );
    }

    static void writeSingleCsv(
            Path csvPath,
            String projectName,
            List<String> targetWells,
            double volume,
            String liquidName,
            int plateRows,
            int plateColumns
    ) throws IOException {

        List<String> sourceWells =
                createSourceWells96();

        int capacityPerSource =
                (int)(SOURCE_WELL_CAPACITY_UL / volume);

        try (BufferedWriter w =
                     Files.newBufferedWriter(csvPath)) {

            // Header 1
            w.write(String.join(";",
                    PROTOCOL_NAME,
                    VERSION,
                    projectName,
                    LocalDate.now().format(DATE_FMT),
                    LocalTime.now().format(TIME_FMT),
                    "", "", ""
            ));
            w.newLine();

            // Header 2
            w.write(String.join(";",
                    SOURCE_PLATE_MODEL,
                    ShapeXResources.get("idot.header.source_plate_1"),
                    "",
                    ShapeXResources.get("idot.header.dispense_resolution"),
                    TARGET_PLATE_MODEL,
                    ShapeXResources.get("idot.header.target_plate_1"),
                    "",
                    ShapeXResources.get("idot.header.waste_tube")
            ));
            w.newLine();

            // Header 3
            w.write(String.join(";",
                    ShapeXResources.get("idot.option.dispense_to_waste"),
                    ShapeXResources.get("idot.option.dispense_to_waste_cycles"),
                    ShapeXResources.get("idot.option.dispense_to_waste_volume"),
                    ShapeXResources.get("idot.option.use_deionisation"),
                    ShapeXResources.get("idot.option.optimization_level"),
                    ShapeXResources.get("idot.option.waste_error_handling"),
                    ShapeXResources.get("idot.option.save_liquids"),
                    ""
            ));
            w.newLine();

            // Column header
            w.write(ShapeXResources.get("idot.column.header"));
            w.newLine();

            // Distribute target wells across source wells
            int targetIndex = 0;

            for (String src : sourceWells) {

                int remaining =
                        targetWells.size() - targetIndex;

                if (remaining <= 0)
                    break;

                int take =
                        Math.min(capacityPerSource, remaining);

                for (int i = 0; i < take; i++) {

                    String target =
                            PlateLayoutUtils.toIDotWellLabel(
                                    targetWells.get(targetIndex++),
                                    plateRows,
                                    plateColumns
                            );

                    w.write(src + ";" +
                            target + ";" +
                            volume + ";" +
                            liquidName +
                            ";;;;");
                    w.newLine();
                }
            }

            if (targetIndex < targetWells.size()) {
                throw new IOException(
                        ShapeXResources.get("error.idot.source_capacity_exceeded")
                );
            }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static List<String> createSourceWells96() {

        List<String> wells = new ArrayList<>();

        char[] rows = "ABCDEFGH".toCharArray();

        for (int col = 1; col <= 12; col++) {
            for (char row : rows) {
                wells.add(row + Integer.toString(col));
            }
        }

        return wells;
    }

    public static List<String> sortWells(Set<String> wells) {
        return PlateLayoutUtils.sortWells(wells);
    }
}
