package qupath.ext.shapex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.ROIs;

class PlateOverviewWriterTest {

    private static final String SLIDE = "Slide";

    @TempDir
    Path tempDirectory;

    @Test
    void plateCsvUsesActualAnnotationMetadataForPriorityAndDisambiguation()
            throws Exception {
        PathObject stroma = annotation("Stroma", "Name is ignored", uuid(1));
        PathObject namedUnclassified = annotation(null, "Named region", uuid(2));
        PathObject unnamedUnclassified = annotation(null, null, uuid(3));
        PathObject leftTumor = annotation("Tumor", "Left", uuid(4));
        PathObject rightTumor = annotation("Tumor", "Right", uuid(5));
        PathObject firstBiopsy = annotation("Tumor", "Biopsy", uuid(6));
        PathObject secondBiopsy = annotation("Tumor", "Biopsy", uuid(7));
        List<PathObject> annotations = List.of(
                stroma,
                namedUnclassified,
                unnamedUnclassified,
                leftTumor,
                rightTumor,
                firstBiopsy,
                secondBiopsy
        );

        ImageData<BufferedImage> imageData = createImageData(SLIDE, annotations);
        try {
            Set<String> sampleKeys = AnnotationUtils.getAnnotationSampleKeys(
                    imageData.getHierarchy(),
                    SLIDE,
                    true,
                    false,
                    false
            );
            MultiPlateAssignmentModel plateSet = new MultiPlateAssignmentModel(
                    sampleKeys,
                    2,
                    4,
                    "1",
                    1,
                    false
            );

            Path plateCsv = PlateOverviewWriter.generateOverview(
                    tempDirectory,
                    plateSet,
                    Map.of(SLIDE, imageData),
                    2,
                    4,
                    PlateOverviewWriter.OverviewExportFormat.CSV,
                    false,
                    false
            );
            String contents = Files.readString(plateCsv);

            assertTrue(contents.contains("Stroma"));
            assertFalse(contents.contains("Name is ignored Stroma"));
            assertTrue(contents.contains("Named region"));
            assertTrue(contents.contains(uuid(3).toString()));
            assertTrue(contents.contains("Left Tumor"));
            assertTrue(contents.contains("Right Tumor"));
            assertTrue(contents.contains("Biopsy Tumor [ID: " + uuid(6) + "]"));
            assertTrue(contents.contains("Biopsy Tumor [ID: " + uuid(7) + "]"));
        } finally {
            imageData.close();
        }
    }

    @Test
    void pooledPlateCsvIncludesEveryContributingObjectIdInSortedOrder()
            throws Exception {
        Map<String, ImageData<BufferedImage>> images = new LinkedHashMap<>();
        images.put("Slide A", createImageData("Slide A", List.of(
                annotation("Tumor", "Biopsy", uuid(13)),
                annotation("Tumor", "Biopsy", uuid(11))
        )));
        images.put("Slide B", createImageData("Slide B", List.of(
                annotation("Tumor", "Biopsy", uuid(14)),
                annotation("Tumor", "Biopsy", uuid(12))
        )));

        try {
            Set<String> sampleKeys = new java.util.HashSet<>();
            for (var entry : images.entrySet()) {
                sampleKeys.addAll(AnnotationUtils.getAnnotationSampleKeys(
                        entry.getValue().getHierarchy(),
                        entry.getKey(),
                        true,
                        true,
                        true
                ));
            }
            MultiPlateAssignmentModel plateSet = new MultiPlateAssignmentModel(
                    sampleKeys,
                    1,
                    2,
                    "1",
                    1,
                    false
            );

            Path plateCsv = PlateOverviewWriter.generateOverview(
                    tempDirectory.resolve("pooled"),
                    plateSet,
                    images,
                    1,
                    2,
                    PlateOverviewWriter.OverviewExportFormat.CSV,
                    true,
                    true
            );
            String contents = Files.readString(plateCsv);

            assertTrue(contents.contains(
                    "Biopsy Tumor [IDs: " + uuid(11) + ", " + uuid(13) + "]"
            ));
            assertTrue(contents.contains(
                    "Biopsy Tumor [IDs: " + uuid(12) + ", " + uuid(14) + "]"
            ));
        } finally {
            for (ImageData<BufferedImage> imageData : images.values()) {
                imageData.close();
            }
        }
    }

