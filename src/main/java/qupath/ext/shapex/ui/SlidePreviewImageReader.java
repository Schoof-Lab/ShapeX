package qupath.ext.shapex.ui;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Shared image-region loading for ShapeX annotation previews.
 */
final class SlidePreviewImageReader {

    private SlidePreviewImageReader() {}

    /**
     * Must be called from the JavaFX application thread because the fallback reads the active viewer.
     */
    static BufferedImage readRegionOrViewerThumbnail(
            ImageServer<?> server,
            int x,
            int y,
            int width,
            int height,
            int z,
            int t,
            double downsample
    ) {
        BufferedImage region = readServerRegion(server, x, y, width, height, z, t, downsample);
        return region != null
                ? region
                : readViewerThumbnailRegion(server, x, y, width, height, z, t);
    }

    static BufferedImage readServerRegion(
            ImageServer<?> server,
            int x,
            int y,
            int width,
            int height,
            int z,
            int t,
            double downsample
    ) {
        if (server == null || width <= 0 || height <= 0
                || !Double.isFinite(downsample) || downsample <= 0.0) {
            return null;
        }

        try {
            RegionRequest request = RegionRequest.createInstance(
                    server.getPath(),
                    downsample,
                    x,
                    y,
                    width,
                    height,
                    z,
                    t
            );
            Object region = server.readRegion(request);
            if (region instanceof BufferedImage bufferedImage
                    && bufferedImage.getWidth() > 0
                    && bufferedImage.getHeight() > 0) {
                return bufferedImage;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Must be called from the JavaFX application thread.
     */
    static BufferedImage readViewerThumbnailRegion(
            ImageServer<?> sourceServer,
            int x,
            int y,
            int width,
            int height,
            int z,
            int t
    ) {
        if (sourceServer == null || width <= 0 || height <= 0) {
            return null;
        }

        QuPathGUI qupath = QuPathGUI.getInstance();
        if (qupath == null) {
            return null;
        }
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || viewer.getServer() == null) {
            return null;
        }

        ImageServer<?> viewerServer = viewer.getServer();
        if (viewerServer.getWidth() <= 0 || viewerServer.getHeight() <= 0
                || !Objects.equals(sourceServer.getPath(), viewerServer.getPath())
                || viewer.getZPosition() != z
                || viewer.getTPosition() != t) {
            return null;
        }

        BufferedImage thumbnail;
        try {
            thumbnail = viewer.getThumbnail();
            if (thumbnail == null || thumbnail.getWidth() <= 0 || thumbnail.getHeight() <= 0) {
                thumbnail = viewer.getRGBThumbnail();
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (thumbnail == null || thumbnail.getWidth() <= 0 || thumbnail.getHeight() <= 0) {
            return null;
        }

        double scaleX = thumbnail.getWidth() / (double) viewerServer.getWidth();
        double scaleY = thumbnail.getHeight() / (double) viewerServer.getHeight();
        if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY) || scaleX <= 0.0 || scaleY <= 0.0) {
            return null;
        }

        int x0 = Math.max(0, Math.min((int) Math.floor(x * scaleX), thumbnail.getWidth() - 1));
        int y0 = Math.max(0, Math.min((int) Math.floor(y * scaleY), thumbnail.getHeight() - 1));
        int x1 = Math.max(
                x0 + 1,
                Math.min((int) Math.ceil((x + width) * scaleX), thumbnail.getWidth())
        );
        int y1 = Math.max(
                y0 + 1,
                Math.min((int) Math.ceil((y + height) * scaleY), thumbnail.getHeight())
        );
        int croppedWidth = x1 - x0;
        int croppedHeight = y1 - y0;
        if (croppedWidth <= 0 || croppedHeight <= 0) {
            return null;
        }

        BufferedImage cropped = new BufferedImage(croppedWidth, croppedHeight, BufferedImage.TYPE_INT_ARGB);
        var graphics = cropped.createGraphics();
        try {
            graphics.drawImage(thumbnail, 0, 0, croppedWidth, croppedHeight, x0, y0, x1, y1, null);
        } finally {
            graphics.dispose();
        }
        return cropped;
    }
}
