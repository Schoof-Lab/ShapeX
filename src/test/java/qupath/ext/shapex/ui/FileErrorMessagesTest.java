package qupath.ext.shapex.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;

import org.junit.jupiter.api.Test;

class FileErrorMessagesTest {

    @Test
    void accessDeniedErrorExplainsTheBlockedPathAndSuggestsTheHomeDirectory() {
        String message = FileErrorMessages.concise(
                new AccessDeniedException("/Users/my")
        );

        assertTrue(message.contains("Permission denied"));
        assertTrue(message.contains("/Users/my"));
        assertTrue(message.contains(System.getProperty("user.home")));
    }

    @Test
    void filesystemReasonIsShownWithoutTheRawExceptionType() {
        String message = FileErrorMessages.concise(
                new FileSystemException(
                        "/my/favorite/folder",
                        null,
                        "Read-only file system"
                )
        );

        assertEquals("Read-only file system", message);
    }
}