    @Test
    void duplicateClassNamesOnSeparatePlatesRemainConcise() throws Exception {
        Map<String, ImageData<BufferedImage>> images = new LinkedHashMap<>();
        images.put("Slide A", createImageData("Slide A", List.of(
                annotation("Tumor", "Left", uuid(21))
        )));
        images.put("Slide B", createImageData("Slide B", List.of(
                annotation("Tumor", "Right", uuid(22))
        )));

        try {
            List<List<String>> fixedPlateSamples = new ArrayList<>();
            for (var entry : images.entrySet()) {
                fixedPlateSamples.add(List.copyOf(AnnotationUtils.getAnnotationSampleKeys(
                        entry.getValue().getHierarchy(),
                        entry.getKey(),
                        true,
                        true,
                        true
                )));
            }
            MultiPlateAssignmentModel plateSet = new MultiPlateAssignmentModel(
                    fixedPlateSamples,
                    1,
                    1,
                    "1",
                    false
            );

            Path outputDirectory = tempDirectory.resolve("separate-plates");
            PlateOverviewWriter.generateOverview(
                    outputDirectory,
                    plateSet,
                    images,
                    1,
                    1,
                    PlateOverviewWriter.OverviewExportFormat.CSV,
                    true,
                    true
            );

            Path overviewDirectory = outputDirectory.resolve("Overview");
            String plate1 = Files.readString(overviewDirectory.resolve("PlateOverview_Plate1.csv"));
            String plate2 = Files.readString(overviewDirectory.resolve("PlateOverview_Plate2.csv"));
            assertTrue(plate1.contains("Tumor"));
            assertTrue(plate2.contains("Tumor"));
            assertFalse(plate1.contains("Left Tumor"));
            assertFalse(plate2.contains("Right Tumor"));
            assertFalse(plate1.contains(uuid(21).toString()));
            assertFalse(plate2.contains(uuid(22).toString()));
        } finally {
            for (ImageData<BufferedImage> imageData : images.values()) {
                imageData.close();
            }
        }
    }

    @Test
    void settingsSheetCapturesRunProjectQuPathAndPlateMetadata() throws Exception {
        MultiPlateAssignmentModel plateSet = new MultiPlateAssignmentModel(
                List.of("Class A", "Class B"),
                2,
                3,
                "424242",
                1,
                false
        );
        plateSet.setDisabledSamples(Set.of("Class B"));
        plateSet.setSharedPlateDefaults(Set.of("B3"), 1);

        ImageData<BufferedImage> imageData = createSettingsImageData();
        try {
            PlateOverviewWriter.RunMetadata metadata = new PlateOverviewWriter.RunMetadata(
                    "2026-07-27T12:34:56+02:00",
                    tempDirectory.resolve("ShapeX").toString(),
                    "Example Project",
                    "file:///data/example.qpproj",
                    "0.7",
                    "0.7.0",
                    "QuPath 0.7.0 build 123",
                    List.of("Plate overview", "anndata"),
                    true,
                    2,
                    1,
                    0
            );

            Path workbookPath = PlateOverviewWriter.generateOverview(
                    tempDirectory.resolve("settings"),
                    plateSet,
                    Map.of("Slide A", imageData),
                    2,
                    3,
                    PlateOverviewWriter.OverviewExportFormat.EXCEL,
                    false,
                    true,
                    Map.of(),
                    metadata
            );

            try (var input = Files.newInputStream(workbookPath);
                 Workbook workbook = new XSSFWorkbook(input)) {
                Sheet settings = workbook.getSheet("Settings");
                assertNotNull(settings);
                Map<String, String> values = propertyValues(settings);

                assertEquals("2026-07-27T12:34:56+02:00", values.get("Generated At:"));
                assertEquals("Example Project", values.get("Project Name:"));
                assertEquals("0.7.0", values.get("QuPath Version:"));
                assertEquals("QuPath 0.7.0 build 123", values.get("QuPath Build:"));
                assertEquals("Plate overview, anndata", values.get("Requested Outputs:"));
                assertEquals("Yes", values.get("Include Unclassified Annotations:"));
                assertEquals("No", values.get("Map Image to Plate:"));
                assertEquals("Yes", values.get("Pool Annotations by Class:"));
                assertEquals("2 rows × 3 columns (6 wells per plate)", values.get("Plate Dimensions:"));
                assertEquals("Sequential", values.get("Sample Assignment:"));
                assertEquals("None", values.get("Random Seed Used:"));
                assertEquals("1", values.get("Total Samples:"));
                assertEquals("1", values.get("Disabled Samples:"));
                assertEquals("1", values.get("Total Exported Shapes:"));
                assertEquals("100", values.get("Total Exported Area (µm²):"));
                assertEquals("1", values.get("Total Negative Controls:"));
                assertEquals("1", values.get("Total Unavailable Wells:"));
            }
        } finally {
            imageData.close();
        }
    }

