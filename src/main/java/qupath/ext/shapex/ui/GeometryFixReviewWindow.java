package qupath.ext.shapex.ui;

import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import qupath.ext.shapex.ShapeXResources;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reviews automatic geometry changes and collects per-annotation manual resolutions.
 */
final class GeometryFixReviewWindow {

    private static final double DEFAULT_PREVIEW_WIDTH = 620.0;
    private static final double DEFAULT_PREVIEW_HEIGHT = 500.0;
    private static final double PREVIEW_CONTENT_FRACTION = 0.88;
    private static final int MAX_IMAGE_CACHE_ENTRIES = 24;

    private enum ReviewMode {
        AUTOMATIC,
        MANUAL
    }

    private interface ReviewEntry {
        String label();
        ImageData<BufferedImage> imageData();
        ROI originalRoi();
        Geometry originalGeometry();
    }

    /**
     * Immutable description of one automatically corrected annotation.
     */
    record Item(
            String label,
            ImageData<BufferedImage> imageData,
            ROI originalRoi,
            Geometry originalGeometry,
            Geometry correctedGeometry,
            Geometry removedGeometry,
            Geometry filledGeometry,
            int removedComponentCount,
            int filledHoleCount
    ) implements ReviewEntry {
        Item {
            label = label == null || label.isBlank()
                    ? ShapeXResources.get("annotation.default_name")
                    : label;
            removedComponentCount = Math.max(0, removedComponentCount);
            filledHoleCount = Math.max(0, filledHoleCount);
        }

        boolean hasFix() {
            return removedComponentCount > 0
                    || filledHoleCount > 0
                    || hasArea(removedGeometry)
                    || hasArea(filledGeometry);
        }
    }

    /**
     * Resolution choices available while manually reviewing geometry issues.
     */
    enum ManualResolution {
        MERGE_COMPONENTS,
        KEEP_COMPONENTS,
        SKIP_ANNOTATION
    }

