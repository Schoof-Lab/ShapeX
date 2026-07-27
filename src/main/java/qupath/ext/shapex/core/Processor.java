package qupath.ext.shapex.core;

import qupath.lib.projects.Project;
import qupath.ext.shapex.ShapeXResources;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves and creates ShapeX output directories.
 */
public final class Processor {

    private static final DateTimeFormatter OUTPUT_FOLDER_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    private Processor() {}

    public static final String DEFAULT_OUTPUT_FOLDER = ShapeXResources.get("processor.output.folder");

    /**
     * Returns the base directory for outputs without creating it.
     */
    public static Path getDefaultOutputDirectory(Project<?> project) {
        return project.getPath().getParent();
    }

    /**
     * Creates a new ShapeX output directory inside {@code baseDir}.
     * The first export in the selected base directory uses {@code ShapeX};
     * later exports use the current local time, for example
     * {@code 20260723_151759_ShapeX}.
     *
     * @param baseDir The directory chosen by the user
     * @return The newly created output directory
     */
    public static Path createOutputDirectory(Path baseDir) throws IOException {
        return createOutputDirectory(baseDir, LocalDateTime.now());
    }

    static Path createOutputDirectory(Path baseDir, LocalDateTime timestamp) throws IOException {
        if (baseDir == null) {
            throw new IOException(ShapeXResources.get("error.output.base.directory.not_set"));
        }

        Objects.requireNonNull(timestamp, "timestamp");
        Files.createDirectories(baseDir);

        Path initialDirectory = baseDir.resolve(DEFAULT_OUTPUT_FOLDER);
        try {
            return Files.createDirectory(initialDirectory);
        } catch (FileAlreadyExistsException ignored) {
            // Preserve the original ShapeX directory and name this export by its creation time.
        }

        String timestampPrefix = OUTPUT_FOLDER_TIMESTAMP_FORMAT.format(timestamp);
        int collisionSuffix = 0;

        while (true) {
            String folderName = timestampPrefix
                    + (collisionSuffix == 0 ? "" : "_" + collisionSuffix)
                    + "_"
                    + DEFAULT_OUTPUT_FOLDER;
            Path candidate = baseDir.resolve(folderName);
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // Multiple exports may start within the same second.
                collisionSuffix++;
            }
        }
    }
}
