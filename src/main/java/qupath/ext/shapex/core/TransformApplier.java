package qupath.ext.shapex.core;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.ext.shapex.ShapeXResources;

/**
 * Applies a permanent image-space flip to the active image and its annotations.
 */
public final class TransformApplier {

    private TransformApplier() {}

    public static void applyTransform(
            boolean flipHorizontal,
            boolean flipVertical
    ) {

        QuPathGUI gui = QuPathGUI.getInstance();
        ImageData<?> imageData = gui.getImageData();

        if (imageData == null) {
            throw new IllegalStateException(ShapeXResources.get("error.image.none.open"));
        }

        @SuppressWarnings("unchecked")
        ImageServer<BufferedImage> server =
                (ImageServer<BufferedImage>) imageData.getServer();

        AffineTransform transform =
                createCenterFlipTransform(
                        server, flipHorizontal, flipVertical
                );

        TransformedServerBuilder builder =
                new TransformedServerBuilder(server);

        builder.transform(transform);

        ImageServer<BufferedImage> transformedServer = builder.build();

        // Keep annotations aligned with the transformed pixels.
        var newHierarchy =
                AnnotationUtils.transformHierarchy(
                        imageData.getHierarchy(),
                        transform
                );

        ImageData<BufferedImage> newImageData =
                new ImageData<>(transformedServer, newHierarchy, imageData.getImageType());

        try {
                gui.getViewer().setImageData(newImageData);

                // Rename image in project if applicable
                var project = gui.getProject();
                if (project != null) {

                    var entry = project.getEntry(newImageData);
                    if (entry != null) {

                        String baseName =
                                imageData.getServer().getMetadata().getName();

                        String suffix;
                        if (flipHorizontal && flipVertical)
                            suffix = ShapeXResources.get("transform.suffix.flipped_xy");
                        else if (flipHorizontal)
                            suffix = ShapeXResources.get("transform.suffix.flipped_x");
                        else
                            suffix = ShapeXResources.get("transform.suffix.flipped_y");

                        entry.setImageName(baseName + suffix);
                    }
                }

        } catch (IOException e) {
            throw new RuntimeException(ShapeXResources.get("error.transform.image_data.apply"), e);
        }

        gui.refreshProject();

        String baseName =
                imageData.getServer().getMetadata().getName();

        String suffix;
        if (flipHorizontal && flipVertical)
            suffix = ShapeXResources.get("transform.suffix.flipped_xy");
        else if (flipHorizontal)
            suffix = ShapeXResources.get("transform.suffix.flipped_x");
        else
            suffix = ShapeXResources.get("transform.suffix.flipped_y");

        var metadata = newImageData.getServer().getMetadata();
        var newMetadata = new ImageServerMetadata.Builder(metadata)
                .name(baseName + suffix)
                .build();
        newImageData.updateServerMetadata(newMetadata);
    }

    /** Image-space equivalent of JavaFX Scale(flip, centerX, centerY) */
    private static AffineTransform createCenterFlipTransform(
            ImageServer<?> server,
            boolean flipHorizontal,
            boolean flipVertical
    ) {
        double cx = server.getWidth() / 2.0;
        double cy = server.getHeight() / 2.0;

        AffineTransform at = new AffineTransform();

        at.translate(cx, cy);
        at.scale(
                flipHorizontal ? -1 : 1,
                flipVertical ? -1 : 1
        );
        at.translate(-cx, -cy);

        return at;
    }
}
