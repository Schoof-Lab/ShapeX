package qupath.ext.shapex.ui;

import qupath.ext.shapex.ShapeXResources;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;

/**
 * Converts filesystem exceptions into short, user-facing explanations.
 */
final class FileErrorMessages {

    private FileErrorMessages() {}

    static String concise(Exception exception) {
        if (exception instanceof AccessDeniedException accessDeniedException) {
            String blockedPath = accessDeniedException.getFile();
            if (blockedPath == null || blockedPath.isBlank()) {
                blockedPath = ShapeXResources.get("common.unknown");
            }
            return ShapeXResources.format(
                    "error.output.folder.permission_denied",
                    blockedPath,
                    System.getProperty(
                            "user.home",
                            ShapeXResources.get("common.unknown")
                    )
            );
        }
        if (exception instanceof FileSystemException fileSystemException
                && fileSystemException.getReason() != null
                && !fileSystemException.getReason().isBlank()) {
            return fileSystemException.getReason();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? ShapeXResources.get("error.unknown")
                : message;
    }
}