    /**
     * One annotation that needs a manual geometry decision.
     *
     * <p>The supplied result geometries must be the exact geometries that the caller will
     * apply for the corresponding choice. This keeps the preview faithful to the export.</p>
     */
    record ManualItem(
            String key,
            String label,
            ImageData<BufferedImage> imageData,
            ROI originalRoi,
            Geometry originalGeometry,
            Geometry mergedGeometry,
            List<Geometry> componentGeometries,
            boolean hasDisconnectedComponents,
            boolean hasHoles
    ) implements ReviewEntry {
        ManualItem {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("A manual geometry-review item requires a stable key");
            }
            label = label == null || label.isBlank()
                    ? ShapeXResources.get("annotation.default_name")
                    : label;
            componentGeometries = componentGeometries == null
                    ? List.of()
                    : componentGeometries.stream()
                            .filter(geometry -> geometry != null && !geometry.isEmpty())
                            .toList();
        }
    }

    /**
     * The confirmed resolution for one manually reviewed annotation.
     */
    record ManualDecision(String key, ManualResolution resolution) {}

    private record PreviewTransform(
            Envelope envelope,
            double scale,
            double offsetX,
            double offsetY
    ) {}

    private record ImageCacheKey(
            String serverPath,
            int x,
            int y,
            int width,
            int height,
            int z,
            int t,
            long downsampleKey
    ) {}

    private record ImageRequest(
            ImageCacheKey cacheKey,
            ImageServer<?> server,
            int x,
            int y,
            int width,
            int height,
            int z,
            int t,
            double downsample,
            double displayX,
            double displayY,
            double displayWidth,
            double displayHeight
    ) {}

    private final Node ownerNode;
    private final Node styleSourceNode;
    private final ReviewMode reviewMode;
    private final List<ReviewEntry> items;
    private final Stage stage = new Stage();
    private final ListView<ReviewEntry> itemList = new ListView<>();
    private final StackPane previewContainer = new StackPane();
    private final Pane previewCanvas = new Pane();
    private final Label previewMessage = new Label();
    private final Label previewStatus = new Label();
    private final CheckBox showImageCheckBox = new CheckBox(ShapeXResources.get("tessellator.show_image"));
    private final ToggleGroup manualResolutionGroup = new ToggleGroup();
    private final Map<String, ManualResolution> manualResolutions = new LinkedHashMap<>();
    private final PauseTransition previewResizeDelay = new PauseTransition(Duration.millis(80));
    private final Map<ImageCacheKey, Image> imageCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ImageCacheKey, Image> eldest) {
            return size() > MAX_IMAGE_CACHE_ENTRIES;
        }
    };

    private Task<BufferedImage> activeImageTask;
    private RadioButton manualRepairButton;
    private RadioButton manualKeepButton;
    private long imageRequestSerial;
    private boolean updatingManualControls;
    private boolean manualAccepted;

    GeometryFixReviewWindow(Node ownerNode, List<Item> items) {
        this(ownerNode, ownerNode, items);
    }

    GeometryFixReviewWindow(Node ownerNode, Node styleSourceNode, List<Item> items) {
        this(ownerNode, styleSourceNode, items, ReviewMode.AUTOMATIC);
    }

    private GeometryFixReviewWindow(
            Node ownerNode,
            Node styleSourceNode,
            List<? extends ReviewEntry> items,
            ReviewMode reviewMode
    ) {
        this.ownerNode = ownerNode;
        this.styleSourceNode = styleSourceNode;
        this.reviewMode = reviewMode;
        if (items == null) {
            this.items = List.of();
        } else if (reviewMode == ReviewMode.AUTOMATIC) {
            this.items = items.stream()
                    .filter(Item.class::isInstance)
                    .map(Item.class::cast)
                    .filter(Item::hasFix)
                    .map(ReviewEntry.class::cast)
                    .toList();
        } else {
            this.items = items.stream()
                    .filter(ManualItem.class::isInstance)
                    .map(ManualItem.class::cast)
                    .map(ReviewEntry.class::cast)
                    .toList();
            initializeManualResolutions();
        }
        initializeStage();
    }

    static void show(Node ownerNode, List<Item> items) {
        new GeometryFixReviewWindow(ownerNode, items).showAndWait();
    }

    static void show(Node ownerNode, Node styleSourceNode, List<Item> items) {
        new GeometryFixReviewWindow(ownerNode, styleSourceNode, items).showAndWait();
    }

    static Optional<List<ManualDecision>> showManual(
            Node ownerNode,
            Node styleSourceNode,
            List<ManualItem> items
    ) {
        GeometryFixReviewWindow window = new GeometryFixReviewWindow(
                ownerNode,
                styleSourceNode,
                items,
                ReviewMode.MANUAL
        );
        return window.showManualAndWait();
    }

    void showAndWait() {
        stage.showAndWait();
    }

    private Optional<List<ManualDecision>> showManualAndWait() {
        stage.showAndWait();
        if (!manualAccepted) {
            return Optional.empty();
        }
        return Optional.of(items.stream()
                .filter(ManualItem.class::isInstance)
                .map(ManualItem.class::cast)
                .map(item -> new ManualDecision(
                        item.key(),
                        manualResolutions.getOrDefault(item.key(), ManualResolution.MERGE_COMPONENTS)
                ))
                .toList());
    }

    private void initializeManualResolutions() {
        for (ReviewEntry entry : items) {
            if (!(entry instanceof ManualItem item)) {
                continue;
            }
            if (manualResolutions.putIfAbsent(item.key(), ManualResolution.MERGE_COMPONENTS) != null) {
                throw new IllegalArgumentException(
                        "Duplicate manual geometry-review key: " + item.key()
                );
            }
        }
    }

    private void initializeStage() {
        stage.setTitle(reviewMode == ReviewMode.MANUAL
                ? ShapeXResources.get("geometry.manual.title")
                : "");
        Window owner = ControllerDialogs.resolveOwner(ownerNode);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        } else {
            stage.initModality(Modality.APPLICATION_MODAL);
        }

        itemList.getItems().setAll(items);
        itemList.setMinWidth(220.0);
        itemList.setPrefWidth(285.0);
        itemList.getStyleClass().add("geometry-fix-review-list");
        itemList.setPlaceholder(new Label(ShapeXResources.get(
                reviewMode == ReviewMode.MANUAL
                        ? "geometry.manual.annotations.none"
                        : "geometry.fix_review.annotations.none"
        )));
        itemList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(ReviewEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
                getStyleClass().removeAll(
                        "geometry-manual-list-cell",
                        "geometry-manual-decision-merge",
                        "geometry-manual-decision-keep",
                        "geometry-manual-decision-skip"
                );
                if (!empty && item instanceof ManualItem manualItem) {
                    getStyleClass().addAll(
                            "geometry-manual-list-cell",
                            decisionStyleClass(manualResolutions.getOrDefault(
                                    manualItem.key(),
                                    ManualResolution.MERGE_COMPONENTS
                            ))
                    );
                }
            }
        });

        Label listHeading = new Label(ShapeXResources.get(
                reviewMode == ReviewMode.MANUAL
                        ? "geometry.manual.annotations"
                        : "geometry.fix_review.annotations"
        ));
        listHeading.getStyleClass().add("geometry-fix-review-heading");
        VBox listPanel = new VBox(10.0, listHeading, itemList);
        listPanel.getStyleClass().add("geometry-fix-review-list-panel");
        VBox.setVgrow(itemList, Priority.ALWAYS);

        previewCanvas.setMinSize(0.0, 0.0);
        previewCanvas.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        previewCanvas.setMouseTransparent(true);
        previewContainer.getChildren().setAll(previewCanvas, previewMessage, previewStatus);
        previewContainer.setMinSize(360.0, 300.0);
        previewContainer.setPrefSize(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT);
        previewContainer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        previewContainer.getStyleClass().add("geometry-fix-preview-pane");
        previewMessage.getStyleClass().add("geometry-fix-preview-message");
        previewStatus.getStyleClass().addAll(
                "geometry-manual-preview-status",
                "geometry-manual-preview-status-skip"
        );
        previewStatus.setVisible(false);
        previewStatus.setMouseTransparent(true);
        StackPane.setAlignment(previewStatus, Pos.TOP_RIGHT);
        StackPane.setMargin(previewStatus, new Insets(12.0));

        HBox previewControls = reviewMode == ReviewMode.MANUAL
                ? createManualPreviewControls()
                : createAutomaticPreviewControls();

        VBox previewPanel = new VBox(10.0, previewContainer, previewControls);
        VBox.setVgrow(previewContainer, Priority.ALWAYS);
        previewPanel.setMinWidth(400.0);

        SplitPane splitPane = new SplitPane(listPanel, previewPanel);
        splitPane.setDividerPositions(0.29);
        splitPane.getStyleClass().add("geometry-fix-review-split");

        Label message = new Label(ShapeXResources.get(
                reviewMode == ReviewMode.MANUAL
                        ? "geometry.manual.message"
                        : "geometry.fix_review.message"
        ));
        message.setWrapText(true);
        message.getStyleClass().add("geometry-fix-review-message");

        HBox buttons = createButtons();
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getStyleClass().add("geometry-fix-review-buttons");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16.0));
        root.setTop(new VBox(12.0, message, new Separator()));
        root.setCenter(splitPane);
        root.setBottom(buttons);
        BorderPane.setMargin(splitPane, new Insets(12.0, 0.0, 12.0, 0.0));
        root.getStyleClass().add("geometry-fix-review-root");

        Scene scene = new Scene(root, 980.0, 650.0);
        inheritShapeXStyles(scene, root);
        stage.setScene(scene);
        stage.setMinWidth(820.0);
        stage.setMinHeight(560.0);

        itemList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldItem, newItem) -> {
                    syncManualControls(newItem);
                    renderSelectedItem();
                }
        );
        showImageCheckBox.selectedProperty().addListener(
                (observable, oldValue, newValue) -> renderSelectedItem()
        );
        previewResizeDelay.setOnFinished(event -> renderSelectedItem());
        previewContainer.widthProperty().addListener((observable, oldValue, newValue) -> schedulePreviewRender());
        previewContainer.heightProperty().addListener((observable, oldValue, newValue) -> schedulePreviewRender());
        stage.setOnHidden(event -> cancelImageRequest());

        if (!this.items.isEmpty()) {
            itemList.getSelectionModel().selectFirst();
        } else {
            showPreviewMessage(ShapeXResources.get(
                    reviewMode == ReviewMode.MANUAL
                            ? "geometry.manual.annotations.none"
                            : "geometry.fix_review.annotations.none"
            ));
        }
    }

    private HBox createAutomaticPreviewControls() {
        HBox legend = new HBox(
                18.0,
                createLegendEntry(
                        "geometry-fix-legend-filled",
                        ShapeXResources.get("geometry.fix_review.legend.filled")
                ),
                createLegendEntry(
                        "geometry-fix-legend-removed",
                        ShapeXResources.get("geometry.fix_review.legend.removed")
                )
        );
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.getStyleClass().add("geometry-fix-legend");

        Region controlsSpacer = new Region();
        HBox.setHgrow(controlsSpacer, Priority.ALWAYS);
        HBox controls = new HBox(12.0, legend, controlsSpacer, showImageCheckBox);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("geometry-fix-preview-controls");
        return controls;
    }

    private HBox createManualPreviewControls() {
        manualRepairButton = createManualResolutionButton(
                ShapeXResources.get("geometry.option.merge_components"),
                ManualResolution.MERGE_COMPONENTS,
                "geometry-manual-option-merge"
        );
        manualKeepButton = createManualResolutionButton(
                ShapeXResources.get("geometry.option.keep_components"),
                ManualResolution.KEEP_COMPONENTS,
                "geometry-manual-option-keep"
        );
        RadioButton skipButton = createManualResolutionButton(
                ShapeXResources.get("geometry.option.skip_annotation"),
                ManualResolution.SKIP_ANNOTATION,
                "geometry-manual-option-skip"
        );

        manualResolutionGroup.selectedToggleProperty().addListener(
                (observable, oldToggle, newToggle) -> {
                    if (updatingManualControls
                            || !(newToggle instanceof RadioButton selected)
                            || !(selected.getUserData() instanceof ManualResolution resolution)
                            || !(itemList.getSelectionModel().getSelectedItem() instanceof ManualItem item)) {
                        return;
                    }
                    manualResolutions.put(item.key(), resolution);
                    itemList.refresh();
                    renderSelectedItem();
                }
        );

        Region controlsSpacer = new Region();
        HBox.setHgrow(controlsSpacer, Priority.ALWAYS);
        HBox controls = new HBox(
                8.0,
                manualRepairButton,
                manualKeepButton,
                skipButton,
                controlsSpacer,
                showImageCheckBox
        );
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().addAll(
                "geometry-fix-preview-controls",
                "geometry-manual-preview-controls"
        );
        return controls;
    }

    private RadioButton createManualResolutionButton(
            String text,
            ManualResolution resolution,
            String resolutionStyleClass
    ) {
        RadioButton button = new RadioButton(text);
        button.setToggleGroup(manualResolutionGroup);
        button.setUserData(resolution);
        button.getStyleClass().addAll("geometry-manual-option", resolutionStyleClass);
        return button;
    }

    private HBox createButtons() {
        if (reviewMode == ReviewMode.MANUAL) {
            Button cancelButton = new Button(ShapeXResources.get("geometry.manual.cancel"));
            cancelButton.setCancelButton(true);
            cancelButton.setOnAction(event -> {
                manualAccepted = false;
                stage.close();
            });

            Button applyButton = new Button(ShapeXResources.get("geometry.manual.apply"));
            applyButton.setDefaultButton(true);
            applyButton.setOnAction(event -> {
                manualAccepted = true;
                stage.close();
            });
            return new HBox(8.0, cancelButton, applyButton);
        }

        Button okButton = new Button(ShapeXResources.get("geometry.fix_review.ok"));
        okButton.setDefaultButton(true);
        okButton.setOnAction(event -> stage.close());
        return new HBox(okButton);
    }

    private void syncManualControls(ReviewEntry entry) {
        if (reviewMode != ReviewMode.MANUAL || !(entry instanceof ManualItem item)) {
            return;
        }

        ManualResolution resolution = manualResolutions.getOrDefault(
                item.key(),
                ManualResolution.MERGE_COMPONENTS
        );
        if (item.hasHoles() && resolution == ManualResolution.KEEP_COMPONENTS) {
            resolution = ManualResolution.MERGE_COMPONENTS;
            manualResolutions.put(item.key(), resolution);
            itemList.refresh();
        }

        manualRepairButton.setText(manualRepairLabel(item));
        manualKeepButton.setManaged(!item.hasHoles());
        manualKeepButton.setVisible(!item.hasHoles());

        updatingManualControls = true;
        try {
            ManualResolution selectedResolution = resolution;
            manualResolutionGroup.getToggles().stream()
                    .filter(toggle -> toggle.getUserData() == selectedResolution)
                    .findFirst()
                    .ifPresent(manualResolutionGroup::selectToggle);
        } finally {
            updatingManualControls = false;
        }
    }

    private String manualRepairLabel(ManualItem item) {
        if (!item.hasHoles()) {
            return ShapeXResources.get("geometry.option.merge_components");
        }
        return ShapeXResources.get(item.hasDisconnectedComponents()
                ? "geometry.option.merge_components_and_fill_holes"
                : "geometry.option.fill_holes");
    }

    private String decisionStyleClass(ManualResolution resolution) {
        return switch (resolution) {
            case MERGE_COMPONENTS -> "geometry-manual-decision-merge";
            case KEEP_COMPONENTS -> "geometry-manual-decision-keep";
            case SKIP_ANNOTATION -> "geometry-manual-decision-skip";
        };
    }

    private HBox createLegendEntry(String swatchStyleClass, String text) {
        Region swatch = new Region();
        swatch.getStyleClass().addAll("geometry-fix-legend-swatch", swatchStyleClass);
        Label label = new Label(text);
        HBox entry = new HBox(6.0, swatch, label);
        entry.setAlignment(Pos.CENTER_LEFT);
        return entry;
    }

    private void inheritShapeXStyles(Scene scene, Parent root) {
        Scene ownerScene = styleSourceNode == null ? null : styleSourceNode.getScene();
        if (ownerScene != null) {
            scene.getStylesheets().setAll(ownerScene.getStylesheets());
            Parent ownerRoot = ownerScene.getRoot();
            if (ownerRoot != null && ownerRoot.getStyle() != null) {
                root.setStyle(ownerRoot.getStyle());
            }
        }

        if (scene.getStylesheets().isEmpty()) {
            var stylesheet = GeometryFixReviewWindow.class.getResource("/qupath/ext/shapex/ui/shapex.css");
            if (stylesheet != null) {
                scene.getStylesheets().add(stylesheet.toExternalForm());
            }
        }
    }

    private void schedulePreviewRender() {
        previewResizeDelay.playFromStart();
    }

    private void renderSelectedItem() {
        cancelImageRequest();
        previewCanvas.getChildren().clear();
        previewMessage.setVisible(false);
        previewStatus.setVisible(false);

        ReviewEntry item = itemList.getSelectionModel().getSelectedItem();
        if (item == null) {
            showPreviewMessage(ShapeXResources.get("geometry.fix_review.preview.empty"));
            return;
        }

        double width = previewContainer.getWidth();
        double height = previewContainer.getHeight();
        if (width <= 1.0 || height <= 1.0) {
            width = DEFAULT_PREVIEW_WIDTH;
            height = DEFAULT_PREVIEW_HEIGHT;
        }
        previewCanvas.resize(width, height);
        previewCanvas.setClip(createViewportClip(width, height));

        PreviewTransform transform = createPreviewTransform(item, width, height);
        if (transform == null) {
            showPreviewMessage(ShapeXResources.get("geometry.fix_review.preview.empty"));
            return;
        }

        boolean showImage = showImageCheckBox.isSelected();
        Group imageLayer = new Group();
        imageLayer.setManaged(false);
        if (showImage) {
            List<Geometry> maskGeometries = item instanceof Item automaticItem
                    ? java.util.Arrays.asList(
                            automaticItem.originalGeometry(),
                            automaticItem.filledGeometry()
                    )
                    : java.util.Collections.singletonList(item.originalGeometry());
            Path imageMask = createGeometryPath(maskGeometries, transform, null);
            if (imageMask != null) {
                imageMask.setFill(Color.WHITE);
                imageMask.setStroke(null);
                imageLayer.setClip(imageMask);
            }
            previewCanvas.getChildren().add(imageLayer);
        }

        if (item instanceof Item automaticItem) {
            renderAutomaticItem(automaticItem, transform, showImage, imageLayer);
        } else if (item instanceof ManualItem manualItem) {
            renderManualItem(manualItem, transform, showImage, imageLayer);
        }
    }

    private void renderAutomaticItem(
            Item item,
            PreviewTransform transform,
            boolean showImage,
            Group imageLayer
    ) {
        Path originalPath = createGeometryPath(
                java.util.Collections.singletonList(item.originalGeometry()),
                transform,
                "geometry-fix-original"
        );
        Path removedPath = createGeometryPath(
                java.util.Collections.singletonList(item.removedGeometry()),
                transform,
                "geometry-fix-removed"
        );
        Path filledPath = createGeometryPath(
                java.util.Collections.singletonList(item.filledGeometry()),
                transform,
                "geometry-fix-filled"
        );

        addIfPresent(previewCanvas, originalPath);
        addIfPresent(previewCanvas, removedPath);
        addIfPresent(previewCanvas, filledPath);

        if (originalPath == null && removedPath == null && filledPath == null) {
            showPreviewMessage(ShapeXResources.get("geometry.fix_review.preview.empty"));
            return;
        }

        if (showImage) {
            requestImage(item, transform, imageLayer, originalPath);
        }
    }

    private void renderManualItem(
            ManualItem item,
            PreviewTransform transform,
            boolean showImage,
            Group imageLayer
    ) {
        ManualResolution resolution = manualResolutions.getOrDefault(
                item.key(),
                ManualResolution.MERGE_COMPONENTS
        );
        Path originalPath = createGeometryPath(
                java.util.Collections.singletonList(item.originalGeometry()),
                transform,
                "geometry-manual-original"
        );

        Path resultPath = switch (resolution) {
            case MERGE_COMPONENTS -> createGeometryPath(
                    java.util.Collections.singletonList(item.mergedGeometry()),
                    transform,
                    "geometry-manual-preview-merge"
            );
            case KEEP_COMPONENTS -> createGeometryPath(
                    item.componentGeometries(),
                    transform,
                    "geometry-manual-preview-keep"
            );
            case SKIP_ANNOTATION -> createGeometryPath(
                    java.util.Collections.singletonList(item.originalGeometry()),
                    transform,
                    "geometry-manual-preview-skip"
            );
        };

        addIfPresent(previewCanvas, originalPath);
        addIfPresent(previewCanvas, resultPath);
        if (originalPath == null && resultPath == null) {
            showPreviewMessage(ShapeXResources.get("geometry.fix_review.preview.empty"));
            return;
        }

        if (resolution == ManualResolution.SKIP_ANNOTATION) {
            previewStatus.setText(ShapeXResources.get("geometry.manual.preview.skipped"));
            previewStatus.setVisible(true);
        }

        if (showImage) {
            requestImage(item, transform, imageLayer, originalPath);
        }
    }

    private Rectangle createViewportClip(double width, double height) {
        Rectangle clip = new Rectangle(Math.max(0.0, width), Math.max(0.0, height));
        clip.setArcWidth(8.0);
        clip.setArcHeight(8.0);
        return clip;
    }

    private void addIfPresent(Pane pane, Node node) {
        if (node != null) {
            pane.getChildren().add(node);
        }
    }

    private void showPreviewMessage(String text) {
        previewMessage.setText(text);
        previewMessage.setVisible(true);
        previewMessage.toFront();
    }

    private PreviewTransform createPreviewTransform(ReviewEntry item, double width, double height) {
        Envelope envelope = new Envelope();
        expandEnvelope(envelope, item.originalGeometry());
        if (item instanceof Item automaticItem) {
            expandEnvelope(envelope, automaticItem.correctedGeometry());
            expandEnvelope(envelope, automaticItem.removedGeometry());
            expandEnvelope(envelope, automaticItem.filledGeometry());
        } else if (item instanceof ManualItem manualItem) {
            expandEnvelope(envelope, manualItem.mergedGeometry());
            for (Geometry componentGeometry : manualItem.componentGeometries()) {
                expandEnvelope(envelope, componentGeometry);
            }
        }
        if (envelope.isNull() || envelope.getWidth() <= 0.0 || envelope.getHeight() <= 0.0) {
            return null;
        }

        double targetWidth = width * PREVIEW_CONTENT_FRACTION;
        double targetHeight = height * PREVIEW_CONTENT_FRACTION;
        double scale = Math.min(targetWidth / envelope.getWidth(), targetHeight / envelope.getHeight());
        if (!Double.isFinite(scale) || scale <= 0.0) {
            return null;
        }

        double offsetX = (width - envelope.getWidth() * scale) / 2.0;
        double offsetY = (height - envelope.getHeight() * scale) / 2.0;
        return new PreviewTransform(envelope, scale, offsetX, offsetY);
    }

    private void expandEnvelope(Envelope envelope, Geometry geometry) {
        Geometry prepared = prepareGeometry(geometry);
        if (prepared != null && !prepared.isEmpty()) {
            envelope.expandToInclude(prepared.getEnvelopeInternal());
        }
    }

    private Path createGeometryPath(
            List<Geometry> geometries,
            PreviewTransform transform,
            String styleClass
    ) {
        if (transform == null || geometries == null || geometries.isEmpty()) {
            return null;
        }

        Path path = new Path();
        path.setFillRule(FillRule.EVEN_ODD);
        for (Geometry geometry : geometries) {
            List<Polygon> polygons = new ArrayList<>();
            collectPolygons(prepareGeometry(geometry), polygons);
            for (Polygon polygon : polygons) {
                appendRing(path, polygon.getExteriorRing().getCoordinates(), transform);
                for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                    appendRing(path, polygon.getInteriorRingN(i).getCoordinates(), transform);
                }
            }
        }

        if (path.getElements().isEmpty()) {
            return null;
        }
        if (styleClass != null && !styleClass.isBlank()) {
            path.getStyleClass().add(styleClass);
        }
        return path;
    }

    private void appendRing(Path path, Coordinate[] coordinates, PreviewTransform transform) {
        if (coordinates == null || coordinates.length < 3) {
            return;
        }

        path.getElements().add(new MoveTo(toPreviewX(coordinates[0].x, transform),
                toPreviewY(coordinates[0].y, transform)));
        for (int i = 1; i < coordinates.length; i++) {
            path.getElements().add(new LineTo(toPreviewX(coordinates[i].x, transform),
                    toPreviewY(coordinates[i].y, transform)));
        }
        path.getElements().add(new ClosePath());
    }

    private double toPreviewX(double sourceX, PreviewTransform transform) {
        return (sourceX - transform.envelope().getMinX()) * transform.scale() + transform.offsetX();
    }

    private double toPreviewY(double sourceY, PreviewTransform transform) {
        return (sourceY - transform.envelope().getMinY()) * transform.scale() + transform.offsetY();
    }

    private void requestImage(
            ReviewEntry item,
            PreviewTransform transform,
            Group imageLayer,
            Path originalPath
    ) {
        ImageRequest request = createImageRequest(item, transform);
        if (request == null || imageLayer.getClip() == null) {
            showPreviewMessage(ShapeXResources.get("geometry.fix_review.image.unavailable"));
            return;
        }

        Image cached = imageCache.get(request.cacheKey());
        if (cached != null) {
            applyImage(imageLayer, cached, request);
            showOriginalOverImage(originalPath);
            return;
        }

        BufferedImage viewerFallback = SlidePreviewImageReader.readViewerThumbnailRegion(
                request.server(),
                request.x(),
                request.y(),
                request.width(),
                request.height(),
                request.z(),
                request.t()
        );
        Image fallbackImage = viewerFallback == null
                ? null
                : SwingFXUtils.toFXImage(viewerFallback, null);
        if (fallbackImage != null) {
            applyImage(imageLayer, fallbackImage, request);
            showOriginalOverImage(originalPath);
            previewMessage.setVisible(false);
        }
        long requestSerial = ++imageRequestSerial;
        Task<BufferedImage> task = new Task<>() {
            @Override
            protected BufferedImage call() {
                BufferedImage region = SlidePreviewImageReader.readServerRegion(
                        request.server(),
                        request.x(),
                        request.y(),
                        request.width(),
                        request.height(),
                        request.z(),
                        request.t(),
                        request.downsample()
                );
                return region;
            }
        };
        activeImageTask = task;
        task.setOnSucceeded(event -> {
            if (requestSerial != imageRequestSerial
                    || itemList.getSelectionModel().getSelectedItem() != item
                    || !showImageCheckBox.isSelected()) {
                return;
            }
            BufferedImage bufferedImage = task.getValue();
            if (bufferedImage == null || bufferedImage.getWidth() <= 0 || bufferedImage.getHeight() <= 0) {
                if (fallbackImage != null) {
                    imageCache.put(request.cacheKey(), fallbackImage);
                    return;
                }
                showPreviewMessage(ShapeXResources.get("geometry.fix_review.image.unavailable"));
                return;
            }
            Image image = SwingFXUtils.toFXImage(bufferedImage, null);
            imageCache.put(request.cacheKey(), image);
            applyImage(imageLayer, image, request);
            showOriginalOverImage(originalPath);
            previewMessage.setVisible(false);
        });
        task.setOnFailed(event -> {
            if (requestSerial == imageRequestSerial && showImageCheckBox.isSelected()) {
                if (fallbackImage != null) {
                    imageCache.put(request.cacheKey(), fallbackImage);
                    previewMessage.setVisible(false);
                } else {
                    showPreviewMessage(ShapeXResources.get("geometry.fix_review.image.unavailable"));
                }
            }
        });

        Thread worker = new Thread(task, "shapex-geometry-fix-image-preview");
        worker.setDaemon(true);
        worker.start();
    }

    private void showOriginalOverImage(Path originalPath) {
        if (originalPath == null || !originalPath.getStyleClass().contains("geometry-fix-original")) {
            return;
        }
        originalPath.getStyleClass().remove("geometry-fix-original");
        if (!originalPath.getStyleClass().contains("geometry-fix-original-image")) {
            originalPath.getStyleClass().add("geometry-fix-original-image");
        }
    }

    private ImageRequest createImageRequest(ReviewEntry item, PreviewTransform transform) {
        ImageData<BufferedImage> imageData = item.imageData();
        if (imageData == null || imageData.getServer() == null || transform == null) {
            return null;
        }

        ImageServer<?> server = imageData.getServer();
        if (server.getWidth() <= 0 || server.getHeight() <= 0) {
            return null;
        }

        Envelope envelope = transform.envelope();
        int x0 = Math.max(0, Math.min((int) Math.floor(envelope.getMinX()), server.getWidth() - 1));
        int y0 = Math.max(0, Math.min((int) Math.floor(envelope.getMinY()), server.getHeight() - 1));
        int x1 = Math.max(x0 + 1, Math.min((int) Math.ceil(envelope.getMaxX()), server.getWidth()));
        int y1 = Math.max(y0 + 1, Math.min((int) Math.ceil(envelope.getMaxY()), server.getHeight()));
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }

        int z = item.originalRoi() == null ? 0 : item.originalRoi().getZ();
        int t = item.originalRoi() == null ? 0 : item.originalRoi().getT();
        z = Math.max(0, Math.min(z, Math.max(0, server.nZSlices() - 1)));
        t = Math.max(0, Math.min(t, Math.max(0, server.nTimepoints() - 1)));
        int requestWidth = x1 - x0;
        int requestHeight = y1 - y0;
        double downsample = Math.max(1.0, 1.0 / transform.scale());
        String serverPath = server.getPath() == null ? "" : server.getPath();
        ImageCacheKey cacheKey = new ImageCacheKey(
                serverPath,
                x0,
                y0,
                requestWidth,
                requestHeight,
                z,
                t,
                Math.round(downsample * 1000.0)
        );

        return new ImageRequest(
                cacheKey,
                server,
                x0,
                y0,
                requestWidth,
                requestHeight,
                z,
                t,
                downsample,
                toPreviewX(x0, transform),
                toPreviewY(y0, transform),
                requestWidth * transform.scale(),
                requestHeight * transform.scale()
        );
    }

    private void applyImage(Group imageLayer, Image image, ImageRequest request) {
        ImageView imageView = new ImageView(image);
        imageView.setManaged(false);
        imageView.setMouseTransparent(true);
        imageView.setSmooth(true);
        imageView.setPreserveRatio(false);
        imageView.setX(request.displayX());
        imageView.setY(request.displayY());
        imageView.setFitWidth(request.displayWidth());
        imageView.setFitHeight(request.displayHeight());
        imageLayer.getChildren().setAll(imageView);
    }

    private void cancelImageRequest() {
        imageRequestSerial++;
        if (activeImageTask != null) {
            activeImageTask.cancel();
            activeImageTask = null;
        }
    }

    private static boolean hasArea(Geometry geometry) {
        return geometry != null && !geometry.isEmpty() && geometry.getArea() > 0.0;
    }

    private static Geometry prepareGeometry(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        if (geometry.isValid()) {
            return geometry;
        }
        try {
            Geometry repaired = geometry.buffer(0.0);
            return repaired == null || repaired.isEmpty() ? geometry : repaired;
        } catch (RuntimeException e) {
            return geometry;
        }
    }

    private static void collectPolygons(Geometry geometry, List<Polygon> output) {
        if (geometry == null || geometry.isEmpty()) {
            return;
        }
        if (geometry instanceof Polygon polygon) {
            output.add(polygon);
            return;
        }
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            collectPolygons(geometry.getGeometryN(i), output);
        }
    }
}
