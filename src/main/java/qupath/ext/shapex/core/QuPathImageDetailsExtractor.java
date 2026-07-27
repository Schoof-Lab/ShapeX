package qupath.ext.shapex.core;

import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ServerTools;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Extracts the rows shown by QuPath 0.7.0's Image tab in the same top-to-bottom order.
 */
public final class QuPathImageDetailsExtractor {

    public enum Slot {
        NAME,
        URI,
        PIXEL_TYPE,
        MAGNIFICATION,
        WIDTH,
        HEIGHT,
        DIMENSIONS,
        PIXEL_WIDTH,
        PIXEL_HEIGHT,
        Z_SPACING,
        UNCOMPRESSED_SIZE,
        SERVER_TYPE,
        PYRAMID,
        METADATA_CHANGED,
        IMAGE_TYPE,
        STAIN_1,
        STAIN_2,
        STAIN_3,
        BACKGROUND
    }

    public record Entry(Slot slot, String key, String value) {}

    private QuPathImageDetailsExtractor() {}

    public static List<Entry> extract(ImageData<?> imageData, String imageName) {
        if (imageData == null || imageData.getServer() == null) {
            return List.of();
        }

        List<Entry> entries = new ArrayList<>();
        ImageServer<?> server = imageData.getServer();
        var calibration = server.getPixelCalibration();

        add(entries, Slot.NAME, "Name", resolveImageName(imageName, server));

        Collection<URI> uris = server.getURIs();
        add(entries, Slot.URI, uris.size() == 1 ? "URI" : "URIs", formatUris(uris));

        String pixelType = server.getPixelType().toString().toLowerCase();
        if (server.isRGB()) {
            pixelType += " (rgb)";
        }
        add(entries, Slot.PIXEL_TYPE, "Pixel type", pixelType);

        double magnification = server.getMetadata().getMagnification();
        add(
                entries,
                Slot.MAGNIFICATION,
                "Magnification",
                Double.isNaN(magnification) ? "Unknown" : magnification
        );

        add(
                entries,
                Slot.WIDTH,
                "Width",
                calibration.hasPixelSizeMicrons()
                        ? String.format(
                                "%s px (%.2f %s)",
                                server.getWidth(),
                                server.getWidth() * calibration.getPixelWidthMicrons(),
                                GeneralTools.micrometerSymbol()
                        )
                        : String.format("%s px", server.getWidth())
        );
        add(
                entries,
                Slot.HEIGHT,
                "Height",
                calibration.hasPixelSizeMicrons()
                        ? String.format(
                                "%s px (%.2f %s)",
                                server.getHeight(),
                                server.getHeight() * calibration.getPixelHeightMicrons(),
                                GeneralTools.micrometerSymbol()
                        )
                        : String.format("%s px", server.getHeight())
        );
        add(
                entries,
                Slot.DIMENSIONS,
                "Dimensions (CZT)",
                String.format("%d x %d x %d", server.nChannels(), server.nZSlices(), server.nTimepoints())
        );
        add(
                entries,
                Slot.PIXEL_WIDTH,
                "Pixel width",
                calibration.hasPixelSizeMicrons()
                        ? String.format("%.4f %s", calibration.getPixelWidthMicrons(), GeneralTools.micrometerSymbol())
                        : "Unknown"
        );
        add(
                entries,
                Slot.PIXEL_HEIGHT,
                "Pixel height",
                calibration.hasPixelSizeMicrons()
                        ? String.format("%.4f %s", calibration.getPixelHeightMicrons(), GeneralTools.micrometerSymbol())
                        : "Unknown"
        );
        if (server.nZSlices() != 1) {
            add(
                    entries,
                    Slot.Z_SPACING,
                    "Z-spacing",
                    calibration.hasZSpacingMicrons()
                            ? String.format("%.4f %s", calibration.getZSpacingMicrons(), GeneralTools.micrometerSymbol())
                            : "Unknown"
            );
        }

        double uncompressedSize = server.getWidth() / 1024.0
                * server.getHeight() / 1024.0
                * server.getPixelType().getBytesPerPixel()
                * server.nChannels()
                * server.nZSlices()
                * server.nTimepoints();
        String sizeUnit = " MB";
        if (uncompressedSize > 1000) {
            uncompressedSize /= 1024.0;
            sizeUnit = " GB";
        }
        add(
                entries,
                Slot.UNCOMPRESSED_SIZE,
                "Uncompressed size",
                GeneralTools.formatNumber(uncompressedSize, 1) + sizeUnit
        );
        add(entries, Slot.SERVER_TYPE, "Server type", server.getServerType());
        add(
                entries,
                Slot.PYRAMID,
                "Pyramid",
                server.nResolutions() == 1
                        ? "No"
                        : GeneralTools.arrayToString(
                                Locale.getDefault(Locale.Category.FORMAT),
                                server.getPreferredDownsamples(),
                                1
                        )
        );
        add(
                entries,
                Slot.METADATA_CHANGED,
                "Metadata changed",
                Objects.equals(server.getMetadata(), server.getOriginalMetadata()) ? "No" : "Yes"
        );
        add(entries, Slot.IMAGE_TYPE, "Image type", imageData.getImageType());

        if (imageData.isBrightfield()) {
            var stains = imageData.getColorDeconvolutionStains();
            add(entries, Slot.STAIN_1, "Stain 1", stains.getStain(1));
            add(entries, Slot.STAIN_2, "Stain 2", stains.getStain(2));
            add(entries, Slot.STAIN_3, "Stain 3", stains.getStain(3));
            add(
                    entries,
                    Slot.BACKGROUND,
                    "Background",
                    GeneralTools.arrayToString(
                            Locale.getDefault(Locale.Category.FORMAT),
                            new double[]{stains.getMaxRed(), stains.getMaxGreen(), stains.getMaxBlue()},
                            2
                    )
            );
        }

        return List.copyOf(entries);
    }

    private static void add(List<Entry> entries, Slot slot, String key, Object value) {
        entries.add(new Entry(slot, key, value == null ? "" : value.toString()));
    }

    private static String resolveImageName(String imageName, ImageServer<?> server) {
        return imageName == null || imageName.isBlank()
                ? ServerTools.getDisplayableImageName(server)
                : imageName;
    }

    private static String formatUris(Collection<URI> uris) {
        if (uris == null || uris.isEmpty()) {
            return "Not available";
        }
        if (uris.size() == 1) {
            return decodeUri(uris.iterator().next());
        }
        return "[" + String.join(", ", uris.stream()
                .map(QuPathImageDetailsExtractor::decodeUri)
                .toList()) + "]";
    }

    private static String decodeUri(URI uri) {
        try {
            return URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return uri.toString();
        }
    }
}