    @Test
    void uncalibratedImagesReportUnavailableMicronAreasInsteadOfNaN() throws Exception {
        ImageData<BufferedImage> imageData = createImageData(
                SLIDE,
                List.of(annotation("Tumor", "Biopsy", uuid(31)))
        );
        try {
            assertFalse(imageData.getServer().getPixelCalibration().hasPixelSizeMicrons());
            MultiPlateAssignmentModel plateSet = new MultiPlateAssignmentModel(
                    Set.of("Tumor"),
                    1,
                    1,
                    "1",
                    1,
                    false
            );

            Path workbookPath = PlateOverviewWriter.generateOverview(
                    tempDirectory.resolve("uncalibrated"),
                    plateSet,
                    Map.of(SLIDE, imageData),
                    1,
                    1,
                    PlateOverviewWriter.OverviewExportFormat.EXCEL,
                    false,
                    true
            );

            try (var input = Files.newInputStream(workbookPath);
                 Workbook workbook = new XSSFWorkbook(input)) {
                DataFormatter formatter = new DataFormatter(Locale.ROOT);
                String unavailable = "Unknown (pixel calibration unavailable)";

                Sheet overview = workbook.getSheet("Overview");
                assertNotNull(overview);
                assertEquals(
                        unavailable,
                        formatter.formatCellValue(overview.getRow(1).getCell(4))
                );

                Sheet settings = workbook.getSheet("Settings");
                assertNotNull(settings);
                assertEquals(
                        unavailable,
                        propertyValues(settings).get("Total Exported Area (µm²):")
                );
            }
        } finally {
            imageData.close();
        }
    }

    private static ImageData<BufferedImage> createSettingsImageData() {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        WrappedBufferedImageServer server = new WrappedBufferedImageServer("Slide A", image);
        server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                .pixelSizeMicrons(1.0, 1.0)
                .build());

        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(0, 0, 10, 10),
                PathClass.fromString("Class A")
        ));
        hierarchy.addObject(PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(20, 0, 20, 10),
                PathClass.fromString("Class B")
        ));
        return new ImageData<>(server, hierarchy, ImageData.ImageType.OTHER);
    }

    private static Map<String, String> propertyValues(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Map<String, String> values = new LinkedHashMap<>();
        for (Row row : sheet) {
            if (row.getCell(0) == null || row.getCell(1) == null) {
                continue;
            }
            String key = formatter.formatCellValue(row.getCell(0));
            if (!key.isBlank()) {
                values.put(key, formatter.formatCellValue(row.getCell(1)));
            }
        }
        return values;
    }

    private static ImageData<BufferedImage> createImageData(
            String imageName,
            List<PathObject> annotations
    ) {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        for (PathObject annotation : annotations) {
            hierarchy.addObject(annotation);
        }
        return new ImageData<>(
                new WrappedBufferedImageServer(
                        imageName,
                        new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
                ),
                hierarchy,
                ImageData.ImageType.OTHER
        );
    }

    private static PathObject annotation(
            String className,
            String annotationName,
            UUID objectId
    ) {
        PathObject annotation = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(1, 1, 10, 10),
                className == null ? null : PathClass.fromString(className)
        );
        annotation.setName(annotationName);
        annotation.setID(objectId);
        return annotation;
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
