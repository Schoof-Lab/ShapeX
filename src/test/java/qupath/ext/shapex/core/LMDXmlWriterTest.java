package qupath.ext.shapex.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.ROIs;

class LMDXmlWriterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void emitted1536CapIdAndCommentUseTheLmdRowSequence() throws IOException {
        Path xml = tempDirectory.resolve("1536.xml");

        LMDXmlWriter.writeXml(
                xml,
                createImageData(),
                "Slide",
                1,
                1,
                Map.of("Tumor", "AB48"),
                false,
                true,
                Set.of(),
                32,
                48
        );

        String contents = Files.readString(xml);
        assertTrue(contents.contains("<!-- Tumor (BB48) -->"));
        assertTrue(contents.contains("<CapID>BB48</CapID>"));
        assertFalse(contents.contains("<CapID>AB48</CapID>"));
    }

    @Test
    void emittedNon1536CapIdKeepsTheShapeXRowSequence() throws IOException {
        Path xml = tempDirectory.resolve("custom.xml");

        LMDXmlWriter.writeXml(
                xml,
                createImageData(),
                "Slide",
                1,
                1,
                Map.of("Tumor", "AB48"),
                false,
                true,
                Set.of(),
                32,
                47
        );

        String contents = Files.readString(xml);
        assertTrue(contents.contains("<!-- Tumor (AB48) -->"));
        assertTrue(contents.contains("<CapID>AB48</CapID>"));
        assertFalse(contents.contains("<CapID>BB48</CapID>"));
    }

    private static ImageData<BufferedImage> createImageData() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathClass calibrationClass = PathClass.fromString(CalibrationResolver.AUTO_CLASS);

        for (int index = 1; index <= 3; index++) {
            PathObject calibration = PathObjects.createAnnotationObject(
                    ROIs.createPointsROI(index, index),
                    calibrationClass
            );
            calibration.setName("Calibration " + index);
            hierarchy.addObject(calibration);
        }

        PathObject sample = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(10, 10, 20, 20),
                PathClass.fromString("Tumor")
        );
        sample.setName("Biopsy");
        hierarchy.addObject(sample);

        return new ImageData<>(
                new WrappedBufferedImageServer(
                        "Test image",
                        new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
                ),
                hierarchy,
                ImageData.ImageType.OTHER
        );
    }
}
