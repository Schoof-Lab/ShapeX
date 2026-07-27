package qupath.ext.shapex.core;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import qupath.ext.shapex.ShapeXResources;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PlateOverviewWriter {

    public enum OverviewExportFormat {
        EXCEL("Excel", "xlsx"),
        NUMBERS("Numbers", "numbers"),
        OPEN_DOCUMENT("OpenDocument", "ods"),
        CSV("CSV", "csv"),
        TSV("TSV", "tsv"),
        TXT("Text", "txt");

        private final String displayName;
        private final String extension;

        OverviewExportFormat(String displayName, String extension) {
            this.displayName = displayName;
            this.extension = extension;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getExtension() {
            return extension;
        }
    }

    private enum SheetKind {
        OVERVIEW,
        SETTINGS,
        PLATE
    }

    private record ExportCell(Object value, Integer fillArgb) {}

    private record ExportSheet(String name, SheetKind kind, List<List<ExportCell>> rows) {}

    private record ExportBundle(List<ExportSheet> sheets) {}

    /**
     * Reproducibility information captured by the run workflow before overview generation
     * moves to its background task.
     */
    public record RunMetadata(
            String generatedAt,
            String runDirectory,
            String projectName,
            String projectUri,
            String projectVersion,
            String quPathVersion,
            String quPathBuild,
            List<String> requestedOutputs,
            Boolean includeAllAnnotations,
            int automaticGeometryAdjustments,
            int explicitGeometryResolutions,
            int skippedGeometryAnnotations
    ) {
        public RunMetadata {
            generatedAt = normalizeMetadataValue(generatedAt);
            runDirectory = normalizeMetadataValue(runDirectory);
            projectName = normalizeMetadataValue(projectName);
            projectUri = normalizeMetadataValue(projectUri);
            projectVersion = normalizeMetadataValue(projectVersion);
            quPathVersion = normalizeMetadataValue(quPathVersion);
            quPathBuild = normalizeMetadataValue(quPathBuild);
            requestedOutputs = requestedOutputs == null ? List.of() : List.copyOf(requestedOutputs);
            automaticGeometryAdjustments = Math.max(0, automaticGeometryAdjustments);
            explicitGeometryResolutions = Math.max(0, explicitGeometryResolutions);
            skippedGeometryAnnotations = Math.max(0, skippedGeometryAnnotations);
        }

        private static RunMetadata unspecified(Path runDirectory) {
            return new RunMetadata(
                    null,
                    runDirectory == null ? null : runDirectory.toAbsolutePath().normalize().toString(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    0,
                    0,
                    0
            );
        }
    }

    private PlateOverviewWriter() {}

    private static final int PLATE_ROW_HEADER_WIDTH = 900;
    private static final int PLATE_WELL_COLUMN_WIDTH = 2400;
    private static final float PLATE_ROW_HEIGHT = 16f;
    private static final int NEGATIVE_CONTROL_ARGB = 0xFFFF0000;

    public static Path generateOverview(
            Path outputDir,
            MultiPlateAssignmentModel plateSet,
            Map<String, ? extends ImageData<?>> imageDataBySlide,
            int rows,
            int cols,
            OverviewExportFormat format,
            boolean mapImageToPlate,
            boolean poolClasses
    ) throws IOException {
        return generateOverview(
                outputDir,
                plateSet,
                imageDataBySlide,
                rows,
                cols,
                format,
                mapImageToPlate,
                poolClasses,
                Map.of(),
                RunMetadata.unspecified(outputDir)
        );
    }

    public static Path generateOverview(
            Path outputDir,
            MultiPlateAssignmentModel plateSet,
            Map<String, ? extends ImageData<?>> imageDataBySlide,
            int rows,
            int cols,
            OverviewExportFormat format,
            boolean mapImageToPlate,
            boolean poolClasses,
            Map<String, ? extends Set<String>> skippedAnnotationIdsBySlide
    ) throws IOException {
        return generateOverview(
                outputDir,
                plateSet,
                imageDataBySlide,
                rows,
                cols,
                format,
                mapImageToPlate,
                poolClasses,
                skippedAnnotationIdsBySlide,
                RunMetadata.unspecified(outputDir)
        );
    }

    public static Path generateOverview(
            Path outputDir,
            MultiPlateAssignmentModel plateSet,
            Map<String, ? extends ImageData<?>> imageDataBySlide,
            int rows,
            int cols,
            OverviewExportFormat format,
            boolean mapImageToPlate,
            boolean poolClasses,
            Map<String, ? extends Set<String>> skippedAnnotationIdsBySlide,
            RunMetadata runMetadata
    ) throws IOException {
        Path overviewDir = outputDir.resolve(ShapeXResources.get("overview.dir.name"));
        Files.createDirectories(overviewDir);

        ExportBundle bundle = buildExportBundle(
                plateSet,
                imageDataBySlide,
                rows,
                cols,
                format,
                mapImageToPlate,
                poolClasses,
                skippedAnnotationIdsBySlide,
                runMetadata == null ? RunMetadata.unspecified(outputDir) : runMetadata
        );
        String fileBase = ShapeXResources.get("overview.file.base");

        return switch (format) {
            case EXCEL -> writeExcelExport(overviewDir.resolve(fileBase + ".xlsx"), bundle);
            case NUMBERS -> writeNumbersExport(overviewDir.resolve(fileBase + ".numbers"), bundle);
            case OPEN_DOCUMENT -> writeOpenDocumentExport(overviewDir.resolve(fileBase + ".ods"), bundle);
            case CSV -> writeDelimitedExports(overviewDir, fileBase, bundle, ",", "csv");
            case TSV -> writeDelimitedExports(overviewDir, fileBase, bundle, "\t", "tsv");
            case TXT -> writeDelimitedExports(overviewDir, fileBase, bundle, "\t", "txt");
        };
    }

    private static ExportBundle buildExportBundle(
            MultiPlateAssignmentModel plateSet,
            Map<String, ? extends ImageData<?>> imageDataBySlide,
            int rows,
            int cols,
            OverviewExportFormat format,
            boolean mapImageToPlate,
            boolean poolClasses,
            Map<String, ? extends Set<String>> skippedAnnotationIdsBySlide,
            RunMetadata runMetadata
    ) {
        Map<String, Integer> shapeCounts = new HashMap<>();
        Map<String, Double> areaPerSample = new HashMap<>();
        Map<String, Integer> sampleColors = new HashMap<>();
        Map<String, List<OverviewWellLabelResolver.AnnotationLabelSource>> wellLabelSourcesBySample =
                new HashMap<>();
        Map<String, Integer> annotationCountsBySlide = new HashMap<>();
        Map<String, Map<String, Integer>> shapeCountsBySampleBySlide = new HashMap<>();
        Map<String, Integer> skippedAnnotationCountsBySlide = new HashMap<>();
        Set<String> exportableSampleKeys = new java.util.HashSet<>();

        for (var imageEntry : imageDataBySlide.entrySet()) {
            String slideName = imageEntry.getKey();
            ImageData<?> imageData = imageEntry.getValue();
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            Set<String> skippedAnnotationIds = Set.of();
            if (skippedAnnotationIdsBySlide != null && skippedAnnotationIdsBySlide.containsKey(slideName)) {
                skippedAnnotationIds = skippedAnnotationIdsBySlide.get(slideName);
            }

            annotationCountsBySlide.put(slideName, hierarchy.getAnnotationObjects().size());
            var calibration = imageData.getServer().getPixelCalibration();
            double px = calibration.getPixelWidthMicrons();
            double py = calibration.getPixelHeightMicrons();
            boolean hasMicronArea = calibration.hasPixelSizeMicrons()
                    && Double.isFinite(px)
                    && Double.isFinite(py);

            for (PathObject obj : hierarchy.getAnnotationObjects()) {
                if (skippedAnnotationIds.contains(ProjectAnnotationService.getPathObjectId(obj))) {
                    skippedAnnotationCountsBySlide.merge(slideName, 1, Integer::sum);
                    continue;
                }

                String sampleKey = AnnotationUtils.getSampleKey(
                        obj,
                        slideName,
                        true,
                        mapImageToPlate,
                        poolClasses
                );
                if (sampleKey == null)
                    continue;

                exportableSampleKeys.add(sampleKey);
                shapeCountsBySampleBySlide
                        .computeIfAbsent(slideName, ignored -> new HashMap<>())
                        .merge(sampleKey, 1, Integer::sum);
                wellLabelSourcesBySample
                        .computeIfAbsent(sampleKey, ignored -> new ArrayList<>())
                        .add(toWellLabelSource(obj));
                shapeCounts.merge(sampleKey, 1, Integer::sum);
                double area = hasMicronArea
                        ? obj.getROI().getArea() * px * py
                        : Double.NaN;
                areaPerSample.merge(
                        sampleKey,
                        area,
                        (existing, addition) -> Double.isFinite(existing)
                                && Double.isFinite(addition)
                                ? existing + addition
                                : Double.NaN
                );

                Integer color = AnnotationUtils.getAnnotationColor(obj);
                if (color != null) {
                    sampleColors.putIfAbsent(sampleKey, color);
                }
            }
        }

        Map<String, PlateLocation> locations = plateSet.getEnabledSampleLocations().entrySet().stream()
                .filter(entry -> exportableSampleKeys.contains(entry.getKey()))
                .filter(entry -> !plateSet.getExcludedWells(entry.getValue().plateIndex()).contains(entry.getValue().well()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));

        Map<String, Integer> exportedAnnotationCountsBySlide = new HashMap<>();
        for (var slideEntry : shapeCountsBySampleBySlide.entrySet()) {
            int exportedCount = slideEntry.getValue().entrySet().stream()
                    .filter(entry -> locations.containsKey(entry.getKey()))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
            exportedAnnotationCountsBySlide.put(slideEntry.getKey(), exportedCount);
        }

        Map<Integer, Map<String, String>> negativeLabelsByWellByPlate =
                buildNegativeControlLabelsByPlate(plateSet);
        Map<String, String> wellLabelsBySample = resolveWellLabelsByPlate(
                locations,
                wellLabelSourcesBySample
        );

        List<ExportSheet> sheets = new ArrayList<>();
        sheets.add(buildOverviewSheet(plateSet, locations, shapeCounts, areaPerSample, sampleColors, negativeLabelsByWellByPlate));
        sheets.add(buildSettingsSheet(
                plateSet,
                locations,
                imageDataBySlide,
                annotationCountsBySlide,
                exportedAnnotationCountsBySlide,
                skippedAnnotationCountsBySlide,
                areaPerSample,
                negativeLabelsByWellByPlate,
                rows,
                cols,
                format,
                mapImageToPlate,
                poolClasses,
                runMetadata
        ));
        sheets.addAll(buildPlateSheets(
                plateSet,
                locations,
                sampleColors,
                wellLabelsBySample,
                negativeLabelsByWellByPlate,
                rows,
                cols
        ));
        return new ExportBundle(sheets);
    }

    private static ExportSheet buildOverviewSheet(
            MultiPlateAssignmentModel plateSet,
            Map<String, PlateLocation> locations,
            Map<String, Integer> shapeCounts,
            Map<String, Double> areaPerSample,
            Map<String, Integer> sampleColors,
            Map<Integer, Map<String, String>> negativeLabelsByWellByPlate
    ) {
        List<List<ExportCell>> rows = new ArrayList<>();
        rows.add(cells(
                ShapeXResources.get("overview.column.sample_name.pretty"),
                ShapeXResources.get("overview.column.well.pretty"),
                ShapeXResources.get("overview.column.plate.pretty"),
                ShapeXResources.get("overview.column.shape_count.pretty"),
                ShapeXResources.get("overview.column.area.pretty"),
                ShapeXResources.get("overview.column.color.pretty")
        ));

        List<String> sortedSamples = new ArrayList<>(locations.keySet());
        Collections.sort(sortedSamples);

        for (String sample : sortedSamples) {
            PlateLocation loc = locations.get(sample);
            int argb = sampleColors.getOrDefault(sample, NEGATIVE_CONTROL_ARGB);

            rows.add(cells(
                    AnnotationUtils.toDisplaySampleLabel(sample),
                    loc.well(),
                    ShapeXResources.format("plate.name.pretty", loc.plateIndex() + 1),
                    shapeCounts.getOrDefault(sample, 0),
                    formatArea(areaPerSample.get(sample)),
                    formatColorHex(argb)
            ));
        }

        for (int plateIndex = 0; plateIndex < plateSet.getPlateCount(); plateIndex++) {
            Map<String, String> labelsByWell =
                    negativeLabelsByWellByPlate.getOrDefault(plateIndex, Collections.emptyMap());
            List<String> sortedNegWells = PlateLayoutUtils.sortWells(labelsByWell.keySet());

            for (String well : sortedNegWells) {
                rows.add(cells(
                        labelsByWell.get(well),
                        well,
                        ShapeXResources.format("plate.name.pretty", plateIndex + 1),
                        0,
                        0.0,
                        formatColorHex(NEGATIVE_CONTROL_ARGB)
                ));
            }
        }

        return new ExportSheet(ShapeXResources.get("overview.sheet.overview"), SheetKind.OVERVIEW, rows);
    }

    private static ExportSheet buildSettingsSheet(
            MultiPlateAssignmentModel plateSet,
            Map<String, PlateLocation> locations,
            Map<String, ? extends ImageData<?>> imageDataBySlide,
            Map<String, Integer> annotationCountsBySlide,
            Map<String, Integer> exportedAnnotationCountsBySlide,
            Map<String, Integer> skippedAnnotationCountsBySlide,
            Map<String, Double> areaPerSample,
            Map<Integer, Map<String, String>> negativeLabelsByWellByPlate,
            int rows,
            int cols,
            OverviewExportFormat format,
            boolean mapImageToPlate,
            boolean poolClasses,
            RunMetadata runMetadata
    ) {
        List<List<ExportCell>> settingsRows = new ArrayList<>();
        settingsRows.add(cells(ShapeXResources.get("overview.section.run_information")));
        settingsRows.add(List.of());
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.property"),
                ShapeXResources.get("overview.settings.value")
        ));
        settingsRows.add(cells(ShapeXResources.get("overview.settings.generated_at"), runMetadata.generatedAt()));
        settingsRows.add(cells(ShapeXResources.get("overview.settings.run_directory"), runMetadata.runDirectory()));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.requested_outputs"),
                runMetadata.requestedOutputs().isEmpty()
                        ? ShapeXResources.get("common.unknown")
                        : String.join(", ", runMetadata.requestedOutputs())
        ));
        settingsRows.add(cells(ShapeXResources.get("overview.settings.overview_format"), format.getDisplayName()));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.shapex_version"),
                ShapeXResources.get("extension.shapex.version")
        ));
        settingsRows.add(cells(ShapeXResources.get("overview.settings.qupath_version"), runMetadata.quPathVersion()));
        settingsRows.add(cells(ShapeXResources.get("overview.settings.qupath_build"), runMetadata.quPathBuild()));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.java_runtime"),
                System.getProperty("java.runtime.name", ShapeXResources.get("common.unknown"))
                        + " "
                        + System.getProperty("java.runtime.version", ShapeXResources.get("common.unknown"))
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.operating_system"),
                System.getProperty("os.name", ShapeXResources.get("common.unknown"))
                        + " "
                        + System.getProperty("os.version", "")
                        + " ("
                        + System.getProperty("os.arch", ShapeXResources.get("common.unknown"))
                        + ")"
        ));

        settingsRows.add(List.of());
        settingsRows.add(cells(ShapeXResources.get("overview.section.project_input")));
        settingsRows.add(List.of());
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.property"),
                ShapeXResources.get("overview.settings.value")
        ));
        settingsRows.add(cells(ShapeXResources.get("overview.settings.project_name"), runMetadata.projectName()));
        settingsRows.add(cells(ShapeXResources.get("overview.settings.project_uri"), runMetadata.projectUri()));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.project_version"),
                runMetadata.projectVersion()
        ));

        List<String> sortedSlideNames = new ArrayList<>(imageDataBySlide.keySet());
        Collections.sort(sortedSlideNames);
        int annotationCount = annotationCountsBySlide.values().stream().mapToInt(Integer::intValue).sum();
        int exportedAnnotationCount =
                exportedAnnotationCountsBySlide.values().stream().mapToInt(Integer::intValue).sum();
        int skippedAnnotationCount =
                skippedAnnotationCountsBySlide.values().stream().mapToInt(Integer::intValue).sum();
        boolean exportedAreaAvailable = locations.keySet().stream()
                .map(areaPerSample::get)
                .allMatch(area -> area != null && Double.isFinite(area));
        Object exportedArea = exportedAreaAvailable
                ? locations.keySet().stream()
                        .mapToDouble(areaPerSample::get)
                        .sum()
                : ShapeXResources.get("overview.area.unavailable");

        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.selected_images"),
                sortedSlideNames.size()
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.image_names"),
                String.join(", ", sortedSlideNames)
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.annotations_in_selected_images"),
                annotationCount
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.exported_annotations"),
                exportedAnnotationCount
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.non_exported_annotations"),
                Math.max(0, annotationCount - exportedAnnotationCount - skippedAnnotationCount)
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.skipped_geometry_annotations"),
                Math.max(skippedAnnotationCount, runMetadata.skippedGeometryAnnotations())
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.automatic_geometry_adjustments"),
                runMetadata.automaticGeometryAdjustments()
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.explicit_geometry_resolutions"),
                runMetadata.explicitGeometryResolutions()
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.include_all_annotations"),
                formatBoolean(runMetadata.includeAllAnnotations())
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.map_image_to_plate"),
                formatBoolean(mapImageToPlate)
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.pool_classes"),
                formatBoolean(poolClasses)
        ));

        settingsRows.add(cells(ShapeXResources.get("overview.section.plate_distribution")));
        settingsRows.add(List.of());
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.plate"),
                ShapeXResources.get("overview.settings.sample_count"),
                ShapeXResources.get("overview.settings.negative_controls"),
                ShapeXResources.get("overview.settings.unavailable_wells"),
                ShapeXResources.get("overview.settings.empty_wells"),
                ShapeXResources.get("overview.settings.available_well_utilization")
        ));

        Map<Integer, Long> plateCounts =
                locations.values().stream()
                        .collect(Collectors.groupingBy(
                                PlateLocation::plateIndex,
                                Collectors.counting()
                        ));

        for (int i = 0; i < plateSet.getPlateCount(); i++) {
            int sampleCount = Math.toIntExact(plateCounts.getOrDefault(i, 0L));
            int negativeControlCount = negativeLabelsByWellByPlate
                    .getOrDefault(i, Collections.emptyMap())
                    .size();
            int unavailableWellCount = plateSet.getExcludedWells(i).size();
            int availableSampleWells = Math.max(0, rows * cols - unavailableWellCount - negativeControlCount);
            int emptyWellCount = Math.max(0, availableSampleWells - sampleCount);
            settingsRows.add(cells(
                    ShapeXResources.format("plate.name.pretty", i + 1),
                    sampleCount,
                    negativeControlCount,
                    unavailableWellCount,
                    emptyWellCount,
                    formatUtilization(sampleCount, availableSampleWells)
            ));
        }

        settingsRows.add(List.of());
        settingsRows.add(cells(ShapeXResources.get("overview.section.processing_statistics")));
        settingsRows.add(List.of());
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.property"),
                ShapeXResources.get("overview.settings.value")
        ));

        int totalSamples = locations.size();
        int totalPlates = plateSet.getPlateCount();
        int totalWells = rows * cols * totalPlates;
        int totalNegativeControls = negativeLabelsByWellByPlate.values().stream()
                .mapToInt(Map::size)
                .sum();
        int totalUnavailableWells = 0;
        for (int i = 0; i < totalPlates; i++) {
            totalUnavailableWells += plateSet.getExcludedWells(i).size();
        }
        int totalAvailableSampleWells =
                Math.max(0, totalWells - totalNegativeControls - totalUnavailableWells);
        int totalEmptyWells = Math.max(0, totalAvailableSampleWells - totalSamples);
        double util = totalWells > 0 ? (double) totalSamples / totalWells * 100.0 : 0;

        settingsRows.add(cells(ShapeXResources.get("overview.settings.total_samples"), totalSamples));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.configured_samples"),
                plateSet.getSampleCount()
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.disabled_samples"),
                plateSet.getDisabledSamples().size()
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.total_exported_shapes"),
                exportedAnnotationCount
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.total_exported_area"),
                exportedArea
        ));
        settingsRows.add(cells(ShapeXResources.get("overview.settings.total_plates_used"), totalPlates));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.total_negative_controls"),
                totalNegativeControls
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.total_unavailable_wells"),
                totalUnavailableWells
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.total_empty_wells"),
                totalEmptyWells
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.plate_utilization"),
                String.format(Locale.ROOT, "%.1f%% (%d/%d)", util, totalSamples, totalWells)
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.available_well_utilization"),
                formatUtilization(totalSamples, totalAvailableSampleWells)
        ));

        settingsRows.add(List.of());
        settingsRows.add(cells(ShapeXResources.get("overview.section.settings")));
        settingsRows.add(List.of());
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.property"),
                ShapeXResources.get("overview.settings.value")
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.plate_dimensions"),
                ShapeXResources.format("overview.settings.plate_dimensions.value", rows, cols, rows * cols)
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.sample_assignment"),
                plateSet.isRandomizeSamples()
                        ? ShapeXResources.get("overview.settings.randomized")
                        : ShapeXResources.get("overview.settings.sequential")
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.plate_settings_mode"),
                plateSet.isUniquePlateSettings()
                        ? ShapeXResources.get("overview.settings.per_plate")
                        : ShapeXResources.get("overview.settings.shared")
        ));
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.random_seed_used"),
                plateSet.isRandomizeSamples()
                        ? plateSet.getSeed()
                        : ShapeXResources.get("common.none")
        ));

        settingsRows.add(List.of());
        settingsRows.add(cells(ShapeXResources.get("overview.section.qupath_image_summary")));
        settingsRows.add(List.of());
        settingsRows.add(cells(
                ShapeXResources.get("overview.settings.image"),
                ShapeXResources.get("overview.settings.image_type"),
                ShapeXResources.get("overview.settings.pixel_type"),
                ShapeXResources.get("overview.settings.dimensions_czt"),
                ShapeXResources.get("overview.settings.width"),
                ShapeXResources.get("overview.settings.height"),
                ShapeXResources.get("overview.settings.pixel_width"),
                ShapeXResources.get("overview.settings.pixel_height"),
                ShapeXResources.get("overview.settings.magnification"),
                ShapeXResources.get("overview.settings.annotation_count"),
                ShapeXResources.get("overview.settings.exported_shape_count"),
                ShapeXResources.get("overview.settings.skipped_count"),
                ShapeXResources.get("overview.settings.server_type"),
                ShapeXResources.get("overview.settings.source_uri")
        ));
        for (String slideName : sortedSlideNames) {
            ImageData<?> imageData = imageDataBySlide.get(slideName);
            Map<QuPathImageDetailsExtractor.Slot, String> details =
                    QuPathImageDetailsExtractor.extract(imageData, slideName).stream()
                            .collect(Collectors.toMap(
                                    QuPathImageDetailsExtractor.Entry::slot,
                                    QuPathImageDetailsExtractor.Entry::value,
                                    (first, ignored) -> first
                            ));
            settingsRows.add(cells(
                    slideName,
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.IMAGE_TYPE),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.PIXEL_TYPE),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.DIMENSIONS),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.WIDTH),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.HEIGHT),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.PIXEL_WIDTH),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.PIXEL_HEIGHT),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.MAGNIFICATION),
                    annotationCountsBySlide.getOrDefault(slideName, 0),
                    exportedAnnotationCountsBySlide.getOrDefault(slideName, 0),
                    skippedAnnotationCountsBySlide.getOrDefault(slideName, 0),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.SERVER_TYPE),
                    imageDetail(details, QuPathImageDetailsExtractor.Slot.URI)
            ));
        }

        return new ExportSheet(ShapeXResources.get("overview.sheet.settings"), SheetKind.SETTINGS, settingsRows);
    }

    private static OverviewWellLabelResolver.AnnotationLabelSource toWellLabelSource(
            PathObject annotation
    ) {
        String className = annotation.getPathClass() == null
                ? null
                : annotation.getPathClass().getName();
        return new OverviewWellLabelResolver.AnnotationLabelSource(
                className,
                annotation.getName(),
                ProjectAnnotationService.getPathObjectId(annotation)
        );
    }

    private static Map<String, String> resolveWellLabelsByPlate(
            Map<String, PlateLocation> locations,
            Map<String, ? extends List<OverviewWellLabelResolver.AnnotationLabelSource>>
                    sourcesBySample
    ) {
        Map<Integer, List<String>> sampleKeysByPlate = new HashMap<>();
        for (var entry : locations.entrySet()) {
            sampleKeysByPlate
                    .computeIfAbsent(entry.getValue().plateIndex(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }

        Map<String, String> labelsBySample = new HashMap<>();
        for (List<String> sampleKeys : sampleKeysByPlate.values()) {
            labelsBySample.putAll(OverviewWellLabelResolver.resolve(sampleKeys, sourcesBySample));
        }
        return labelsBySample;
    }

    private static List<ExportSheet> buildPlateSheets(
            MultiPlateAssignmentModel plateSet,
            Map<String, PlateLocation> locations,
            Map<String, Integer> sampleColors,
            Map<String, String> wellLabelsBySample,
            Map<Integer, Map<String, String>> negativeLabelsByWellByPlate,
            int rows,
            int cols
    ) {
        Map<String, Integer> displayColors = new HashMap<>(sampleColors);
        Map<Integer, Map<String, String>> sampleByWellByPlate = new HashMap<>();

        for (var entry : locations.entrySet()) {
            String sample = entry.getKey();
            PlateLocation location = entry.getValue();

            sampleByWellByPlate
                    .computeIfAbsent(location.plateIndex(), k -> new HashMap<>())
                    .put(location.well(), sample);
        }

        for (var plateEntry : negativeLabelsByWellByPlate.entrySet()) {
            int plateIndex = plateEntry.getKey();
            Map<String, String> negativeByWell = plateEntry.getValue();

            Map<String, String> byWell =
                    sampleByWellByPlate.computeIfAbsent(plateIndex, k -> new HashMap<>());

            for (var negEntry : negativeByWell.entrySet()) {
                byWell.putIfAbsent(negEntry.getKey(), negEntry.getValue());
                displayColors.putIfAbsent(negEntry.getValue(), NEGATIVE_CONTROL_ARGB);
            }
        }

        List<ExportSheet> sheets = new ArrayList<>();
        for (int plateIndex = 0; plateIndex < plateSet.getPlateCount(); plateIndex++) {
            List<List<ExportCell>> plateRows = new ArrayList<>();
            List<ExportCell> headerRow = new ArrayList<>();
            headerRow.add(new ExportCell("", null));
            for (int c = 1; c <= cols; c++) {
                headerRow.add(new ExportCell(c, null));
            }
            plateRows.add(headerRow);

            Map<String, String> samplesByWell =
                    sampleByWellByPlate.getOrDefault(plateIndex, Collections.emptyMap());

            for (int r = 1; r <= rows; r++) {
                List<ExportCell> row = new ArrayList<>();
                String rowLabel = PlateLayoutUtils.rowLabel(r - 1);
                row.add(new ExportCell(rowLabel, null));

                for (int c = 1; c <= cols; c++) {
                    String well = rowLabel + c;
                    String sample = samplesByWell.get(well);
                    Integer fillArgb = sample == null || sample.isBlank()
                            ? null
                            : displayColors.getOrDefault(sample, NEGATIVE_CONTROL_ARGB);
                    row.add(new ExportCell(
                            sample == null
                                    ? ""
                                    : wellLabelsBySample.getOrDefault(
                                            sample,
                                            AnnotationUtils.toDisplaySampleName(sample)
                                    ),
                            fillArgb
                    ));
                }
                plateRows.add(row);
            }

            sheets.add(new ExportSheet(
                    ShapeXResources.format("overview.sheet.plate", plateIndex + 1),
                    SheetKind.PLATE,
                    plateRows
            ));
        }

        return sheets;
    }

    private static Path writeExcelExport(Path file, ExportBundle bundle) throws IOException {
        ensureParentDirectory(file);

        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle sectionStyle = createSectionStyle(workbook);
            CellStyle emptyWellStyle = createEmptyWellStyle(workbook);
            CellStyle sampleBaseStyle = createSampleBaseStyle(workbook);
            Map<Integer, CellStyle> sampleStyleByArgb = new HashMap<>();

            for (ExportSheet exportSheet : bundle.sheets()) {
                Sheet sheet = workbook.createSheet(exportSheet.name());
                List<List<ExportCell>> rows = exportSheet.rows();

                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    List<ExportCell> exportRow = rows.get(rowIndex);
                    Row row = sheet.createRow(rowIndex);

                    if (exportSheet.kind() == SheetKind.PLATE && rowIndex > 0) {
                        row.setHeightInPoints(PLATE_ROW_HEIGHT);
                    }

                    for (int colIndex = 0; colIndex < exportRow.size(); colIndex++) {
                        ExportCell exportCell = exportRow.get(colIndex);
                        Cell cell = row.createCell(colIndex);
                        writePoiCellValue(cell, exportCell.value());

                        if (exportSheet.kind() == SheetKind.OVERVIEW && rowIndex == 0) {
                            cell.setCellStyle(headerStyle);
                            continue;
                        }

                        if (exportSheet.kind() == SheetKind.SETTINGS) {
                            if (!exportRow.isEmpty() && exportRow.size() == 1) {
                                cell.setCellStyle(sectionStyle);
                            } else if (isSettingsHeaderRow(rows, rowIndex)) {
                                cell.setCellStyle(headerStyle);
                            }
                            continue;
                        }

                        if (exportSheet.kind() == SheetKind.PLATE) {
                            if (rowIndex == 0 || colIndex == 0) {
                                cell.setCellStyle(headerStyle);
                                continue;
                            }

                            Integer argb = exportCell.fillArgb();
                            if (argb == null || String.valueOf(exportCell.value()).isBlank()) {
                                cell.setCellStyle(emptyWellStyle);
                            } else {
                                CellStyle style = sampleStyleByArgb.computeIfAbsent(
                                        argb,
                                        color -> createSampleColorStyle(workbook, sampleBaseStyle, color)
                                );
                                cell.setCellStyle(style);
                            }
                        }
                    }
                }

                if (exportSheet.kind() == SheetKind.PLATE) {
                    sheet.setColumnWidth(0, PLATE_ROW_HEADER_WIDTH);
                    int maxColumns = exportSheet.rows().stream().mapToInt(List::size).max().orElse(1);
                    for (int c = 1; c < maxColumns; c++) {
                        sheet.setColumnWidth(c, PLATE_WELL_COLUMN_WIDTH);
                    }
                    sheet.createFreezePane(1, 1);
                } else {
                    int maxColumns = exportSheet.rows().stream().mapToInt(List::size).max().orElse(0);
                    for (int i = 0; i < maxColumns; i++) {
                        sheet.autoSizeColumn(i);
                    }
                }
            }

            try (OutputStream os = Files.newOutputStream(file)) {
                workbook.write(os);
            }
        }

        return file;
    }

    private static Path writeNumbersExport(Path file, ExportBundle bundle) throws IOException {
        if (!isMac()) {
            throw new IOException("Numbers export requires macOS.");
        }

        Path tempXlsx = Files.createTempFile("plate-overview-", ".xlsx");
        try {
            writeExcelExport(tempXlsx, bundle);
            replaceExistingPath(file);

            String script = """
                    set wasRunning to application "Numbers" is running
                    tell application "Numbers"
                        launch
                        set sourceFile to POSIX file "%s"
                        set targetFile to POSIX file "%s"
                        set docRef to open sourceFile
                        delay 1
                        save docRef in targetFile
                        close docRef saving no
                        if not wasRunning then
                            quit
                        end if
                    end tell
                    """.formatted(
                    escapeAppleScriptPath(tempXlsx.toAbsolutePath().toString()),
                    escapeAppleScriptPath(file.toAbsolutePath().toString())
            );

            Path scriptFile = Files.createTempFile("plate-overview-", ".applescript");
            Files.writeString(scriptFile, script, StandardCharsets.UTF_8);
            try {
                Process process = new ProcessBuilder("osascript", scriptFile.toString())
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("Timed out while converting workbook to Numbers format.");
                }
                if (process.exitValue() != 0) {
                    throw new IOException(output.isBlank()
                            ? "Failed to convert workbook to Numbers format."
                            : output);
                }
                if (!Files.exists(file)) {
                    throw new IOException("Numbers export completed without creating an output file.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Numbers export was interrupted.", e);
            } finally {
                Files.deleteIfExists(scriptFile);
            }

            return file;
        } finally {
            Files.deleteIfExists(tempXlsx);
        }
    }

    private static Path writeOpenDocumentExport(Path file, ExportBundle bundle) throws IOException {
        ensureParentDirectory(file);
        replaceExistingPath(file);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);

            writeStoredZipEntry(zip, "mimetype", "application/vnd.oasis.opendocument.spreadsheet");
            writeZipEntry(zip, "content.xml", buildOdsContentXml(bundle));
            writeZipEntry(zip, "styles.xml", buildOdsStylesXml());
            writeZipEntry(zip, "meta.xml", buildOdsMetaXml());
            writeZipEntry(zip, "settings.xml", buildOdsSettingsXml());
            writeZipEntry(zip, "META-INF/manifest.xml", buildOdsManifestXml());
        }

        return file;
    }

    private static Path writeDelimitedExports(
            Path overviewDir,
            String fileBase,
            ExportBundle bundle,
            String delimiter,
            String extension
    ) throws IOException {
        Files.createDirectories(overviewDir);
        Path lastFile = null;

        for (ExportSheet sheet : bundle.sheets()) {
            String fileName = fileBase + "_" + sanitizeFileComponent(sheet.name()) + "." + extension;
            Path file = overviewDir.resolve(fileName);
            ensureParentDirectory(file);

            StringBuilder sb = new StringBuilder();
            for (List<ExportCell> row : sheet.rows()) {
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        sb.append(delimiter);
                    }
                    sb.append(escapeDelimitedValue(cellDisplayValue(row.get(i).value()), delimiter));
                }
                sb.append(System.lineSeparator());
            }

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            lastFile = file;
        }

        return lastFile != null ? lastFile : overviewDir;
    }

    private static String buildOdsContentXml(ExportBundle bundle) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<office:document-content");
        xml.append(" xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"");
        xml.append(" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\"");
        xml.append(" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"");
        xml.append(" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\"");
        xml.append(" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\"");
        xml.append(" office:version=\"1.2\">");
        xml.append("<office:body><office:spreadsheet>");

        for (ExportSheet sheet : bundle.sheets()) {
            xml.append("<table:table table:name=\"").append(xmlEscape(sheet.name())).append("\">");
            for (List<ExportCell> row : sheet.rows()) {
                xml.append("<table:table-row>");
                if (row.isEmpty()) {
                    xml.append("<table:table-cell office:value-type=\"string\"><text:p></text:p></table:table-cell>");
                } else {
                    for (ExportCell cell : row) {
                        appendOdsCell(xml, cell.value());
                    }
                }
                xml.append("</table:table-row>");
            }
            xml.append("</table:table>");
        }

        xml.append("</office:spreadsheet></office:body></office:document-content>");
        return xml.toString();
    }

    private static void appendOdsCell(StringBuilder xml, Object value) {
        if (value instanceof Number number) {
            String numericValue = formatNumber(number);
            xml.append("<table:table-cell office:value-type=\"float\" office:value=\"")
                    .append(xmlEscape(numericValue))
                    .append("\"><text:p>")
                    .append(xmlEscape(numericValue))
                    .append("</text:p></table:table-cell>");
            return;
        }

        String text = value == null ? "" : value.toString();
        xml.append("<table:table-cell office:value-type=\"string\"><text:p>")
                .append(xmlEscape(text))
                .append("</text:p></table:table-cell>");
    }

    private static String buildOdsStylesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-styles
                  xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                  xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
                  office:version="1.2">
                  <office:styles/>
                  <office:automatic-styles/>
                  <office:master-styles/>
                </office:document-styles>
                """;
    }

    private static String buildOdsMetaXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-meta
                  xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                  xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
                  office:version="1.2">
                  <office:meta>
                    <meta:generator>QuPath ShapeX</meta:generator>
                  </office:meta>
                </office:document-meta>
                """;
    }

    private static String buildOdsSettingsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-settings
                  xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                  office:version="1.2">
                  <office:settings/>
                </office:document-settings>
                """;
    }

    private static String buildOdsManifestXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest
                  xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"
                  manifest:version="1.2">
                  <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>
                  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                  <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
                  <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
                  <manifest:file-entry manifest:full-path="settings.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>
                """;
    }

    private static void writeStoredZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(bytes);

        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeZipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static boolean isSettingsHeaderRow(List<List<ExportCell>> rows, int rowIndex) {
        if (rowIndex <= 0 || rowIndex >= rows.size())
            return false;
        List<ExportCell> row = rows.get(rowIndex);
        if (row.size() < 2)
            return false;
        for (int previousIndex = rowIndex - 1; previousIndex >= 0; previousIndex--) {
            List<ExportCell> previousRow = rows.get(previousIndex);
            if (previousRow.isEmpty())
                continue;
            return previousRow.size() == 1;
        }
        return false;
    }

    private static List<ExportCell> cells(Object... values) {
        List<ExportCell> row = new ArrayList<>(values.length);
        for (Object value : values) {
            row.add(new ExportCell(value, null));
        }
        return row;
    }

    private static String normalizeMetadataValue(String value) {
        return value == null || value.isBlank()
                ? ShapeXResources.get("common.unknown")
                : value.trim();
    }

    private static String formatBoolean(Boolean value) {
        if (value == null)
            return ShapeXResources.get("common.unknown");
        return value
                ? ShapeXResources.get("common.yes")
                : ShapeXResources.get("common.no");
    }

    private static Object formatArea(Double area) {
        return area != null && Double.isFinite(area)
                ? area
                : ShapeXResources.get("overview.area.unavailable");
    }

    private static String formatUtilization(int used, int available) {
        double percent = available > 0 ? (double) used / available * 100.0 : 0.0;
        return String.format(Locale.ROOT, "%.1f%% (%d/%d)", percent, used, available);
    }

    private static String imageDetail(
            Map<QuPathImageDetailsExtractor.Slot, String> details,
            QuPathImageDetailsExtractor.Slot slot
    ) {
        return normalizeMetadataValue(details.get(slot));
    }

    private static void writePoiCellValue(Cell cell, Object value) {
        if (value instanceof Integer integer) {
            cell.setCellValue(integer);
        } else if (value instanceof Long longValue) {
            cell.setCellValue(longValue);
        } else if (value instanceof Double doubleValue) {
            cell.setCellValue(doubleValue);
        } else if (value instanceof Float floatValue) {
            cell.setCellValue(floatValue);
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(value == null ? "" : value.toString());
        }
    }

    private static CellStyle createHeaderStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);

        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createSectionStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);

        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private static CellStyle createEmptyWellStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createSampleBaseStyle(Workbook workbook) {
        CellStyle style = createEmptyWellStyle(workbook);
        style.setWrapText(false);
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) 8);
        style.setFont(font);
        return style;
    }

    private static CellStyle createSampleColorStyle(
            Workbook workbook,
            CellStyle sampleBaseStyle,
            int argb
    ) {
        CellStyle style = workbook.createCellStyle();
        style.cloneStyleFrom(sampleBaseStyle);

        int rr = (argb >> 16) & 0xFF;
        int gg = (argb >> 8) & 0xFF;
        int bb = argb & 0xFF;

        if (style instanceof XSSFCellStyle xssfStyle) {
            xssfStyle.setFillForegroundColor(
                    new XSSFColor(new byte[]{(byte) rr, (byte) gg, (byte) bb}, null)
            );
            xssfStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        return style;
    }

    private static Map<Integer, Map<String, String>> buildNegativeControlLabelsByPlate(
            MultiPlateAssignmentModel plateSet
    ) {
        Map<Integer, Map<String, String>> byPlate = new HashMap<>();

        for (int plateIndex = 0; plateIndex < plateSet.getPlateCount(); plateIndex++) {
            PlateAssignmentModel plate = plateSet.getPlate(plateIndex);
            if (plate == null)
                continue;

            List<String> sortedNegWells =
                    PlateLayoutUtils.sortWells(plate.getNegativeControlWells());
            Set<String> excludedWells = plateSet.getExcludedWells(plateIndex);
            Map<String, String> labelsByWell = new LinkedHashMap<>();

            for (int i = 0; i < sortedNegWells.size(); i++) {
                String well = sortedNegWells.get(i);
                if (excludedWells.contains(well))
                    continue;
                labelsByWell.put(well, ShapeXResources.format("overview.negative_control.label", i + 1));
            }

            if (!labelsByWell.isEmpty()) {
                byPlate.put(plateIndex, labelsByWell);
            }
        }

        return byPlate;
    }

    private static String cellDisplayValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String escapeDelimitedValue(String value, String delimiter) {
        if (value == null)
            return "";

        boolean mustQuote = value.contains("\"")
                || value.contains("\n")
                || value.contains("\r")
                || value.contains(delimiter);

        String escaped = value.replace("\"", "\"\"");
        return mustQuote ? "\"" + escaped + "\"" : escaped;
    }

    private static String sanitizeFileComponent(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        return sanitized.isBlank() ? "sheet" : sanitized;
    }

    private static String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String formatNumber(Number number) {
        if (number instanceof Float || number instanceof Double) {
            return Double.toString(number.doubleValue());
        }
        return Long.toString(number.longValue());
    }

    private static String formatColorHex(int argb) {
        int rr = (argb >> 16) & 0xFF;
        int gg = (argb >> 8) & 0xFF;
        int bb = argb & 0xFF;
        return String.format(Locale.ROOT, "#%02X%02X%02X", rr, gg, bb);
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String escapeAppleScriptPath(String path) {
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void ensureParentDirectory(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void replaceExistingPath(Path path) throws IOException {
        if (!Files.exists(path))
            return;

        if (Files.isDirectory(path)) {
            List<Path> toDelete = Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path candidate : toDelete) {
                Files.deleteIfExists(candidate);
            }
            return;
        }

        Files.deleteIfExists(path);
    }
}
