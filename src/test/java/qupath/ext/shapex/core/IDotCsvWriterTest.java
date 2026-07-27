package qupath.ext.shapex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IDotCsvWriterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void csvTargetWellsUseTheIDot1536Notation() throws IOException {
        Path csv = tempDirectory.resolve("idot.csv");

        IDotCsvWriter.writeSingleCsv(
                csv,
                "Project",
                List.of("A1", "D2", "E3", "AF48"),
                1.0,
                "Liquid",
                32,
                48
        );

        List<String> dispenseRows = Files.readAllLines(csv).subList(4, 8);
        assertEquals("A1;Aa1;1.0;Liquid;;;;", dispenseRows.get(0));
        assertEquals("A1;Ad2;1.0;Liquid;;;;", dispenseRows.get(1));
        assertEquals("A1;Ba3;1.0;Liquid;;;;", dispenseRows.get(2));
        assertEquals("A1;Hd48;1.0;Liquid;;;;", dispenseRows.get(3));
    }

    @Test
    void legacyWriterAndNon1536PlatesKeepShapeXWellLabels() throws IOException {
        Path legacyCsv = tempDirectory.resolve("legacy.csv");
        Path plate384Csv = tempDirectory.resolve("384.csv");

        IDotCsvWriter.writeSingleCsv(
                legacyCsv,
                "Project",
                List.of("A1", "P24"),
                1.0,
                "Liquid"
        );
        IDotCsvWriter.writeSingleCsv(
                plate384Csv,
                "Project",
                List.of("A1", "P24"),
                1.0,
                "Liquid",
                16,
                24
        );

        assertEquals(
                Files.readAllLines(legacyCsv).subList(4, 6),
                Files.readAllLines(plate384Csv).subList(4, 6)
        );
        assertEquals(
                List.of(
                        "A1;A1;1.0;Liquid;;;;",
                        "A1;P24;1.0;Liquid;;;;"
                ),
                Files.readAllLines(plate384Csv).subList(4, 6)
        );
    }
}
