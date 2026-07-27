package qupath.ext.shapex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessorTest {

    private static final LocalDateTime EXPORT_TIME =
            LocalDateTime.of(2026, 7, 23, 15, 17, 59);

    @TempDir
    Path baseDirectory;

    @Test
    void firstExportUsesShapeXFolderName() throws IOException {
        Path outputDirectory = Processor.createOutputDirectory(baseDirectory, EXPORT_TIME);

        assertEquals("ShapeX", outputDirectory.getFileName().toString());
        assertTrue(Files.isDirectory(outputDirectory));
    }

    @Test
    void firstExportCreatesMissingBaseDirectory() throws IOException {
        Path missingBaseDirectory = baseDirectory.resolve("new").resolve("output");

        Path outputDirectory = Processor.createOutputDirectory(
                missingBaseDirectory,
                EXPORT_TIME
        );

        assertTrue(Files.isDirectory(missingBaseDirectory));
        assertEquals("ShapeX", outputDirectory.getFileName().toString());
        assertTrue(Files.isDirectory(outputDirectory));
    }

    @Test
    void laterExportUsesTimestampedFolderName() throws IOException {
        Files.createDirectory(baseDirectory.resolve("ShapeX"));

        Path outputDirectory = Processor.createOutputDirectory(baseDirectory, EXPORT_TIME);

        assertEquals("20260723_151759_ShapeX", outputDirectory.getFileName().toString());
        assertTrue(Files.isDirectory(outputDirectory));
    }

    @Test
    void exportsWithinTheSameSecondRemainUnique() throws IOException {
        Files.createDirectory(baseDirectory.resolve("ShapeX"));
        Files.createDirectory(baseDirectory.resolve("20260723_151759_ShapeX"));

        Path outputDirectory = Processor.createOutputDirectory(baseDirectory, EXPORT_TIME);

        assertEquals("20260723_151759_1_ShapeX", outputDirectory.getFileName().toString());
        assertTrue(Files.isDirectory(outputDirectory));
    }
}
