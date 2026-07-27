package qupath.ext.shapex.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import qupath.ext.shapex.ShapeXResources;

class UserGuideLinkTest {

    private static final String GUIDE_REDIRECT_TARGET =
            "assets/guides/code/user_guide.html";

    @Test
    void informationButtonUsesCanonicalPagesRootThatRedirectsToBundledGuide()
            throws IOException {
        URI guideUri = URI.create(ShapeXResources.get("window.user_guide.url"));
        assertEquals("https", guideUri.getScheme());
        assertEquals("schoof-lab.github.io", guideUri.getHost());
        assertEquals("/ShapeX/", guideUri.getPath());

        Path docsDirectory = Path.of(System.getProperty("user.dir"), "docs");
        String index = Files.readString(docsDirectory.resolve("index.html"));
        assertTrue(index.contains("new URL(\"" + GUIDE_REDIRECT_TARGET + "\""));
        assertTrue(index.contains("href=\"" + GUIDE_REDIRECT_TARGET + "\""));
        assertTrue(index.contains("guideUrl.search = window.location.search"));
        assertTrue(index.contains("guideUrl.hash = window.location.hash"));

        Path guideFile = docsDirectory.resolve(GUIDE_REDIRECT_TARGET);
        assertTrue(Files.isRegularFile(guideFile));
        assertTrue(Files.readString(guideFile).contains("<title>ShapeX User Guide</title>"));

        Path fxmlFile = Path.of(
                System.getProperty("user.dir"),
                "src",
                "main",
                "resources",
                "qupath",
                "ext",
                "shapex",
                "ui",
                "shapex_control.fxml"
        );
        assertTrue(Files.readString(fxmlFile).contains("onAction=\"#handleOpenUserGuide\""));

        Path controllerFile = Path.of(
                System.getProperty("user.dir"),
                "src",
                "main",
                "java",
                "qupath",
                "ext",
                "shapex",
                "ui",
                "Controller.java"
        );
        String controller = Files.readString(controllerFile);
        assertTrue(controller.contains("private void handleOpenUserGuide(ActionEvent event)"));
        assertTrue(controller.contains("GuiTools.browseURI(USER_GUIDE_URI)"));
    }

    @Test
    void everyInternalGuideLinkTargetsAnExistingElement() throws IOException {
        Path guideFile = Path.of(
                System.getProperty("user.dir"),
                "docs",
                GUIDE_REDIRECT_TARGET
        );
        String guide = Files.readString(guideFile);

        Set<String> ids = matches(guide, Pattern.compile("\\bid=\"([^\"]+)\""));
        Set<String> targets = matches(guide, Pattern.compile("\\bhref=\"#([^\"]+)\""));

        assertTrue(ids.containsAll(targets), () -> {
            Set<String> missing = targets.stream()
                    .filter(target -> !ids.contains(target))
                    .collect(Collectors.toSet());
            return "Missing guide anchors: " + missing;
        });
    }

    @Test
    void everyLocalGuideAssetExists() throws IOException {
        Path docsDirectory = Path.of(System.getProperty("user.dir"), "docs")
                .toAbsolutePath()
                .normalize();
        Path guideFile = docsDirectory.resolve(GUIDE_REDIRECT_TARGET);
        String guide = Files.readString(guideFile);

        Set<String> references = new HashSet<>(matches(
                guide,
                Pattern.compile("\\b(?:href|src)=\"([^\"]+)\"")
        ));
        String guideScript = Files.readString(
                guideFile.getParent().resolve("interactive_guide.js")
        );
        references.addAll(matches(
                guideScript,
                Pattern.compile(
                        "[\"'](\\.\\.?/[^\"']+\\.(?:gif|jpe?g|png|svg|webp))[\"']",
                        Pattern.CASE_INSENSITIVE
                )
        ));
        for (String reference : references) {
            URI referenceUri = URI.create(reference);
            if (reference.startsWith("#") || referenceUri.isAbsolute()) {
                continue;
            }

            Path resource = guideFile.getParent().resolve(reference).normalize();
            assertTrue(
                    resource.startsWith(docsDirectory),
                    () -> "Guide reference escapes the published docs directory: " + reference
            );
            assertTrue(
                    Files.isRegularFile(resource),
                    () -> "Missing local guide resource: " + reference
            );
        }
    }

    private static Set<String> matches(String source, Pattern pattern) {
        return pattern.matcher(source)
                .results()
                .map(result -> result.group(1))
                .collect(Collectors.toSet());
    }
}
