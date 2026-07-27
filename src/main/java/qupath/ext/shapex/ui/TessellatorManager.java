package qupath.ext.shapex.ui;

import qupath.ext.shapex.ShapeXResources;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import qupath.ext.shapex.core.AnnotationTessellator;
import qupath.ext.shapex.core.ProjectAnnotationService;
import qupath.ext.shapex.core.TransformApplier;
import qupath.ext.shapex.core.WindowFlipper;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

final class TessellatorManager {

    private static final double PREVIEW_HEX_SIDE = 16.0;
    private static final double DEFAULT_TESSELLATOR_TILE_AREA_PX2 = 10_000.0;
    private static final double MIN_TESSELLATOR_TILE_AREA_UM2 = 1.0;
    private static final double MAX_TESSELLATOR_TILE_AREA_UM2 = 1_000_000.0;
    private static final double SUGGESTED_TILE_AREA_FRACTION = 0.05;
    private static final double PREVIEW_LOCK_TILE_AREA_FRACTION = 0.00005;
    private static final double PREVIEW_BASE_AREA =
            (3.0 * Math.sqrt(3.0) / 2.0) * PREVIEW_HEX_SIDE * PREVIEW_HEX_SIDE;
    private static final double PREVIEW_CACHE_ANGLE_STEP_DEGREES = 1.0;
    private static final double PREVIEW_REBUILD_DELAY_MILLIS = 180.0;
    private static final double ANNOTATION_LIST_REFRESH_DELAY_MILLIS = 180.0;
    private static final double MAX_PREVIEW_CELL_COUNT = 20_000.0;
    private static final String DEFAULT_PREVIEW_FILL_COLOR = "-shapex-primary-color";
    private static final String DEFAULT_PREVIEW_STROKE_COLOR = "-shapex-primary-stroke-color";
    private static final org.locationtech.jts.geom.GeometryFactory PREVIEW_GEOMETRY_FACTORY =
            new org.locationtech.jts.geom.GeometryFactory();

    private final Node ownerNode;
    private final ToggleButton flipImageVerticalButton;
    private final ToggleButton flipImageHorizontalButton;
    private final javafx.scene.control.Button generateFlippedImageButton;
    private final ToggleGroup tessellatorShapeToggleGroup;
    private final RadioButton tessellatorSquareRadio;
    private final RadioButton tessellatorHexagonRadio;
    private final Pane tessellatorSlidePreviewPane;
    private final Pane tessellatorPreviewPane;
    private final ToggleGroup tessellatorPartialModeToggleGroup;
    private final RadioButton tessellatorRemovePartialRadio;
    private final RadioButton tessellatorAddPartialRadio;
    private final CheckBox tessellatorHideGridCheckBox;
    private final TextField tessellatorTileAreaField;
    private final CheckBox tessellatorShowImageCheckBox;
    private final CheckBox tessellatorAssignClassesCheckBox;
    private final ListView<PathObject> tessellatorClassListView;
    private final Consumer<Boolean> annotationWatchdogSuspender;

    private boolean updatingTessellatorTileArea = false;
    private boolean updatingTessellatorClassSelection = false;
    private String activeTessellatorAnnotationId;
    private Double lastSuggestedTileAreaUm2;
    private double tessellatorOrientationDegrees = 0.0;
    private double tessellatorGridOffsetX = 0.0;
    private double tessellatorGridOffsetY = 0.0;
    private double tessellatorDragLastX = 0.0;
    private double tessellatorDragLastY = 0.0;
    private TessellatorDragMode tessellatorDragMode = TessellatorDragMode.NONE;
    private boolean tessellatorPreviewUsingImage = false;
    private boolean tessellatorPreviewLocked = false;
    private boolean tessellationInProgress = false;
    private PathObjectHierarchy observedHierarchy;

    private final PauseTransition tessellatorPreviewRebuildDelay =
            new PauseTransition(Duration.millis(PREVIEW_REBUILD_DELAY_MILLIS));
    private final PauseTransition tessellatorAnnotationListRefreshDelay =
            new PauseTransition(Duration.millis(ANNOTATION_LIST_REFRESH_DELAY_MILLIS));
    private final PathObjectHierarchyListener hierarchyListener = event -> {
        if (event.isChanging()) {
            return;
        }
        Platform.runLater(this::scheduleTessellatorClassListRefresh);
    };
    private final Group tessellatorPreviewMaskedLayer = new Group();
    private final Group tessellatorPreviewGroup = new Group();
    private final Group tessellatorPreviewOverlayGroup = new Group();
    private final Group tessellatorPreviewOutlineGroup = new Group();
    private final Group tessellatorPreviewContentGroup = new Group();
    private final Rectangle tessellatorPreviewViewportClip = new Rectangle();
    private final Rectangle tessellatorPreviewViewportBorder = new Rectangle();
    private final Rectangle tessellatorAnnotationListClip = new Rectangle();
    private final Label tessellatorPreviewCountLabel = new Label();
    private final Label tessellatorPreviewLockedLabel =
            new Label(ShapeXResources.get("tessellator.preview.locked"));
    private final ImageView tessellatorPreviewImageView = new ImageView();
    private final ImageView tessellatorSlidePreviewImageView = new ImageView();
    private final Circle tessellatorSlidePreviewMarker = new Circle();
    private final Rotate tessellatorPreviewRotate = new Rotate();
    private final Scale tessellatorPreviewFlipScale = new Scale(1.0, 1.0, 0.0, 0.0);
    private final Group tessellatorSlidePreviewContentGroup = new Group();
    private final Scale tessellatorSlidePreviewFlipScale = new Scale(1.0, 1.0, 0.0, 0.0);
    private final List<Polygon> tessellatorPreviewCells = new ArrayList<>();
    private final Map<Integer, List<PreviewCellProjection>> tessellatorPreviewProjectionCache =
            new HashMap<>();
    private final Map<String, PreviewColorStyle> tessellatorPreviewColorStyleCache =
            new HashMap<>();

    private Shape tessellatorPreviewMaskShape;
    private PreviewGeometryIndex tessellatorPreviewGeometryIndex;
    private String tessellatorPreviewImageCacheKey;
    private Image tessellatorPreviewImageCachedImage;

    private record PreviewGeometryIndex(
            org.locationtech.jts.geom.Geometry geometry,
            org.locationtech.jts.geom.prep.PreparedGeometry preparedGeometry,
            org.locationtech.jts.geom.Envelope envelope
    ) {}

    private record PreviewCellClassification(
            boolean hasOverlap,
            boolean isPartial
    ) {}

    private record PreviewColorStyle(
            String fill,
            String stroke
    ) {}

    private record PreviewCellProjection(
            boolean hasOverlap,
            boolean isPartial,
            double[] rotatedPoints
    ) {}

    private record TessellatorSlideContext(
            ImageData<BufferedImage> imageData,
            String slideName
    ) {}

    private record TessellatorPreviewTarget(
            ImageData<BufferedImage> imageData,
            String slideName,
            PathObject pathObject
    ) {}

    private record PreviewGeometryTransform(
            org.locationtech.jts.geom.Envelope envelope,
            double scale,
            double offsetX,
            double offsetY
    ) {}

    private enum TessellatorDragMode {
        NONE,
        TRANSLATE,
        ROTATE
    }

    TessellatorManager(
            Node ownerNode,
            ToggleButton flipImageVerticalButton,
            ToggleButton flipImageHorizontalButton,
            javafx.scene.control.Button generateFlippedImageButton,
            ToggleGroup tessellatorShapeToggleGroup,
            RadioButton tessellatorTriangleRadio,
            RadioButton tessellatorSquareRadio,
            RadioButton tessellatorHexagonRadio,
            Pane tessellatorSlidePreviewPane,
            Pane tessellatorPreviewPane,
            ToggleGroup tessellatorPartialModeToggleGroup,
            RadioButton tessellatorFitShapeRadio,
            RadioButton tessellatorRemovePartialRadio,
            RadioButton tessellatorAddPartialRadio,
            CheckBox tessellatorHideGridCheckBox,
            TextField tessellatorTileAreaField,
            CheckBox tessellatorShowImageCheckBox,
            CheckBox tessellatorAssignClassesCheckBox,
            ListView<PathObject> tessellatorClassListView,
            Consumer<Boolean> annotationWatchdogSuspender
    ) {
        this.ownerNode = ownerNode;
        this.flipImageVerticalButton = flipImageVerticalButton;
        this.flipImageHorizontalButton = flipImageHorizontalButton;
        this.generateFlippedImageButton = generateFlippedImageButton;
        this.tessellatorShapeToggleGroup = tessellatorShapeToggleGroup;
        this.tessellatorSquareRadio = tessellatorSquareRadio;
        this.tessellatorHexagonRadio = tessellatorHexagonRadio;
        this.tessellatorSlidePreviewPane = tessellatorSlidePreviewPane;
        this.tessellatorPreviewPane = tessellatorPreviewPane;
        this.tessellatorPartialModeToggleGroup = tessellatorPartialModeToggleGroup;
        this.tessellatorRemovePartialRadio = tessellatorRemovePartialRadio;
        this.tessellatorAddPartialRadio = tessellatorAddPartialRadio;
        this.tessellatorHideGridCheckBox = tessellatorHideGridCheckBox;
        this.tessellatorTileAreaField = tessellatorTileAreaField;
        this.tessellatorShowImageCheckBox = tessellatorShowImageCheckBox;
        this.tessellatorAssignClassesCheckBox = tessellatorAssignClassesCheckBox;
        this.tessellatorClassListView = tessellatorClassListView;
        this.annotationWatchdogSuspender = annotationWatchdogSuspender;
    }

    /* ============================================================
     * Public API
     * ============================================================ */

    void initialize() {
        tessellatorAnnotationListRefreshDelay.setOnFinished(event -> populateTessellatorClassList());
        updateTessellatorHierarchyListener();
        setupFlipListeners();
        setupTessellatorControls();
    }

    void handleProjectChanged() {
        updateTessellatorHierarchyListener();
        populateTessellatorClassList();
        refreshTessellatorSlidePreview();
    }

    void handleImageChanged() {
        updateTessellatorHierarchyListener();
        populateTessellatorClassList();
        refreshTessellatorSlidePreview();
    }

    void handleGenerateFlippedImage() {
        boolean flipH = flipImageHorizontalButton.isSelected();
        boolean flipV = flipImageVerticalButton.isSelected();

        String unavailableStatus = getUnavailableProjectImageStatus();
        if (unavailableStatus != null) {
            ControllerDialogs.showError(ownerNode, unavailableStatus);
            return;
        }

        if (!flipH && !flipV) {
            ControllerDialogs.showError(ownerNode, ShapeXResources.get("error.flip.not.selected"));
            return;
        }

        try {
            TransformApplier.applyTransform(flipH, flipV);
            flipImageVerticalButton.setSelected(false);
            flipImageHorizontalButton.setSelected(false);
        } catch (Exception e) {
            ControllerDialogs.showError(
                    ownerNode,
                    ShapeXResources.format("error.transform.apply.failed", e.getMessage())
            );
        }
    }

    void handleTessellateAnnotations() {
        AnnotationTessellator.TilingShape shape = getSelectedTilingShape();
        Set<String> selectedAnnotationIds = getSelectedTessellatorAnnotationIds();
        if ((selectedAnnotationIds == null || selectedAnnotationIds.isEmpty())
                && activeTessellatorAnnotationId != null
                && !activeTessellatorAnnotationId.isBlank()) {
            selectedAnnotationIds = Set.of(activeTessellatorAnnotationId);
        }

        AnnotationTessellator.PartialPolygonMode partialMode = getSelectedPartialPolygonMode();
        TessellatorSlideContext slideContext = getTessellatorSlideContext();

        normalizeTessellatorTileAreaField(false);
        double tileAreaPx2 = getTileAreaPx2FromField();
        double orientationDegrees = tessellatorOrientationDegrees;
        boolean assignClasses = tessellatorAssignClassesCheckBox != null
                && tessellatorAssignClassesCheckBox.isSelected();

        try {
            Set<String> targetAnnotationIds = selectedAnnotationIds;
            String unavailableStatus = getUnavailableProjectImageStatus();
            if (unavailableStatus != null) {
                ControllerDialogs.showError(ownerNode, unavailableStatus);
                return;
            }
            if (slideContext == null || slideContext.imageData() == null) {
                ControllerDialogs.showError(ownerNode, ShapeXResources.get("error.tessellator.no_image"));
                return;
            }
            if (targetAnnotationIds.isEmpty()) {
                ControllerDialogs.showError(ownerNode, ShapeXResources.get("error.tessellator.annotation.not.selected"));
                return;
            }

            AnnotationTessellator.TessellationResult result = runTessellationWithProgress(
                    slideContext,
                    targetAnnotationIds,
                    shape,
                    partialMode,
                    tileAreaPx2,
                    orientationDegrees,
                    tessellatorGridOffsetX,
                    tessellatorGridOffsetY,
                    assignClasses
            );
            if (result == null) {
                return;
            }

            ControllerDialogs.showInfo(
                    ownerNode,
                    ShapeXResources.format(
                            "info.tessellation.completed",
                            result.sourceAnnotations(),
                            result.createdAnnotations()
                    )
            );
        } catch (Exception e) {
            ControllerDialogs.showError(
                    ownerNode,
                    ShapeXResources.format("error.tessellation.failed", e.getMessage())
            );
        }
    }

    void populateTessellatorClassList() {
        invalidateTessellatorPreviewImageCache();

        var project = QuPathGUI.getInstance().getProject();
        String previousActive = activeTessellatorAnnotationId;
        Set<String> previousSelectedIds = getSelectedTessellatorAnnotationIds();

        if (project == null) {
            activeTessellatorAnnotationId = null;
            showTessellatorListPlaceholder(ShapeXResources.get("project.none.open"));
            maybeApplySuggestedTileArea(false);
            rebuildTessellatorPreviewGrid();
            refreshTessellatorSlidePreview();
            return;
        }
        if (project.getImageList().isEmpty()) {
            activeTessellatorAnnotationId = null;
            showTessellatorListPlaceholder(ShapeXResources.get("project.images.none"));
            maybeApplySuggestedTileArea(false);
            rebuildTessellatorPreviewGrid();
            refreshTessellatorSlidePreview();
            return;
        }

        TessellatorSlideContext context = getTessellatorSlideContext();
        List<PathObject> annotations = context == null
                ? List.of()
                : ProjectAnnotationService.getTessellatorAnnotations(context.imageData());

        if (annotations.isEmpty()) {
            activeTessellatorAnnotationId = null;
            updatingTessellatorClassSelection = true;
            try {
                tessellatorClassListView.getItems().clear();
                Label placeholder = new Label(ShapeXResources.get("tessellator.annotations.none"));
                placeholder.getStyleClass().add("italic-message");
                tessellatorClassListView.setPlaceholder(placeholder);
            } finally {
                updatingTessellatorClassSelection = false;
            }
            maybeApplySuggestedTileArea(false);
            rebuildTessellatorPreviewGrid();
            refreshTessellatorSlidePreview();
            return;
        }

        updatingTessellatorClassSelection = true;
        try {
            tessellatorClassListView.getItems().setAll(annotations);
            tessellatorClassListView.setPlaceholder(new Label(""));

            var selection = tessellatorClassListView.getSelectionModel();
            selection.clearSelection();
            for (PathObject annotation : annotations) {
                String id = ProjectAnnotationService.getPathObjectId(annotation);
                if (previousSelectedIds.contains(id)) {
                    selection.select(annotation);
                }
            }

            PathObject targetActiveObject = null;
            if (previousActive != null && !previousActive.isBlank()) {
                for (PathObject annotation : annotations) {
                    if (previousActive.equals(ProjectAnnotationService.getPathObjectId(annotation))) {
                        targetActiveObject = annotation;
                        break;
                    }
                }
            }
            if (targetActiveObject == null) {
                targetActiveObject = annotations.get(0);
            }

            activeTessellatorAnnotationId = ProjectAnnotationService.getPathObjectId(targetActiveObject);
            selection.select(targetActiveObject);
            int activeIndex = annotations.indexOf(targetActiveObject);
            tessellatorClassListView.getFocusModel().focus(Math.max(0, activeIndex));
            tessellatorClassListView.scrollTo(targetActiveObject);
        } finally {
            updatingTessellatorClassSelection = false;
        }

        boolean activeChanged = previousActive == null
                ? activeTessellatorAnnotationId != null
                : !previousActive.equals(activeTessellatorAnnotationId);

        maybeApplySuggestedTileArea(activeChanged);
        rebuildTessellatorPreviewGrid();
        refreshTessellatorSlidePreview();
    }

    private void updateTessellatorHierarchyListener() {
        PathObjectHierarchy nextHierarchy = null;
        TessellatorSlideContext context = getTessellatorSlideContext();
        if (context != null && context.imageData() != null) {
            nextHierarchy = context.imageData().getHierarchy();
        }

        if (observedHierarchy == nextHierarchy) {
            return;
        }

        tessellatorAnnotationListRefreshDelay.stop();
        if (observedHierarchy != null) {
            observedHierarchy.removeListener(hierarchyListener);
        }
        observedHierarchy = nextHierarchy;
        if (observedHierarchy != null) {
            observedHierarchy.addListener(hierarchyListener);
        }
    }

    private void scheduleTessellatorClassListRefresh() {
        tessellatorAnnotationListRefreshDelay.playFromStart();
    }

    /* ============================================================
     * Control setup
     * ============================================================ */

    private void setupFlipListeners() {
        if (generateFlippedImageButton != null) {
            generateFlippedImageButton.disableProperty().bind(
                    flipImageVerticalButton.selectedProperty().not()
                            .and(flipImageHorizontalButton.selectedProperty().not())
            );
        }

        flipImageVerticalButton.selectedProperty().addListener((obs, oldValue, newValue) -> {
            WindowFlipper.flipImageVertically(newValue);
            updatePreviewFlipState();
        });
        flipImageHorizontalButton.selectedProperty().addListener((obs, oldValue, newValue) -> {
            WindowFlipper.flipImageHorizontally(newValue);
            updatePreviewFlipState();
        });

        updatePreviewFlipState();
    }

    private void setupTessellatorControls() {
        UnaryOperator<TextFormatter.Change> areaFilter = change -> {
            String next = change.getControlNewText();
            if (next.isEmpty() || next.matches("\\d{0,9}(\\.\\d{0,2})?")) {
                return change;
            }
            return null;
        };

        tessellatorTileAreaField.setTextFormatter(new TextFormatter<>(areaFilter));
        lastSuggestedTileAreaUm2 = computeSuggestedTileAreaUm2();
        tessellatorTileAreaField.setText(formatTileAreaUm2(lastSuggestedTileAreaUm2));
        updateTessellatorTileAreaFieldWidth(tessellatorTileAreaField.getText());

        tessellatorTileAreaField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (updatingTessellatorTileArea) {
                return;
            }

            updateTessellatorTileAreaFieldWidth(newValue);
            if (newValue == null || newValue.isBlank()) {
                return;
            }

            try {
                double areaUm2 = parseTileAreaUm2(newValue);
                double normalized = clampTessellatorTileAreaUm2(areaUm2);
                if (normalized < areaUm2 - 1e-6) {
                    updatingTessellatorTileArea = true;
                    try {
                        tessellatorTileAreaField.setText(formatTileAreaUm2(normalized));
                        updateTessellatorTileAreaFieldWidth(tessellatorTileAreaField.getText());
                    } finally {
                        updatingTessellatorTileArea = false;
                    }
                    scheduleTessellatorPreviewGridRebuild();
                    return;
                }
                if (areaUm2 >= MIN_TESSELLATOR_TILE_AREA_UM2) {
                    scheduleTessellatorPreviewGridRebuild();
                }
            } catch (NumberFormatException ignored) {}
        });
        tessellatorTileAreaField.setOnAction(event -> normalizeTessellatorTileAreaField());
        tessellatorTileAreaField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                normalizeTessellatorTileAreaField();
            }
        });

        if (tessellatorClassListView != null) {
            tessellatorAnnotationListClip.setArcWidth(16.0);
            tessellatorAnnotationListClip.setArcHeight(16.0);
            tessellatorAnnotationListClip.widthProperty().bind(tessellatorClassListView.widthProperty());
            tessellatorAnnotationListClip.heightProperty().bind(tessellatorClassListView.heightProperty());
            tessellatorClassListView.setClip(tessellatorAnnotationListClip);
            tessellatorClassListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            tessellatorClassListView.setCellFactory(list -> new ListCell<>() {
                @Override
                protected void updateItem(PathObject item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : ProjectAnnotationService.getTessellatorAnnotationLabel(item));
                }
            });
            tessellatorClassListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
                if (updatingTessellatorClassSelection || newValue == null) {
                    return;
                }
                setActiveTessellatorAnnotation(newValue);
            });
        }

        tessellatorShapeToggleGroup.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            updateTessellatorAngleRange();
            rebuildTessellatorPreviewGrid();
        });
        tessellatorPartialModeToggleGroup.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            refreshTessellatorPartialOverlay(tessellatorOrientationDegrees);
            refreshTessellatorPreviewCount();
        });

        if (tessellatorHideGridCheckBox != null) {
            tessellatorHideGridCheckBox.selectedProperty().addListener((obs, oldValue, newValue) ->
                    updateTessellatorGridVisibility()
            );
        }
        if (tessellatorShowImageCheckBox != null) {
            tessellatorShowImageCheckBox.setSelected(false);
            tessellatorShowImageCheckBox.selectedProperty().addListener((obs, oldValue, newValue) ->
                    scheduleTessellatorPreviewGridRebuild()
            );
        }
        if (tessellatorAssignClassesCheckBox != null) {
            tessellatorAssignClassesCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
                tessellatorPreviewColorStyleCache.clear();
                refreshTessellatorPartialOverlay(tessellatorOrientationDegrees);
            });
        }

        initializeTessellatorPreviewGrid();
        initializeTessellatorSlidePreview();
        updateTessellatorAngleRange();
    }

    private void setActiveTessellatorAnnotation(PathObject annotation) {
        if (annotation == null) {
            return;
        }

        String objectId = ProjectAnnotationService.getPathObjectId(annotation);
        if (objectId == null || objectId.isBlank()) {
            return;
        }

        boolean changed = !objectId.equals(activeTessellatorAnnotationId);
        activeTessellatorAnnotationId = objectId;

        if (tessellatorClassListView != null) {
            var selection = tessellatorClassListView.getSelectionModel();
            if (!selection.getSelectedItems().contains(annotation)) {
                selection.select(annotation);
            }
        }

        if (!changed) {
            return;
        }

        maybeApplySuggestedTileArea(true);
        rebuildTessellatorPreviewGrid();
        refreshTessellatorSlidePreview();
    }

    /* ============================================================
     * Slide/class selection
     * ============================================================ */

    private Set<String> getSelectedTessellatorAnnotationIds() {
        if (tessellatorClassListView == null) {
            return Set.of();
        }

        Set<String> ids = new java.util.HashSet<>();
        for (PathObject obj : tessellatorClassListView.getSelectionModel().getSelectedItems()) {
            String id = ProjectAnnotationService.getPathObjectId(obj);
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private TessellatorSlideContext getTessellatorSlideContext() {
        var currentImageData = QuPathGUI.getInstance().getImageData();
        if (currentImageData == null) {
            return null;
        }
        return new TessellatorSlideContext(currentImageData, ProjectAnnotationService.getCurrentSlideName());
    }

    private void showTessellatorListPlaceholder(String message) {
        if (tessellatorClassListView == null) {
            return;
        }
        tessellatorClassListView.getItems().clear();
        Label placeholder = new Label(message);
        placeholder.getStyleClass().add("italic-message");
        tessellatorClassListView.setPlaceholder(placeholder);
    }

    private String getUnavailableProjectImageStatus() {
        var gui = QuPathGUI.getInstance();
        var project = gui.getProject();
        if (project == null) {
            return ShapeXResources.get("project.none.open");
        }
        if (project.getImageList().isEmpty()) {
            return ShapeXResources.get("project.images.none");
        }
        if (gui.getImageData() == null) {
            return ShapeXResources.get("error.tessellator.no_image");
        }
        return null;
    }

    /* ============================================================
     * Slide preview
     * ============================================================ */

    private void initializeTessellatorSlidePreview() {
        if (tessellatorSlidePreviewPane == null) {
            return;
        }

        tessellatorSlidePreviewImageView.setManaged(false);
        tessellatorSlidePreviewImageView.setMouseTransparent(true);
        tessellatorSlidePreviewImageView.setPreserveRatio(true);
        tessellatorSlidePreviewImageView.setSmooth(true);
        tessellatorSlidePreviewMarker.setManaged(false);
        tessellatorSlidePreviewMarker.setMouseTransparent(true);
        tessellatorSlidePreviewMarker.setRadius(5.0);
        tessellatorSlidePreviewMarker.setStroke(Color.rgb(255, 255, 255, 0.9));
        tessellatorSlidePreviewMarker.setStrokeWidth(1.25);
        tessellatorSlidePreviewMarker.getStyleClass().add("shapex-secondary-fill");
        tessellatorSlidePreviewMarker.setVisible(false);
        tessellatorSlidePreviewContentGroup.setManaged(false);
        tessellatorSlidePreviewContentGroup.getTransforms().setAll(tessellatorSlidePreviewFlipScale);
        tessellatorSlidePreviewContentGroup.getChildren().setAll(
                tessellatorSlidePreviewImageView,
                tessellatorSlidePreviewMarker
        );
        tessellatorSlidePreviewPane.getChildren().setAll(tessellatorSlidePreviewContentGroup);
        tessellatorSlidePreviewPane.widthProperty().addListener((obs, oldValue, newValue) ->
                refreshTessellatorSlidePreview());
        tessellatorSlidePreviewPane.heightProperty().addListener((obs, oldValue, newValue) ->
                refreshTessellatorSlidePreview());
        refreshTessellatorSlidePreview();
    }

    private void refreshTessellatorSlidePreview() {
        if (tessellatorSlidePreviewPane == null) {
            return;
        }

        TessellatorSlideContext context = getTessellatorSlideContext();
        if (context == null || context.imageData() == null || context.imageData().getServer() == null) {
            tessellatorSlidePreviewImageView.setImage(null);
            tessellatorSlidePreviewMarker.setVisible(false);
            return;
        }

        BufferedImage preview = createTessellatorSlidePreview(context.imageData());
        if (preview == null) {
            tessellatorSlidePreviewImageView.setImage(null);
            tessellatorSlidePreviewMarker.setVisible(false);
            return;
        }

        double paneWidth = tessellatorSlidePreviewPane.getWidth() > 0
                ? tessellatorSlidePreviewPane.getWidth()
                : tessellatorSlidePreviewPane.getPrefWidth();
        double paneHeight = tessellatorSlidePreviewPane.getHeight() > 0
                ? tessellatorSlidePreviewPane.getHeight()
                : tessellatorSlidePreviewPane.getPrefHeight();

        double imageWidth = preview.getWidth();
        double imageHeight = preview.getHeight();
        if (paneWidth <= 0 || paneHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            tessellatorSlidePreviewImageView.setImage(null);
            tessellatorSlidePreviewMarker.setVisible(false);
            return;
        }

        double scale = Math.min((paneWidth - 20.0) / imageWidth, (paneHeight - 20.0) / imageHeight);
        scale = Math.max(0.1, scale);
        double fitWidth = imageWidth * scale;
        double fitHeight = imageHeight * scale;

        tessellatorSlidePreviewImageView.setImage(SwingFXUtils.toFXImage(preview, null));
        tessellatorSlidePreviewImageView.setFitWidth(fitWidth);
        tessellatorSlidePreviewImageView.setFitHeight(fitHeight);
        double offsetX = (paneWidth - fitWidth) / 2.0;
        double offsetY = (paneHeight - fitHeight) / 2.0;
        tessellatorSlidePreviewImageView.setX(offsetX);
        tessellatorSlidePreviewImageView.setY(offsetY);
        updateTessellatorSlidePreviewMarker(context, offsetX, offsetY, fitWidth, fitHeight);
        updatePreviewFlipState();
    }

    private void updateTessellatorSlidePreviewMarker(
            TessellatorSlideContext context,
            double offsetX,
            double offsetY,
            double fitWidth,
            double fitHeight
    ) {
        if (context == null || context.imageData() == null || context.imageData().getServer() == null) {
            tessellatorSlidePreviewMarker.setVisible(false);
            return;
        }

        TessellatorPreviewTarget previewTarget = getActiveTessellatorPreviewTarget();
        if (previewTarget == null
                || previewTarget.pathObject() == null
                || previewTarget.pathObject().getROI() == null
                || previewTarget.pathObject().getROI().getGeometry() == null
                || previewTarget.pathObject().getROI().getGeometry().isEmpty()) {
            tessellatorSlidePreviewMarker.setVisible(false);
            return;
        }

        double serverWidth = context.imageData().getServer().getWidth();
        double serverHeight = context.imageData().getServer().getHeight();
        if (serverWidth <= 0 || serverHeight <= 0 || fitWidth <= 0 || fitHeight <= 0) {
            tessellatorSlidePreviewMarker.setVisible(false);
            return;
        }

        var centroid = previewTarget.pathObject().getROI().getGeometry().getCentroid();
        if (centroid == null || centroid.isEmpty()) {
            tessellatorSlidePreviewMarker.setVisible(false);
            return;
        }

        double markerX = offsetX + (centroid.getX() / serverWidth) * fitWidth;
        double markerY = offsetY + (centroid.getY() / serverHeight) * fitHeight;
        tessellatorSlidePreviewMarker.setCenterX(markerX);
        tessellatorSlidePreviewMarker.setCenterY(markerY);
        tessellatorSlidePreviewMarker.setVisible(
                Double.isFinite(markerX)
                        && Double.isFinite(markerY)
                        && markerX >= offsetX
                        && markerX <= offsetX + fitWidth
                        && markerY >= offsetY
                        && markerY <= offsetY + fitHeight
        );
    }

    private BufferedImage createTessellatorSlidePreview(ImageData<BufferedImage> imageData) {
        if (imageData == null || imageData.getServer() == null) {
            return null;
        }

        ImageServer<?> server = imageData.getServer();

        int width = server.getWidth();
        int height = server.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        double targetWidth = tessellatorSlidePreviewPane != null && tessellatorSlidePreviewPane.getPrefWidth() > 0
                ? tessellatorSlidePreviewPane.getPrefWidth()
                : 320.0;
        double targetHeight = tessellatorSlidePreviewPane != null && tessellatorSlidePreviewPane.getPrefHeight() > 0
                ? tessellatorSlidePreviewPane.getPrefHeight()
                : 180.0;

        double downsample = Math.max(
                1.0,
                Math.max(width / Math.max(1.0, targetWidth),
                         height / Math.max(1.0, targetHeight))
        );

        try {
            RegionRequest request = RegionRequest.createInstance(
                    server.getPath(),
                    downsample,
                    0,
                    0,
                    width,
                    height
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

    /* ============================================================
     * Tile-area helpers
     * ============================================================ */

    private double parseTileAreaUm2(String text) {
        return Double.parseDouble(text.trim());
    }

    private String formatTileAreaUm2(double areaUm2) {
        if (Math.abs(areaUm2 - Math.rint(areaUm2)) < 1e-6) {
            return String.format(Locale.US, "%.0f", areaUm2);
        }
        return String.format(Locale.US, "%.2f", areaUm2);
    }

    private void updateTessellatorTileAreaFieldWidth(String text) {
        String value = text == null || text.isBlank() ? "0" : text.trim();
        Text helper = new Text(value);
        helper.setFont(tessellatorTileAreaField.getFont());

        double width = Math.ceil(helper.getLayoutBounds().getWidth()) + 6.0;
        width = Math.max(18.0, Math.min(76.0, width));

        tessellatorTileAreaField.setMinWidth(width);
        tessellatorTileAreaField.setPrefWidth(width);
        tessellatorTileAreaField.setMaxWidth(width);
    }

    private void maybeApplySuggestedTileArea(boolean force) {
        double suggested = clampTessellatorTileAreaUm2(computeSuggestedTileAreaUm2());

        boolean shouldApply = force;
        if (!shouldApply) {
            String text = tessellatorTileAreaField.getText();
            if (text == null || text.isBlank()) {
                shouldApply = true;
            } else {
                try {
                    double current = parseTileAreaUm2(text);
                    if (lastSuggestedTileAreaUm2 == null) {
                        shouldApply = true;
                    } else {
                        shouldApply = Math.abs(current - lastSuggestedTileAreaUm2) < 1e-6;
                    }
                } catch (NumberFormatException e) {
                    shouldApply = true;
                }
            }
        }

        if (shouldApply) {
            updatingTessellatorTileArea = true;
            try {
                tessellatorTileAreaField.setText(formatTileAreaUm2(suggested));
                updateTessellatorTileAreaFieldWidth(tessellatorTileAreaField.getText());
            } finally {
                updatingTessellatorTileArea = false;
            }
        }

        lastSuggestedTileAreaUm2 = suggested;
    }

    private void normalizeTessellatorTileAreaField() {
        normalizeTessellatorTileAreaField(true);
    }

    private void normalizeTessellatorTileAreaField(boolean rebuildPreview) {
        double normalized = getTileAreaUm2FromField();
        updatingTessellatorTileArea = true;
        try {
            tessellatorTileAreaField.setText(formatTileAreaUm2(normalized));
            updateTessellatorTileAreaFieldWidth(tessellatorTileAreaField.getText());
        } finally {
            updatingTessellatorTileArea = false;
        }
        if (rebuildPreview) {
            rebuildTessellatorPreviewGrid();
        }
    }

    private double getDefaultTessellatorTileAreaUm2() {
        return DEFAULT_TESSELLATOR_TILE_AREA_PX2 * getActiveTessellatorPixelAreaUm2();
    }

    private double roundSuggestedTileAreaUm2(double areaUm2) {
        if (!Double.isFinite(areaUm2) || areaUm2 <= 0.0) {
            return Math.ceil(MIN_TESSELLATOR_TILE_AREA_UM2);
        }
        return clampTessellatorTileAreaUm2(Math.ceil(areaUm2));
    }

    private double computeSuggestedTileAreaUm2() {
        org.locationtech.jts.geom.Geometry geometry = getActiveClassPreviewGeometry();
        if (geometry == null || geometry.isEmpty() || geometry.getArea() <= 0.0) {
            return roundSuggestedTileAreaUm2(getDefaultTessellatorTileAreaUm2());
        }

        TessellatorPreviewTarget previewTarget = getActiveTessellatorPreviewTarget();
        double pixelAreaUm2 = previewTarget != null
                ? getPixelAreaUm2(previewTarget.imageData())
                : getActiveTessellatorPixelAreaUm2();

        double shapeAreaUm2 = geometry.getArea() * pixelAreaUm2;
        if (!Double.isFinite(shapeAreaUm2) || shapeAreaUm2 <= 0.0) {
            return roundSuggestedTileAreaUm2(getDefaultTessellatorTileAreaUm2());
        }

        double suggested = shapeAreaUm2 * SUGGESTED_TILE_AREA_FRACTION;
        if (!Double.isFinite(suggested) || suggested <= 0.0) {
            return roundSuggestedTileAreaUm2(getDefaultTessellatorTileAreaUm2());
        }

        return roundSuggestedTileAreaUm2(suggested);
    }

    private double getTileAreaUm2FromField() {
        String text = tessellatorTileAreaField.getText();
        if (text == null || text.isBlank()) {
            return clampTessellatorTileAreaUm2(MIN_TESSELLATOR_TILE_AREA_UM2);
        }

        try {
            double parsed = parseTileAreaUm2(text);
            if (!Double.isFinite(parsed)) {
                return clampTessellatorTileAreaUm2(MIN_TESSELLATOR_TILE_AREA_UM2);
            }
            return clampTessellatorTileAreaUm2(parsed);
        } catch (NumberFormatException e) {
            return clampTessellatorTileAreaUm2(MIN_TESSELLATOR_TILE_AREA_UM2);
        }
    }

    private double clampTessellatorTileAreaUm2(double areaUm2) {
        if (!Double.isFinite(areaUm2)) {
            return MIN_TESSELLATOR_TILE_AREA_UM2;
        }
        return Math.min(
                MAX_TESSELLATOR_TILE_AREA_UM2,
                Math.max(MIN_TESSELLATOR_TILE_AREA_UM2, areaUm2)
        );
    }

    private double getTileAreaPx2FromField() {
        double areaUm2 = getTileAreaUm2FromField();
        double pixelAreaUm2 = getActiveTessellatorPixelAreaUm2();
        if (!Double.isFinite(pixelAreaUm2) || pixelAreaUm2 <= 0.0) {
            return areaUm2;
        }
        return areaUm2 / pixelAreaUm2;
    }

    private double getActiveTessellatorPixelAreaUm2() {
        TessellatorPreviewTarget previewTarget = getActiveTessellatorPreviewTarget();
        if (previewTarget != null) {
            return getPixelAreaUm2(previewTarget.imageData());
        }

        TessellatorSlideContext slideContext = getTessellatorSlideContext();
        if (slideContext != null) {
            return getPixelAreaUm2(slideContext.imageData());
        }

        return getPixelAreaUm2(QuPathGUI.getInstance().getImageData());
    }

    private double getPixelAreaUm2(ImageData<?> imageData) {
        if (imageData == null || imageData.getServer() == null) {
            return 1.0;
        }

        try {
            double px = imageData.getServer().getPixelCalibration().getPixelWidthMicrons();
            double py = imageData.getServer().getPixelCalibration().getPixelHeightMicrons();
            double pixelAreaUm2 = px * py;
            if (!Double.isFinite(pixelAreaUm2) || pixelAreaUm2 <= 0.0) {
                return 1.0;
            }
            return pixelAreaUm2;
        } catch (Exception e) {
            return 1.0;
        }
    }

    /* ============================================================
     * Preview/grid controls
     * ============================================================ */

    private double getTessellatorMaxRotationDegrees(
            AnnotationTessellator.TilingShape shape
    ) {
        return switch (shape) {
            case TRIANGLE -> 120.0;
            case SQUARE -> 90.0;
            case HEXAGON -> 60.0;
        };
    }

    private AnnotationTessellator.PartialPolygonMode getSelectedPartialPolygonMode() {
        if (tessellatorRemovePartialRadio.isSelected()) {
            return AnnotationTessellator.PartialPolygonMode.REMOVE_PARTIAL;
        }
        if (tessellatorAddPartialRadio.isSelected()) {
            return AnnotationTessellator.PartialPolygonMode.FORCE_PARTIAL;
        }
        return AnnotationTessellator.PartialPolygonMode.FIT_SHAPE;
    }

    private AnnotationTessellator.TilingShape getSelectedTilingShape() {
        if (tessellatorSquareRadio.isSelected()) {
            return AnnotationTessellator.TilingShape.SQUARE;
        }
        if (tessellatorHexagonRadio.isSelected()) {
            return AnnotationTessellator.TilingShape.HEXAGON;
        }
        return AnnotationTessellator.TilingShape.TRIANGLE;
    }

    private void updateTessellatorAngleRange() {
        AnnotationTessellator.TilingShape shape = getSelectedTilingShape();
        double maxDegrees = getTessellatorMaxRotationDegrees(shape);

        double wrapped = tessellatorOrientationDegrees % maxDegrees;
        if (wrapped < 0.0) {
            wrapped += maxDegrees;
        }

        tessellatorOrientationDegrees = wrapped;
        wrapTessellatorGridOffsetToFundamentalDomain();
        updateTessellatorPreviewRotation(wrapped, false);
    }

    private void updateTessellatorPreviewRotation(double degrees, boolean fromUserDrag) {
        tessellatorOrientationDegrees = degrees;
        tessellatorPreviewRotate.setAngle(degrees);
        if (fromUserDrag && shouldDeferPartialPreviewOverlay()) {
            showDeferredTessellatorDragPreview();
            return;
        }
        refreshTessellatorPartialOverlay(degrees);
    }

    private void initializeTessellatorPreviewGrid() {
        tessellatorPreviewRebuildDelay.setOnFinished(event -> rebuildTessellatorPreviewGridNow());

        tessellatorPreviewMaskedLayer.setManaged(false);
        tessellatorPreviewImageView.setManaged(false);
        tessellatorPreviewImageView.setMouseTransparent(true);
        tessellatorPreviewImageView.setSmooth(true);
        tessellatorPreviewGroup.setManaged(false);
        tessellatorPreviewGroup.setCache(true);
        tessellatorPreviewGroup.setCacheHint(CacheHint.SPEED);
        tessellatorPreviewGroup.getTransforms().setAll(tessellatorPreviewRotate);
        tessellatorPreviewMaskedLayer.getChildren().setAll(
                tessellatorPreviewImageView,
                tessellatorPreviewGroup
        );

        tessellatorPreviewOverlayGroup.setManaged(false);
        tessellatorPreviewOverlayGroup.setMouseTransparent(true);
        tessellatorPreviewOutlineGroup.setManaged(false);
        tessellatorPreviewOutlineGroup.setMouseTransparent(true);
        tessellatorPreviewViewportBorder.setManaged(false);
        tessellatorPreviewViewportBorder.setMouseTransparent(true);
        tessellatorPreviewViewportBorder.getStyleClass().add("tessellator-preview-viewport-border");
        tessellatorPreviewCountLabel.setManaged(false);
        tessellatorPreviewCountLabel.setMouseTransparent(true);
        tessellatorPreviewCountLabel.setVisible(false);
        tessellatorPreviewCountLabel.getStyleClass().add("tessellator-preview-count");
        tessellatorPreviewLockedLabel.setManaged(false);
        tessellatorPreviewLockedLabel.setMouseTransparent(true);
        tessellatorPreviewLockedLabel.setAlignment(Pos.CENTER);
        tessellatorPreviewLockedLabel.setVisible(false);
        tessellatorPreviewLockedLabel.getStyleClass().add("tessellator-preview-lock-text");
        tessellatorPreviewContentGroup.setManaged(false);
        tessellatorPreviewContentGroup.getTransforms().setAll(tessellatorPreviewFlipScale);
        tessellatorPreviewContentGroup.setClip(tessellatorPreviewViewportClip);
        tessellatorPreviewContentGroup.getChildren().setAll(
                tessellatorPreviewMaskedLayer,
                tessellatorPreviewOverlayGroup,
                tessellatorPreviewOutlineGroup,
                tessellatorPreviewViewportBorder,
                tessellatorPreviewLockedLabel
        );
        tessellatorPreviewPane.getChildren().setAll(
                tessellatorPreviewContentGroup,
                tessellatorPreviewCountLabel
        );

        tessellatorPreviewPane.widthProperty().addListener((obs, oldValue, newValue) ->
                scheduleTessellatorPreviewGridRebuild());
        tessellatorPreviewPane.heightProperty().addListener((obs, oldValue, newValue) ->
                scheduleTessellatorPreviewGridRebuild());
        tessellatorPreviewPane.setOnMouseMoved(event ->
                updateTessellatorPreviewCursor(toTessellatorPreviewLogicalPoint(event.getX(), event.getY())));
        tessellatorPreviewPane.setOnMouseExited(event -> {
            if (tessellatorDragMode == TessellatorDragMode.NONE) {
                tessellatorPreviewPane.setCursor(javafx.scene.Cursor.DEFAULT);
            }
        });
        tessellatorPreviewPane.setOnMousePressed(event -> {
            Point2D logicalPoint = toTessellatorPreviewLogicalPoint(event.getX(), event.getY());
            if (event.getButton() != MouseButton.PRIMARY || tessellatorPreviewMaskShape == null) {
                return;
            }
            if (!tessellatorPreviewMaskShape.contains(logicalPoint.getX(), logicalPoint.getY())) {
                return;
            }
            tessellatorDragMode = event.isControlDown()
                    ? TessellatorDragMode.ROTATE
                    : TessellatorDragMode.TRANSLATE;
            tessellatorDragLastX = logicalPoint.getX();
            tessellatorDragLastY = logicalPoint.getY();
            tessellatorPreviewPane.setCursor(javafx.scene.Cursor.HAND);
            event.consume();
        });
        tessellatorPreviewPane.setOnMouseDragged(event -> {
            Point2D logicalPoint = toTessellatorPreviewLogicalPoint(event.getX(), event.getY());
            if (tessellatorDragMode == TessellatorDragMode.NONE) {
                return;
            }
            if (tessellatorDragMode == TessellatorDragMode.ROTATE) {
                handleTessellatorRotationDrag(logicalPoint.getX(), logicalPoint.getY());
            } else {
                handleTessellatorTranslationDrag(logicalPoint.getX(), logicalPoint.getY());
            }
            event.consume();
        });
        tessellatorPreviewPane.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                boolean wasDragging = tessellatorDragMode != TessellatorDragMode.NONE;
                tessellatorDragMode = TessellatorDragMode.NONE;
                updateTessellatorPreviewCursor(toTessellatorPreviewLogicalPoint(event.getX(), event.getY()));
                if (wasDragging) {
                    refreshTessellatorPartialOverlay(tessellatorOrientationDegrees);
                    refreshTessellatorPreviewCount();
                }
            }
        });

        rebuildTessellatorPreviewGrid();
    }

    private void rebuildTessellatorPreviewGrid() {
        tessellatorPreviewRebuildDelay.stop();
        rebuildTessellatorPreviewGridNow();
    }

    private void scheduleTessellatorPreviewGridRebuild() {
        if (tessellationInProgress) {
            return;
        }
        tessellatorPreviewRebuildDelay.playFromStart();
    }

    private void rebuildTessellatorPreviewGridNow() {
        if (tessellationInProgress) {
            return;
        }

        double width = tessellatorPreviewPane.getWidth();
        double height = tessellatorPreviewPane.getHeight();

        if (width <= 1 || height <= 1) {
            width = tessellatorPreviewPane.getPrefWidth();
            height = tessellatorPreviewPane.getPrefHeight();
        }
        if (width <= 1 || height <= 1) {
            return;
        }

        updateTessellatorPreviewViewportBounds(width, height);
        updateTessellatorPreviewCountLabelLayout(width, height);

        AnnotationTessellator.TilingShape shape = getSelectedTilingShape();
        double side = getPreviewSideLength(shape);
        tessellatorPreviewLocked = shouldLockTessellatorPreview(width, height, shape, side);
        updateTessellatorPreviewLockOverlay(width, height);
        if (tessellatorPreviewLocked) {
            clearTessellatorPreviewGridContent();
            refreshTessellatorPreviewCount();
            updateTessellatorGridVisibility();
            updatePreviewFlipState();
            return;
        }

        tessellatorPreviewMaskShape = createPreviewAnnotationShape(width, height);
        tessellatorPreviewGeometryIndex = createPreviewGeometryIndex(width, height);
        tessellatorPreviewMaskedLayer.setClip(tessellatorPreviewMaskShape);
        Shape outline = createPreviewAnnotationShape(width, height);
        outline.getStyleClass().add("tessellator-preview-outline");
        tessellatorPreviewOutlineGroup.getChildren().setAll(outline);

        tessellatorPreviewGroup.getChildren().clear();
        tessellatorPreviewOverlayGroup.getChildren().clear();
        tessellatorPreviewCells.clear();
        tessellatorPreviewProjectionCache.clear();
        tessellatorPreviewColorStyleCache.clear();

        switch (shape) {
            case TRIANGLE -> populateTrianglePreview(width, height, side);
            case SQUARE -> populateSquarePreview(width, height, side);
            case HEXAGON -> populateHexagonPreview(width, height, side);
        }

        for (Node node : tessellatorPreviewGroup.getChildren()) {
            if (node instanceof Polygon polygon) {
                tessellatorPreviewCells.add(polygon);
            }
        }

        updateTessellatorPreviewImage(width, height);
        applyTessellatorPreviewCellStyles();
        wrapTessellatorGridOffsetToFundamentalDomain();
        applyTessellatorPreviewGridTranslation();
        updateTessellatorGridVisibility();

        tessellatorPreviewRotate.setPivotX(width / 2.0);
        tessellatorPreviewRotate.setPivotY(height / 2.0);
        updateTessellatorPreviewRotation(tessellatorOrientationDegrees, false);
        refreshTessellatorPreviewCount();
        updatePreviewFlipState();
    }

    private void clearTessellatorPreviewGridContent() {
        tessellatorPreviewGroup.getChildren().clear();
        tessellatorPreviewOverlayGroup.getChildren().clear();
        tessellatorPreviewOutlineGroup.getChildren().clear();
        tessellatorPreviewCells.clear();
        tessellatorPreviewProjectionCache.clear();
        tessellatorPreviewColorStyleCache.clear();
        tessellatorPreviewImageView.setImage(null);
        tessellatorPreviewImageView.setVisible(false);
        tessellatorPreviewMaskShape = null;
        tessellatorPreviewGeometryIndex = null;
        tessellatorPreviewMaskedLayer.setClip(null);
    }

    private void invalidateTessellatorPreviewImageCache() {
        tessellatorPreviewImageCacheKey = null;
        tessellatorPreviewImageCachedImage = null;
    }

    private void updateTessellatorPreviewViewportBounds(double width, double height) {
        tessellatorPreviewViewportClip.setX(0.0);
        tessellatorPreviewViewportClip.setY(0.0);
        tessellatorPreviewViewportClip.setWidth(Math.max(0.0, width));
        tessellatorPreviewViewportClip.setHeight(Math.max(0.0, height));
        tessellatorPreviewViewportClip.setArcWidth(16.0);
        tessellatorPreviewViewportClip.setArcHeight(16.0);

        tessellatorPreviewViewportBorder.setX(0.5);
        tessellatorPreviewViewportBorder.setY(0.5);
        tessellatorPreviewViewportBorder.setWidth(Math.max(0.0, width - 1.0));
        tessellatorPreviewViewportBorder.setHeight(Math.max(0.0, height - 1.0));
        tessellatorPreviewViewportBorder.setArcWidth(16.0);
        tessellatorPreviewViewportBorder.setArcHeight(16.0);
        tessellatorPreviewViewportBorder.toFront();
    }

    private void updateTessellatorPreviewCountLabelLayout(double width, double height) {
        tessellatorPreviewCountLabel.applyCss();
        double labelWidth = Math.ceil(tessellatorPreviewCountLabel.prefWidth(-1));
        double labelHeight = Math.ceil(tessellatorPreviewCountLabel.prefHeight(-1));
        tessellatorPreviewCountLabel.resizeRelocate(
                10.0,
                Math.max(10.0, height - labelHeight - 10.0),
                labelWidth,
                labelHeight
        );
        tessellatorPreviewCountLabel.toFront();
    }

    private void refreshTessellatorPreviewCount() {
        TessellatorPreviewTarget previewTarget = getActiveTessellatorPreviewTarget();
        if (previewTarget == null
                || previewTarget.pathObject() == null
                || tessellatorPreviewLocked
                || tessellatorPreviewMaskShape == null) {
            tessellatorPreviewCountLabel.setText("");
            tessellatorPreviewCountLabel.setVisible(false);
            return;
        }

        try {
            int count = estimateVisibleTessellatorPreviewCellCount(previewTarget.pathObject());
            tessellatorPreviewCountLabel.setText(Integer.toString(Math.max(0, count)));
            tessellatorPreviewCountLabel.setVisible(true);
        } catch (Exception ignored) {
            tessellatorPreviewCountLabel.setText("");
            tessellatorPreviewCountLabel.setVisible(false);
        }

        double width = getPaneRenderWidth(tessellatorPreviewPane);
        double height = getPaneRenderHeight(tessellatorPreviewPane);
        updateTessellatorPreviewCountLabelLayout(width, height);
    }

    private int estimateVisibleTessellatorPreviewCellCount(PathObject previewObject) {
        AnnotationTessellator.PartialPolygonMode mode = getSelectedPartialPolygonMode();
        if (mode == AnnotationTessellator.PartialPolygonMode.FIT_SHAPE) {
            org.locationtech.jts.geom.Geometry geometry = previewObject == null || previewObject.getROI() == null
                    ? null
                    : previewObject.getROI().getGeometry();
            double tileAreaPx2 = getTileAreaPx2FromField();
            if (geometry != null
                    && !geometry.isEmpty()
                    && geometry.getArea() > 0.0
                    && Double.isFinite(tileAreaPx2)
                    && tileAreaPx2 > 0.0) {
                return toPreviewCount(geometry.getArea() / tileAreaPx2);
            }
            return countPreviewCellsWithOverlap(getOrBuildPreviewCellProjections(tessellatorOrientationDegrees));
        }

        List<PreviewCellProjection> projections =
                getOrBuildPreviewCellProjections(tessellatorOrientationDegrees);
        if (mode == AnnotationTessellator.PartialPolygonMode.REMOVE_PARTIAL) {
            int count = 0;
            for (PreviewCellProjection projection : projections) {
                if (projection.hasOverlap() && !projection.isPartial()) {
                    count++;
                }
            }
            return count;
        }

        return countPreviewCellsWithOverlap(projections);
    }

    private int toPreviewCount(double count) {
        if (!Double.isFinite(count) || count <= 0.0) {
            return 0;
        }
        if (count >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(count);
    }

    private int countPreviewCellsWithOverlap(List<PreviewCellProjection> projections) {
        int count = 0;
        for (PreviewCellProjection projection : projections) {
            if (projection.hasOverlap()) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldLockTessellatorPreview(
            double width,
            double height,
            AnnotationTessellator.TilingShape shape,
            double side
    ) {
        if (estimatePreviewCellCount(width, height, shape, side) > MAX_PREVIEW_CELL_COUNT) {
            return true;
        }

        org.locationtech.jts.geom.Geometry geometry = getActiveClassPreviewGeometry();
        if (geometry == null || geometry.isEmpty() || geometry.getArea() <= 0.0) {
            return false;
        }

        double annotationAreaUm2 = geometry.getArea() * getActiveTessellatorPixelAreaUm2();
        if (!Double.isFinite(annotationAreaUm2) || annotationAreaUm2 <= 0.0) {
            return false;
        }

        double tileAreaUm2 = getTileAreaUm2FromField();
        if (!Double.isFinite(tileAreaUm2) || tileAreaUm2 <= 0.0) {
            return false;
        }

        return tileAreaUm2 < annotationAreaUm2 * PREVIEW_LOCK_TILE_AREA_FRACTION;
    }

    private double estimatePreviewCellCount(
            double width,
            double height,
            AnnotationTessellator.TilingShape shape,
            double side
    ) {
        if (!Double.isFinite(width)
                || !Double.isFinite(height)
                || !Double.isFinite(side)
                || width <= 0.0
                || height <= 0.0
                || side <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }

        double overscan = Math.max(width, height) * 0.9;
        return switch (shape) {
            case SQUARE -> {
                double nx = Math.ceil((width / 2.0 + overscan) / side) + 1.0;
                double ny = Math.ceil((height / 2.0 + overscan) / side) + 1.0;
                yield (2.0 * nx + 1.0) * (2.0 * ny + 1.0);
            }
            case TRIANGLE -> {
                double triHeight = side * Math.sqrt(3.0) / 2.0;
                if (!Double.isFinite(triHeight) || triHeight <= 0.0) {
                    yield Double.POSITIVE_INFINITY;
                }
                double nx = Math.ceil((width / 2.0 + overscan) / side) + 4.0;
                double ny = Math.ceil((height / 2.0 + overscan) / triHeight) + 4.0;
                yield (2.0 * nx + 1.0) * (2.0 * ny + 1.0) * 2.0;
            }
            case HEXAGON -> {
                double dx = 1.5 * side;
                double dy = Math.sqrt(3.0) * side;
                if (!Double.isFinite(dx) || !Double.isFinite(dy) || dx <= 0.0 || dy <= 0.0) {
                    yield Double.POSITIVE_INFINITY;
                }
                double nx = Math.ceil((width / 2.0 + overscan) / dx) + 2.0;
                double ny = Math.ceil((height / 2.0 + overscan) / dy) + 2.0;
                yield (2.0 * nx + 1.0) * (2.0 * ny + 1.0);
            }
        };
    }

    private void updateTessellatorPreviewLockOverlay(double width, double height) {
        tessellatorPreviewLockedLabel.setVisible(tessellatorPreviewLocked);
        if (!tessellatorPreviewLocked) {
            return;
        }

        double bannerHeight = Math.max(30.0, Math.min(42.0, height * 0.24));
        tessellatorPreviewLockedLabel.resizeRelocate(
                0.0,
                (height - bannerHeight) / 2.0,
                width,
                bannerHeight
        );
        tessellatorPreviewLockedLabel.toFront();
    }

    private void applyTessellatorPreviewGridTranslation() {
        PreviewGeometryTransform transform = getCurrentPreviewGeometryTransform();
        double previewOffsetX = transform == null ? 0.0 : tessellatorGridOffsetX * transform.scale();
        double previewOffsetY = transform == null ? 0.0 : tessellatorGridOffsetY * transform.scale();
        tessellatorPreviewGroup.setTranslateX(previewOffsetX);
        tessellatorPreviewGroup.setTranslateY(previewOffsetY);
    }

    private void updateTessellatorPreviewCursor(Point2D logicalPoint) {
        if (tessellatorPreviewMaskShape == null) {
            return;
        }
        tessellatorPreviewPane.setCursor(
                logicalPoint != null
                        && tessellatorPreviewMaskShape.contains(logicalPoint.getX(), logicalPoint.getY())
                        ? javafx.scene.Cursor.HAND
                        : javafx.scene.Cursor.DEFAULT
        );
    }

    private Point2D toTessellatorPreviewLogicalPoint(double x, double y) {
        double width = getPaneRenderWidth(tessellatorPreviewPane);
        double height = getPaneRenderHeight(tessellatorPreviewPane);
        double logicalX = isPreviewFlippedHorizontally() ? width - x : x;
        double logicalY = isPreviewFlippedVertically() ? height - y : y;
        return new Point2D(logicalX, logicalY);
    }

    private void updatePreviewFlipState() {
        updateTessellatorPreviewFlipTransform();
        updateTessellatorSlidePreviewFlipTransform();
    }

    private void updateTessellatorPreviewFlipTransform() {
        double width = getPaneRenderWidth(tessellatorPreviewPane);
        double height = getPaneRenderHeight(tessellatorPreviewPane);
        tessellatorPreviewFlipScale.setPivotX(width / 2.0);
        tessellatorPreviewFlipScale.setPivotY(height / 2.0);
        tessellatorPreviewFlipScale.setX(isPreviewFlippedHorizontally() ? -1.0 : 1.0);
        tessellatorPreviewFlipScale.setY(isPreviewFlippedVertically() ? -1.0 : 1.0);
    }

    private void updateTessellatorSlidePreviewFlipTransform() {
        double width = getPaneRenderWidth(tessellatorSlidePreviewPane);
        double height = getPaneRenderHeight(tessellatorSlidePreviewPane);
        tessellatorSlidePreviewFlipScale.setPivotX(width / 2.0);
        tessellatorSlidePreviewFlipScale.setPivotY(height / 2.0);
        tessellatorSlidePreviewFlipScale.setX(isPreviewFlippedHorizontally() ? -1.0 : 1.0);
        tessellatorSlidePreviewFlipScale.setY(isPreviewFlippedVertically() ? -1.0 : 1.0);
    }

    private boolean isPreviewFlippedHorizontally() {
        return flipImageHorizontalButton != null && flipImageHorizontalButton.isSelected();
    }

    private boolean isPreviewFlippedVertically() {
        return flipImageVerticalButton != null && flipImageVerticalButton.isSelected();
    }

    private double getPaneRenderWidth(Pane pane) {
        return pane.getWidth() > 1.0 ? pane.getWidth() : pane.getPrefWidth();
    }

    private double getPaneRenderHeight(Pane pane) {
        return pane.getHeight() > 1.0 ? pane.getHeight() : pane.getPrefHeight();
    }

    private void updateTessellatorGridVisibility() {
        boolean visible = tessellatorHideGridCheckBox == null || !tessellatorHideGridCheckBox.isSelected();
        tessellatorPreviewGroup.setVisible(visible);
        tessellatorPreviewOverlayGroup.setVisible(visible);
    }

    private void handleTessellatorTranslationDrag(double x, double y) {
        PreviewGeometryTransform transform = getCurrentPreviewGeometryTransform();
        if (transform == null || transform.scale() <= 0.0) {
            return;
        }

        double dx = x - tessellatorDragLastX;
        double dy = y - tessellatorDragLastY;
        tessellatorDragLastX = x;
        tessellatorDragLastY = y;

        tessellatorGridOffsetX += dx / transform.scale();
        tessellatorGridOffsetY += dy / transform.scale();
        wrapTessellatorGridOffsetToFundamentalDomain();
        tessellatorPreviewProjectionCache.clear();
        applyTessellatorPreviewGridTranslation();
        if (shouldDeferPartialPreviewOverlay()) {
            showDeferredTessellatorDragPreview();
        } else {
            refreshTessellatorPartialOverlay(tessellatorOrientationDegrees);
        }
    }

    private void handleTessellatorRotationDrag(double x, double y) {
        double pivotX = tessellatorPreviewRotate.getPivotX();
        double pivotY = tessellatorPreviewRotate.getPivotY();

        double previousAngle = Math.atan2(tessellatorDragLastY - pivotY, tessellatorDragLastX - pivotX);
        double currentAngle = Math.atan2(y - pivotY, x - pivotX);
        double deltaDegrees = Math.toDegrees(normalizeRadians(currentAngle - previousAngle));

        tessellatorDragLastX = x;
        tessellatorDragLastY = y;

        if (!Double.isFinite(deltaDegrees)) {
            return;
        }

        tessellatorOrientationDegrees = wrapTessellatorOrientationDegrees(
                tessellatorOrientationDegrees + deltaDegrees
        );
        wrapTessellatorGridOffsetToFundamentalDomain();
        tessellatorPreviewProjectionCache.clear();
        applyTessellatorPreviewGridTranslation();
        updateTessellatorPreviewRotation(tessellatorOrientationDegrees, true);
    }

    private boolean shouldDeferPartialPreviewOverlay() {
        return tessellatorDragMode != TessellatorDragMode.NONE
                && getSelectedPartialPolygonMode() != AnnotationTessellator.PartialPolygonMode.FIT_SHAPE;
    }

    private void showDeferredTessellatorDragPreview() {
        tessellatorPreviewOverlayGroup.getChildren().clear();
        for (Polygon cell : tessellatorPreviewCells) {
            cell.setVisible(true);
        }
        applyDefaultTessellatorPreviewCellStyles();
    }

    private double normalizeRadians(double radians) {
        while (radians <= -Math.PI) {
            radians += Math.PI * 2.0;
        }
        while (radians > Math.PI) {
            radians -= Math.PI * 2.0;
        }
        return radians;
    }

    private double wrapTessellatorOrientationDegrees(double degrees) {
        double maxDegrees = getTessellatorMaxRotationDegrees(getSelectedTilingShape());
        double wrapped = degrees % maxDegrees;
        if (wrapped < 0.0) {
            wrapped += maxDegrees;
        }
        return wrapped;
    }

    private void wrapTessellatorGridOffsetToFundamentalDomain() {
        double[][] basis = getTessellatorLatticeBasisVectors();
        double b1x = basis[0][0];
        double b1y = basis[0][1];
        double b2x = basis[1][0];
        double b2y = basis[1][1];

        double determinant = b1x * b2y - b1y * b2x;
        if (Math.abs(determinant) < 1e-9) {
            return;
        }

        double a = (tessellatorGridOffsetX * b2y - tessellatorGridOffsetY * b2x) / determinant;
        double b = (b1x * tessellatorGridOffsetY - b1y * tessellatorGridOffsetX) / determinant;

        a -= Math.floor(a + 0.5);
        b -= Math.floor(b + 0.5);

        tessellatorGridOffsetX = a * b1x + b * b2x;
        tessellatorGridOffsetY = a * b1y + b * b2y;
    }

    private double[][] getTessellatorLatticeBasisVectors() {
        AnnotationTessellator.TilingShape shape = getSelectedTilingShape();
        double side = getSourceSideLength(shape);
        double radians = Math.toRadians(tessellatorOrientationDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        double[][] basis = switch (shape) {
            case SQUARE -> new double[][]{{side, 0.0}, {0.0, side}};
            case HEXAGON -> {
                double dy = Math.sqrt(3.0) * side;
                yield new double[][]{{1.5 * side, dy / 2.0}, {0.0, dy}};
            }
            case TRIANGLE -> {
                double half = side / 2.0;
                double height = Math.sqrt(3.0) * half;
                yield new double[][]{{side, 0.0}, {half, height}};
            }
        };

        return new double[][]{
                {
                        basis[0][0] * cos - basis[0][1] * sin,
                        basis[0][0] * sin + basis[0][1] * cos
                },
                {
                        basis[1][0] * cos - basis[1][1] * sin,
                        basis[1][0] * sin + basis[1][1] * cos
                }
        };
    }

    private double getSourceSideLength(AnnotationTessellator.TilingShape shape) {
        double tileAreaPx2 = getTileAreaPx2FromField();
        double area = Math.max(1e-3, tileAreaPx2);
        return switch (shape) {
            case TRIANGLE -> Math.sqrt((4.0 * area) / Math.sqrt(3.0));
            case SQUARE -> Math.sqrt(area);
            case HEXAGON -> Math.sqrt((2.0 * area) / (3.0 * Math.sqrt(3.0)));
        };
    }

    private void updateTessellatorPreviewImage(double previewWidth, double previewHeight) {
        boolean imageRequested = tessellatorShowImageCheckBox != null
                && tessellatorShowImageCheckBox.isSelected();

        boolean imageApplied = false;
        if (imageRequested) {
            imageApplied = tryShowTessellatorPreviewImage(previewWidth, previewHeight);
        }

        tessellatorPreviewUsingImage = imageApplied;

        if (!imageApplied) {
            tessellatorPreviewImageView.setImage(null);
            tessellatorPreviewImageView.setVisible(false);
        }
    }

    private boolean tryShowTessellatorPreviewImage(double previewWidth, double previewHeight) {
        TessellatorPreviewTarget previewTarget = getActiveTessellatorPreviewTarget();
        if (previewTarget == null || previewTarget.pathObject() == null) {
            return false;
        }

        PathObject previewObject = previewTarget.pathObject();
        var imageData = previewTarget.imageData();
        if (imageData == null || imageData.getServer() == null) {
            return false;
        }

        ImageServer<?> server = imageData.getServer();
        org.locationtech.jts.geom.Geometry geometry = previewObject.getROI().getGeometry();
        if (geometry == null || geometry.isEmpty()) {
            return false;
        }

        org.locationtech.jts.geom.Envelope envelope = geometry.getEnvelopeInternal();
        double envWidth = envelope.getWidth();
        double envHeight = envelope.getHeight();
        if (envWidth <= 0.0 || envHeight <= 0.0) {
            return false;
        }

        double targetWidth = previewWidth * 0.88;
        double targetHeight = previewHeight * 0.88;
        double scale = Math.min(targetWidth / envWidth, targetHeight / envHeight);
        if (!Double.isFinite(scale) || scale <= 0.0) {
            return false;
        }

        double offsetX = (previewWidth - envWidth * scale) / 2.0;
        double offsetY = (previewHeight - envHeight * scale) / 2.0;
        double displayWidth = envWidth * scale;
        double displayHeight = envHeight * scale;

        int requestX = (int) Math.floor(envelope.getMinX());
        int requestY = (int) Math.floor(envelope.getMinY());
        int requestMaxX = (int) Math.ceil(envelope.getMaxX());
        int requestMaxY = (int) Math.ceil(envelope.getMaxY());

        requestX = Math.max(0, Math.min(requestX, server.getWidth() - 1));
        requestY = Math.max(0, Math.min(requestY, server.getHeight() - 1));
        requestMaxX = Math.max(requestX + 1, Math.min(requestMaxX, server.getWidth()));
        requestMaxY = Math.max(requestY + 1, Math.min(requestMaxY, server.getHeight()));

        int requestWidth = requestMaxX - requestX;
        int requestHeight = requestMaxY - requestY;
        if (requestWidth <= 0 || requestHeight <= 0) {
            return false;
        }

        double downsampleX = requestWidth / Math.max(1.0, displayWidth);
        double downsampleY = requestHeight / Math.max(1.0, displayHeight);
        double downsample = Math.max(1.0, Math.max(downsampleX, downsampleY));

        String cacheKey = buildTessellatorPreviewImageCacheKey(
                server,
                previewObject,
                requestX,
                requestY,
                requestWidth,
                requestHeight,
                downsample,
                displayWidth,
                displayHeight
        );
        if (cacheKey.equals(tessellatorPreviewImageCacheKey)
                && tessellatorPreviewImageCachedImage != null) {
            applyTessellatorPreviewImage(
                    tessellatorPreviewImageCachedImage,
                    offsetX,
                    offsetY,
                    displayWidth,
                    displayHeight
            );
            return true;
        }

        BufferedImage bufferedImage = readTessellatorPreviewImage(
                server,
                previewObject,
                requestX,
                requestY,
                requestWidth,
                requestHeight,
                downsample
        );
        if (bufferedImage == null) {
            return false;
        }

        Image fxImage = SwingFXUtils.toFXImage(bufferedImage, null);
        tessellatorPreviewImageCacheKey = cacheKey;
        tessellatorPreviewImageCachedImage = fxImage;
        applyTessellatorPreviewImage(fxImage, offsetX, offsetY, displayWidth, displayHeight);
        return true;
    }

    private String buildTessellatorPreviewImageCacheKey(
            ImageServer<?> server,
            PathObject previewObject,
            int requestX,
            int requestY,
            int requestWidth,
            int requestHeight,
            double downsample,
            double displayWidth,
            double displayHeight
    ) {
        String objectId = ProjectAnnotationService.getPathObjectId(previewObject);
        String serverPath = server == null || server.getPath() == null ? "" : server.getPath();
        int z = previewObject == null || previewObject.getROI() == null ? 0 : previewObject.getROI().getZ();
        int t = previewObject == null || previewObject.getROI() == null ? 0 : previewObject.getROI().getT();
        return serverPath
                + '|'
                + objectId
                + '|'
                + requestX
                + '|'
                + requestY
                + '|'
                + requestWidth
                + '|'
                + requestHeight
                + '|'
                + z
                + '|'
                + t
                + '|'
                + Math.round(downsample * 1000.0)
                + '|'
                + Math.round(displayWidth)
                + '|'
                + Math.round(displayHeight);
    }

    private void applyTessellatorPreviewImage(
            Image image,
            double offsetX,
            double offsetY,
            double displayWidth,
            double displayHeight
    ) {
        tessellatorPreviewImageView.setImage(image);
        tessellatorPreviewImageView.setX(offsetX);
        tessellatorPreviewImageView.setY(offsetY);
        tessellatorPreviewImageView.setFitWidth(displayWidth);
        tessellatorPreviewImageView.setFitHeight(displayHeight);
        tessellatorPreviewImageView.setVisible(true);
    }

    private BufferedImage readTessellatorPreviewImage(
            ImageServer<?> server,
            PathObject previewObject,
            int requestX,
            int requestY,
            int requestWidth,
            int requestHeight,
            double downsample
    ) {
        int z = previewObject.getROI().getZ();
        int t = previewObject.getROI().getT();
        z = Math.max(0, Math.min(z, Math.max(0, server.nZSlices() - 1)));
        t = Math.max(0, Math.min(t, Math.max(0, server.nTimepoints() - 1)));

        return SlidePreviewImageReader.readRegionOrViewerThumbnail(
                server,
                requestX,
                requestY,
                requestWidth,
                requestHeight,
                z,
                t,
                downsample
        );
    }

    private Shape createPreviewAnnotationShape(double width, double height) {
        org.locationtech.jts.geom.Geometry classGeometry =
                prepareGeometryForPreview(getActiveClassPreviewGeometry());
        if (classGeometry != null) {
            Shape classShape = createPreviewShapeFromGeometry(classGeometry, width, height);
            if (classShape != null) {
                return classShape;
            }
        }
        return createDefaultPreviewAnnotationShape(width, height);
    }

    private org.locationtech.jts.geom.Geometry getActiveClassPreviewGeometry() {
        TessellatorPreviewTarget previewTarget = getActiveTessellatorPreviewTarget();
        if (previewTarget == null || previewTarget.pathObject() == null || previewTarget.pathObject().getROI() == null) {
            return null;
        }
        return previewTarget.pathObject().getROI().getGeometry();
    }

    private TessellatorPreviewTarget getActiveTessellatorPreviewTarget() {
        if (activeTessellatorAnnotationId == null || activeTessellatorAnnotationId.isBlank()) {
            return null;
        }

        TessellatorSlideContext context = getTessellatorSlideContext();
        if (context == null || context.imageData() == null) {
            return null;
        }

        PathObject object = context.imageData().getHierarchy().getAnnotationObjects().stream()
                .filter(obj -> obj.getROI() != null && obj.getROI().isArea())
                .filter(obj -> activeTessellatorAnnotationId.equals(ProjectAnnotationService.getPathObjectId(obj)))
                .findFirst()
                .orElse(null);

        if (object == null) {
            return null;
        }

        return new TessellatorPreviewTarget(context.imageData(), context.slideName(), object);
    }

    private Shape createPreviewShapeFromGeometry(
            org.locationtech.jts.geom.Geometry sourceGeometry,
            double width,
            double height
    ) {
        org.locationtech.jts.geom.Geometry geometry = prepareGeometryForPreview(sourceGeometry);
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }

        List<org.locationtech.jts.geom.Polygon> polygons = new ArrayList<>();
        collectPreviewPolygons(geometry, polygons);
        if (polygons.isEmpty()) {
            return null;
        }

        PreviewGeometryTransform transform = createPreviewGeometryTransform(geometry, width, height);
        if (transform == null) {
            return null;
        }

        javafx.scene.shape.Path path = new javafx.scene.shape.Path();
        path.setFillRule(FillRule.EVEN_ODD);

        for (org.locationtech.jts.geom.Polygon polygon : polygons) {
            appendPreviewRing(
                    path,
                    polygon.getExteriorRing().getCoordinates(),
                    transform.envelope(),
                    transform.scale(),
                    transform.offsetX(),
                    transform.offsetY()
            );
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                appendPreviewRing(
                        path,
                        polygon.getInteriorRingN(i).getCoordinates(),
                        transform.envelope(),
                        transform.scale(),
                        transform.offsetX(),
                        transform.offsetY()
                );
            }
        }

        if (path.getElements().isEmpty()) {
            return null;
        }

        path.setFill(Color.WHITE);
        path.setStroke(null);
        return path;
    }

    private PreviewGeometryIndex createPreviewGeometryIndex(double width, double height) {
        org.locationtech.jts.geom.Geometry sourceGeometry =
                prepareGeometryForPreview(getActiveClassPreviewGeometry());
        if (sourceGeometry == null || sourceGeometry.isEmpty()) {
            return null;
        }

        PreviewGeometryTransform transform = createPreviewGeometryTransform(sourceGeometry, width, height);
        if (transform == null) {
            return null;
        }

        org.locationtech.jts.geom.Geometry previewGeometry =
                createPreviewJtsGeometryFromSource(sourceGeometry, transform);
        previewGeometry = prepareGeometryForPreview(previewGeometry);
        if (previewGeometry == null || previewGeometry.isEmpty()) {
            return null;
        }

        return new PreviewGeometryIndex(
                previewGeometry,
                org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(previewGeometry),
                previewGeometry.getEnvelopeInternal()
        );
    }

    private org.locationtech.jts.geom.Geometry createPreviewJtsGeometryFromSource(
            org.locationtech.jts.geom.Geometry sourceGeometry,
            PreviewGeometryTransform transform
    ) {
        List<org.locationtech.jts.geom.Polygon> polygons = new ArrayList<>();
        collectPreviewPolygons(sourceGeometry, polygons);
        if (polygons.isEmpty()) {
            return null;
        }

        List<org.locationtech.jts.geom.Geometry> previewPolygons = new ArrayList<>(polygons.size());
        for (org.locationtech.jts.geom.Polygon polygon : polygons) {
            org.locationtech.jts.geom.LinearRing shell =
                    createPreviewLinearRing(polygon.getExteriorRing().getCoordinates(), transform);
            if (shell == null) {
                continue;
            }

            List<org.locationtech.jts.geom.LinearRing> holes = new ArrayList<>(polygon.getNumInteriorRing());
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                org.locationtech.jts.geom.LinearRing hole =
                        createPreviewLinearRing(polygon.getInteriorRingN(i).getCoordinates(), transform);
                if (hole != null) {
                    holes.add(hole);
                }
            }

            try {
                org.locationtech.jts.geom.Polygon previewPolygon = PREVIEW_GEOMETRY_FACTORY.createPolygon(
                        shell,
                        holes.toArray(org.locationtech.jts.geom.LinearRing[]::new)
                );
                if (!previewPolygon.isEmpty() && previewPolygon.getArea() > 1e-6) {
                    previewPolygons.add(previewPolygon);
                }
            } catch (IllegalArgumentException ignored) {}
        }

        if (previewPolygons.isEmpty()) {
            return null;
        }
        if (previewPolygons.size() == 1) {
            return previewPolygons.get(0);
        }

        return PREVIEW_GEOMETRY_FACTORY.createGeometryCollection(
                previewPolygons.toArray(org.locationtech.jts.geom.Geometry[]::new)
        );
    }

    private org.locationtech.jts.geom.LinearRing createPreviewLinearRing(
            org.locationtech.jts.geom.Coordinate[] coordinates,
            PreviewGeometryTransform transform
    ) {
        if (coordinates == null || coordinates.length < 3 || transform == null) {
            return null;
        }

        boolean closed = coordinates[0].equals2D(coordinates[coordinates.length - 1]);
        int outputLength = closed ? coordinates.length : coordinates.length + 1;
        if (outputLength < 4) {
            return null;
        }

        org.locationtech.jts.geom.Coordinate[] transformed =
                new org.locationtech.jts.geom.Coordinate[outputLength];
        int coordinateCount = closed ? coordinates.length : coordinates.length;
        for (int i = 0; i < coordinateCount; i++) {
            transformed[i] = transformPreviewCoordinate(coordinates[i], transform);
        }
        if (!closed) {
            transformed[outputLength - 1] = new org.locationtech.jts.geom.Coordinate(transformed[0]);
        } else {
            transformed[outputLength - 1] = new org.locationtech.jts.geom.Coordinate(transformed[0]);
        }

        try {
            return PREVIEW_GEOMETRY_FACTORY.createLinearRing(transformed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private org.locationtech.jts.geom.Coordinate transformPreviewCoordinate(
            org.locationtech.jts.geom.Coordinate coordinate,
            PreviewGeometryTransform transform
    ) {
        return new org.locationtech.jts.geom.Coordinate(
                (coordinate.x - transform.envelope().getMinX()) * transform.scale() + transform.offsetX(),
                (coordinate.y - transform.envelope().getMinY()) * transform.scale() + transform.offsetY()
        );
    }

    private PreviewGeometryTransform createPreviewGeometryTransform(
            org.locationtech.jts.geom.Geometry sourceGeometry,
            double width,
            double height
    ) {
        if (sourceGeometry == null || sourceGeometry.isEmpty() || width <= 0.0 || height <= 0.0) {
            return null;
        }

        org.locationtech.jts.geom.Envelope envelope = sourceGeometry.getEnvelopeInternal();
        double envWidth = envelope.getWidth();
        double envHeight = envelope.getHeight();
        if (envWidth <= 0.0 || envHeight <= 0.0) {
            return null;
        }

        double targetWidth = width * 0.88;
        double targetHeight = height * 0.88;
        double scale = Math.min(targetWidth / envWidth, targetHeight / envHeight);
        if (!Double.isFinite(scale) || scale <= 0.0) {
            return null;
        }

        double offsetX = (width - envWidth * scale) / 2.0;
        double offsetY = (height - envHeight * scale) / 2.0;
        return new PreviewGeometryTransform(envelope, scale, offsetX, offsetY);
    }

    private PreviewGeometryTransform getCurrentPreviewGeometryTransform() {
        org.locationtech.jts.geom.Geometry geometry =
                prepareGeometryForPreview(getActiveClassPreviewGeometry());
        if (geometry == null || tessellatorPreviewPane == null) {
            return null;
        }

        double width = tessellatorPreviewPane.getWidth() > 1.0
                ? tessellatorPreviewPane.getWidth()
                : tessellatorPreviewPane.getPrefWidth();
        double height = tessellatorPreviewPane.getHeight() > 1.0
                ? tessellatorPreviewPane.getHeight()
                : tessellatorPreviewPane.getPrefHeight();
        return createPreviewGeometryTransform(geometry, width, height);
    }

    private org.locationtech.jts.geom.Geometry prepareGeometryForPreview(
            org.locationtech.jts.geom.Geometry geometry
    ) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        if (geometry.isValid()) {
            return geometry;
        }

        try {
            org.locationtech.jts.geom.Geometry repaired = geometry.buffer(0);
            if (repaired != null && !repaired.isEmpty()) {
                return repaired;
            }
        } catch (Exception ignored) {}

        return geometry;
    }

    private void collectPreviewPolygons(
            org.locationtech.jts.geom.Geometry geometry,
            List<org.locationtech.jts.geom.Polygon> out
    ) {
        if (geometry == null || geometry.isEmpty()) {
            return;
        }
        if (geometry instanceof org.locationtech.jts.geom.Polygon polygon) {
            out.add(polygon);
            return;
        }
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            collectPreviewPolygons(geometry.getGeometryN(i), out);
        }
    }

    private void appendPreviewRing(
            javafx.scene.shape.Path path,
            org.locationtech.jts.geom.Coordinate[] coordinates,
            org.locationtech.jts.geom.Envelope envelope,
            double scale,
            double offsetX,
            double offsetY
    ) {
        if (coordinates == null || coordinates.length < 4) {
            return;
        }

        path.getElements().add(new MoveTo(
                (coordinates[0].x - envelope.getMinX()) * scale + offsetX,
                (coordinates[0].y - envelope.getMinY()) * scale + offsetY
        ));

        for (int i = 1; i < coordinates.length; i++) {
            path.getElements().add(new LineTo(
                    (coordinates[i].x - envelope.getMinX()) * scale + offsetX,
                    (coordinates[i].y - envelope.getMinY()) * scale + offsetY
            ));
        }
        path.getElements().add(new ClosePath());
    }

    private javafx.scene.shape.Path createDefaultPreviewAnnotationShape(double width, double height) {
        double cx = width / 2.0;
        double cy = height / 2.0;
        double shapeHeight = height * 0.94;
        double shapeWidth = shapeHeight * 1.05;
        double left = cx - shapeWidth / 2.0;
        double top = cy - shapeHeight / 2.0;

        javafx.scene.shape.Path path = new javafx.scene.shape.Path();
        path.getElements().add(new MoveTo(left + 0.08 * shapeWidth, top + 0.57 * shapeHeight));
        path.getElements().add(new CubicCurveTo(
                left + 0.04 * shapeWidth, top + 0.43 * shapeHeight,
                left + 0.14 * shapeWidth, top + 0.27 * shapeHeight,
                left + 0.29 * shapeWidth, top + 0.24 * shapeHeight
        ));
        path.getElements().add(new CubicCurveTo(
                left + 0.41 * shapeWidth, top + 0.22 * shapeHeight,
                left + 0.48 * shapeWidth, top + 0.27 * shapeHeight,
                left + 0.55 * shapeWidth, top + 0.18 * shapeHeight
        ));
        path.getElements().add(new CubicCurveTo(
                left + 0.61 * shapeWidth, top + 0.10 * shapeHeight,
                left + 0.69 * shapeWidth, top + 0.05 * shapeHeight,
                left + 0.80 * shapeWidth, top + 0.08 * shapeHeight
        ));
        path.getElements().add(new CubicCurveTo(
                left + 0.93 * shapeWidth, top + 0.11 * shapeHeight,
                left + 1.00 * shapeWidth, top + 0.23 * shapeHeight,
                left + 0.97 * shapeWidth, top + 0.36 * shapeHeight
        ));
        path.getElements().add(new CubicCurveTo(
                left + 0.95 * shapeWidth, top + 0.48 * shapeHeight,
                left + 0.89 * shapeWidth, top + 0.57 * shapeHeight,
                left + 0.83 * shapeWidth, top + 0.63 * shapeHeight
        ));
        path.getElements().add(new CubicCurveTo(
                left + 0.77 * shapeWidth, top + 0.71 * shapeHeight,
                left + 0.74 * shapeWidth, top + 0.78 * shapeHeight,
                left + 0.76 * shapeWidth, top + 0.86 * shapeHeight
        ));
        path.getElements().add(new CubicCurveTo(
                left + 0.78 * shapeWidth, top + 0.94 * shapeHeight,
                left + 0.70 * shapeWidth, top + 0.99 * shapeHeight,
                left + 0.60 * shapeWidth, top + 0.98 * shapeHeight
        ));
        path.getElements().add(new CubicCurveTo(
                left + 0.46 * shapeWidth, top + 0.97 * shapeHeight,
                left + 0.31 * shapeWidth, top + 0.98 * shapeHeight,
                left + 0.21 * shapeWidth, top + 0.90 * shapeHeight
        ));
        path.getElements().add(new CubicCurveTo(
                left + 0.11 * shapeWidth, top + 0.82 * shapeHeight,
                left + 0.06 * shapeWidth, top + 0.70 * shapeHeight,
                left + 0.08 * shapeWidth, top + 0.57 * shapeHeight
        ));
        path.getElements().add(new ClosePath());
        path.setFill(Color.WHITE);
        path.setStroke(null);
        return path;
    }

    private void refreshTessellatorPartialOverlay(double degrees) {
        tessellatorPreviewOverlayGroup.getChildren().clear();

        if (tessellatorPreviewMaskShape == null) {
            return;
        }

        AnnotationTessellator.PartialPolygonMode mode = getSelectedPartialPolygonMode();
        if (mode == AnnotationTessellator.PartialPolygonMode.FIT_SHAPE) {
            for (Polygon basePolygon : tessellatorPreviewCells) {
                basePolygon.setVisible(true);
            }
            applyTessellatorPreviewCellStyles();
            return;
        }

        List<PreviewCellProjection> projections = getOrBuildPreviewCellProjections(degrees);
        String generatedName = getPreviewGeneratedTessellationAnnotationName();
        int generatedIndex = 1;
        for (int i = 0; i < tessellatorPreviewCells.size(); i++) {
            Polygon basePolygon = tessellatorPreviewCells.get(i);
            PreviewCellProjection projection = projections.get(i);

            if (!projection.hasOverlap()) {
                basePolygon.setVisible(false);
                applyTessellatorPreviewCellStyle(basePolygon);
                continue;
            }
            if (mode == AnnotationTessellator.PartialPolygonMode.REMOVE_PARTIAL
                    && projection.isPartial()) {
                basePolygon.setVisible(false);
                applyTessellatorPreviewCellStyle(basePolygon);
                continue;
            }

            basePolygon.setVisible(true);
            applyTessellatorPreviewCellStyle(basePolygon, generatedName, generatedIndex);

            if (mode != AnnotationTessellator.PartialPolygonMode.FORCE_PARTIAL || !projection.isPartial()) {
                generatedIndex++;
                continue;
            }

            Polygon rotatedPolygon = new Polygon();
            for (double value : projection.rotatedPoints()) {
                rotatedPolygon.getPoints().add(value);
            }
            rotatedPolygon.getStyleClass().add("tessellator-preview-cell");
            applyTessellatorPreviewCellStyle(rotatedPolygon, generatedName, generatedIndex);
            tessellatorPreviewOverlayGroup.getChildren().add(rotatedPolygon);
            generatedIndex++;
        }
    }

    private void applyTessellatorPreviewCellStyles() {
        String generatedName = getPreviewGeneratedTessellationAnnotationName();
        if (generatedName == null) {
            applyDefaultTessellatorPreviewCellStyles();
            return;
        }

        List<PreviewCellProjection> projections = getOrBuildPreviewCellProjections(tessellatorOrientationDegrees);
        AnnotationTessellator.PartialPolygonMode mode = getSelectedPartialPolygonMode();
        int generatedIndex = 1;
        for (int i = 0; i < tessellatorPreviewCells.size(); i++) {
            Polygon polygon = tessellatorPreviewCells.get(i);
            PreviewCellProjection projection = projections.get(i);
            boolean included = projection.hasOverlap()
                    && (mode != AnnotationTessellator.PartialPolygonMode.REMOVE_PARTIAL || !projection.isPartial());
            if (included) {
                applyTessellatorPreviewCellStyle(polygon, generatedName, generatedIndex);
                generatedIndex++;
            } else {
                applyTessellatorPreviewCellStyle(polygon);
            }
        }

    }

    private void applyTessellatorPreviewCellStyle(Polygon polygon) {
        applyTessellatorPreviewCellStyle(polygon, null, -1);
    }

    private void applyTessellatorPreviewCellStyle(
            Polygon polygon,
            String generatedName,
            int generatedIndex
    ) {
        PreviewColorStyle assignedStyle = generatedName == null || generatedIndex <= 0
                ? null
                : getPreviewGeneratedColorStyle(generatedName, generatedIndex);
        if (tessellatorPreviewUsingImage) {
            String stroke = assignedStyle == null ? DEFAULT_PREVIEW_FILL_COLOR : assignedStyle.fill();
            polygon.setStyle("-fx-fill: transparent; -fx-stroke: " + stroke + "; -fx-stroke-width: 1.8;");
        } else {
            String fill = assignedStyle == null ? DEFAULT_PREVIEW_FILL_COLOR : assignedStyle.fill();
            String stroke = assignedStyle == null ? DEFAULT_PREVIEW_STROKE_COLOR : assignedStyle.stroke();
            polygon.setStyle("-fx-fill: " + fill + "; -fx-stroke: " + stroke + "; -fx-stroke-width: 1.8;");
        }
    }

    private void applyDefaultTessellatorPreviewCellStyles() {
        tessellatorPreviewCells.forEach(this::applyTessellatorPreviewCellStyle);
    }

    private String getPreviewGeneratedTessellationAnnotationName() {
        if (tessellatorAssignClassesCheckBox == null || !tessellatorAssignClassesCheckBox.isSelected()) {
            return null;
        }
        if (tessellatorDragMode != TessellatorDragMode.NONE) {
            return null;
        }

        TessellatorPreviewTarget previewTarget = getActiveTessellatorPreviewTarget();
        if (previewTarget == null || previewTarget.pathObject() == null) {
            return null;
        }

        PathObject source = previewTarget.pathObject();
        return AnnotationTessellator.getGeneratedTessellationAnnotationName(
                source.getName(),
                source.getPathClass(),
                getSelectedTilingShape()
        );
    }

    private PreviewColorStyle getPreviewGeneratedColorStyle(String generatedName, int generatedIndex) {
        String className = generatedName + "_" + generatedIndex;
        return tessellatorPreviewColorStyleCache.computeIfAbsent(
                className,
                ignored -> {
                    PathClass pathClass = PathClass.fromString(className);
                    Integer color = pathClass == null ? null : pathClass.getColor();
                    if (color == null) {
                        return new PreviewColorStyle(DEFAULT_PREVIEW_FILL_COLOR, DEFAULT_PREVIEW_STROKE_COLOR);
                    }
                    return new PreviewColorStyle(toCssColor(color), toCssColor(scaleRgb(color, 0.58)));
                }
        );
    }

    private String toCssColor(int rgb) {
        return String.format(
                Locale.US,
                "#%02x%02x%02x",
                (rgb >> 16) & 0xff,
                (rgb >> 8) & 0xff,
                rgb & 0xff
        );
    }

    private int scaleRgb(int rgb, double scale) {
        int r = Math.max(0, Math.min(255, (int) Math.round(((rgb >> 16) & 0xff) * scale)));
        int g = Math.max(0, Math.min(255, (int) Math.round(((rgb >> 8) & 0xff) * scale)));
        int b = Math.max(0, Math.min(255, (int) Math.round((rgb & 0xff) * scale)));
        return (r << 16) | (g << 8) | b;
    }

    private List<PreviewCellProjection> getOrBuildPreviewCellProjections(double degrees) {
        int angleKey = toPreviewAngleKey(degrees);
        return tessellatorPreviewProjectionCache.computeIfAbsent(
                angleKey,
                key -> buildPreviewCellProjections(key * PREVIEW_CACHE_ANGLE_STEP_DEGREES)
        );
    }

    private List<PreviewCellProjection> buildPreviewCellProjections(double quantizedDegrees) {
        double pivotX = tessellatorPreviewRotate.getPivotX();
        double pivotY = tessellatorPreviewRotate.getPivotY();
        PreviewGeometryTransform transform = getCurrentPreviewGeometryTransform();
        double previewOffsetX = transform == null ? 0.0 : tessellatorGridOffsetX * transform.scale();
        double previewOffsetY = transform == null ? 0.0 : tessellatorGridOffsetY * transform.scale();
        List<PreviewCellProjection> out = new ArrayList<>(tessellatorPreviewCells.size());

        for (Polygon basePolygon : tessellatorPreviewCells) {
            double[] rotatedPoints = rotatePolygonPoints(
                    basePolygon.getPoints(),
                    pivotX,
                    pivotY,
                    quantizedDegrees,
                    previewOffsetX,
                    previewOffsetY
            );

            PreviewCellClassification classification = classifyPreviewCell(rotatedPoints);
            out.add(new PreviewCellProjection(
                    classification.hasOverlap(),
                    classification.isPartial(),
                    rotatedPoints
            ));
        }

        return out;
    }

    private PreviewCellClassification classifyPreviewCell(double[] rotatedPoints) {
        PreviewGeometryIndex geometryIndex = tessellatorPreviewGeometryIndex;
        if (geometryIndex == null) {
            return classifyPreviewCellWithFx(rotatedPoints);
        }

        org.locationtech.jts.geom.Envelope cellEnvelope = createPreviewCellEnvelope(rotatedPoints);
        if (cellEnvelope == null || !geometryIndex.envelope().intersects(cellEnvelope)) {
            return new PreviewCellClassification(false, false);
        }

        org.locationtech.jts.geom.Geometry cellGeometry = createPreviewCellGeometry(rotatedPoints);
        if (cellGeometry == null || cellGeometry.isEmpty()) {
            return new PreviewCellClassification(false, false);
        }

        try {
            if (geometryIndex.preparedGeometry().covers(cellGeometry)) {
                return new PreviewCellClassification(true, false);
            }
            if (geometryIndex.preparedGeometry().intersects(cellGeometry)) {
                return new PreviewCellClassification(true, true);
            }
            return new PreviewCellClassification(false, false);
        } catch (Exception e) {
            return classifyPreviewCellWithFx(rotatedPoints);
        }
    }

    private PreviewCellClassification classifyPreviewCellWithFx(double[] rotatedPoints) {
        Polygon rotatedPolygon = new Polygon();
        for (double value : rotatedPoints) {
            rotatedPolygon.getPoints().add(value);
        }

        Shape overlap = Shape.intersect(rotatedPolygon, tessellatorPreviewMaskShape);
        boolean hasOverlap = shapeHasArea(overlap);
        boolean isPartial = false;

        if (hasOverlap) {
            Shape outside = Shape.subtract(rotatedPolygon, tessellatorPreviewMaskShape);
            isPartial = shapeHasArea(outside);
        }

        return new PreviewCellClassification(hasOverlap, isPartial);
    }

    private org.locationtech.jts.geom.Envelope createPreviewCellEnvelope(double[] points) {
        if (points == null || points.length < 6) {
            return null;
        }

        org.locationtech.jts.geom.Envelope envelope = new org.locationtech.jts.geom.Envelope();
        for (int i = 0; i < points.length; i += 2) {
            envelope.expandToInclude(points[i], points[i + 1]);
        }
        return envelope;
    }

    private org.locationtech.jts.geom.Geometry createPreviewCellGeometry(double[] points) {
        if (points == null || points.length < 6 || points.length % 2 != 0) {
            return null;
        }

        int coordinateCount = points.length / 2;
        org.locationtech.jts.geom.Coordinate[] coordinates =
                new org.locationtech.jts.geom.Coordinate[coordinateCount + 1];
        for (int i = 0; i < coordinateCount; i++) {
            coordinates[i] = new org.locationtech.jts.geom.Coordinate(points[i * 2], points[i * 2 + 1]);
        }
        coordinates[coordinateCount] = new org.locationtech.jts.geom.Coordinate(coordinates[0]);

        try {
            return PREVIEW_GEOMETRY_FACTORY.createPolygon(coordinates);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int toPreviewAngleKey(double degrees) {
        AnnotationTessellator.TilingShape shape = getSelectedTilingShape();
        double maxDegrees = getTessellatorMaxRotationDegrees(shape);
        double normalized = degrees % maxDegrees;
        if (normalized < 0.0) {
            normalized += maxDegrees;
        }
        return (int) Math.round(normalized / PREVIEW_CACHE_ANGLE_STEP_DEGREES);
    }

    private double[] rotatePolygonPoints(
            List<Double> points,
            double pivotX,
            double pivotY,
            double degrees,
            double previewOffsetX,
            double previewOffsetY
    ) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double[] out = new double[points.size()];

        for (int i = 0; i < points.size(); i += 2) {
            double x = points.get(i);
            double y = points.get(i + 1);
            double dx = x - pivotX;
            double dy = y - pivotY;

            out[i] = pivotX + dx * cos - dy * sin;
            out[i + 1] = pivotY + dx * sin + dy * cos;
        }

        if (previewOffsetX != 0.0 || previewOffsetY != 0.0) {
            for (int i = 0; i < out.length; i += 2) {
                out[i] += previewOffsetX;
                out[i + 1] += previewOffsetY;
            }
        }

        return out;
    }

    private boolean shapeHasArea(Shape shape) {
        var bounds = shape.getBoundsInLocal();
        return bounds.getWidth() > 0.01 && bounds.getHeight() > 0.01;
    }

    private double getPreviewSideLength(AnnotationTessellator.TilingShape shape) {
        PreviewGeometryTransform transform = getCurrentPreviewGeometryTransform();
        if (transform != null && Double.isFinite(transform.scale()) && transform.scale() > 0.0) {
            return Math.max(1e-3, getSourceSideLength(shape) * transform.scale());
        }

        double tileAreaPx2 = getTileAreaPx2FromField();
        double previewArea = PREVIEW_BASE_AREA * (tileAreaPx2 / DEFAULT_TESSELLATOR_TILE_AREA_PX2);
        previewArea = Math.max(1e-3, previewArea);

        return switch (shape) {
            case HEXAGON -> Math.sqrt((2.0 * previewArea) / (3.0 * Math.sqrt(3.0)));
            case SQUARE -> Math.sqrt(previewArea);
            case TRIANGLE -> Math.sqrt((4.0 * previewArea) / Math.sqrt(3.0));
        };
    }

    private void populateSquarePreview(double width, double height, double side) {
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        double overscan = Math.max(width, height) * 0.9;

        int nx = (int) Math.ceil((width / 2.0 + overscan) / side) + 1;
        int ny = (int) Math.ceil((height / 2.0 + overscan) / side) + 1;

        for (int ix = -nx; ix <= nx; ix++) {
            for (int iy = -ny; iy <= ny; iy++) {
                double x = centerX + ix * side;
                double y = centerY + iy * side;
                tessellatorPreviewGroup.getChildren().add(createSquareCell(x, y, side));
            }
        }
    }

    private void populateTrianglePreview(double width, double height, double side) {
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        double triHeight = side * Math.sqrt(3.0) / 2.0;
        double overscan = Math.max(width, height) * 0.9;

        int nx = (int) Math.ceil((width / 2.0 + overscan) / side) + 4;
        int ny = (int) Math.ceil((height / 2.0 + overscan) / triHeight) + 4;

        double originX = centerX - side;
        double originY = centerY - (2.0 * triHeight / 3.0);

        for (int j = -ny; j <= ny; j++) {
            for (int i = -nx; i <= nx; i++) {
                double p00x = originX + i * side + j * (side / 2.0);
                double p00y = originY + j * triHeight;
                double p10x = p00x + side;
                double p10y = p00y;
                double p01x = p00x + side / 2.0;
                double p01y = p00y + triHeight;
                double p11x = p00x + 1.5 * side;
                double p11y = p00y + triHeight;

                tessellatorPreviewGroup.getChildren().add(
                        createTriangleCell(p00x, p00y, p10x, p10y, p01x, p01y)
                );
                tessellatorPreviewGroup.getChildren().add(
                        createTriangleCell(p10x, p10y, p11x, p11y, p01x, p01y)
                );
            }
        }
    }

    private void populateHexagonPreview(double width, double height, double side) {
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        double dx = 1.5 * side;
        double dy = Math.sqrt(3.0) * side;
        double overscan = Math.max(width, height) * 0.9;

        int nx = (int) Math.ceil((width / 2.0 + overscan) / dx) + 2;
        int ny = (int) Math.ceil((height / 2.0 + overscan) / dy) + 2;

        for (int ix = -nx; ix <= nx; ix++) {
            double x = centerX + ix * dx;
            double yOffset = (Math.abs(ix) % 2 == 0) ? 0.0 : dy / 2.0;

            for (int iy = -ny; iy <= ny; iy++) {
                double y = centerY + iy * dy + yOffset;
                tessellatorPreviewGroup.getChildren().add(createRegularCell(x, y, side, 6, 0.0));
            }
        }
    }

    private Polygon createSquareCell(double centerX, double centerY, double side) {
        double half = side / 2.0;
        Polygon square = new Polygon(
                centerX - half, centerY - half,
                centerX + half, centerY - half,
                centerX + half, centerY + half,
                centerX - half, centerY + half
        );
        square.getStyleClass().add("tessellator-preview-cell");
        return square;
    }

    private Polygon createTriangleCell(
            double x1, double y1,
            double x2, double y2,
            double x3, double y3
    ) {
        Polygon triangle = new Polygon(x1, y1, x2, y2, x3, y3);
        triangle.getStyleClass().add("tessellator-preview-cell");
        return triangle;
    }

    private Polygon createRegularCell(
            double centerX,
            double centerY,
            double sideLength,
            int sides,
            double angleOffsetDegrees
    ) {
        double radius = sideLength / (2.0 * Math.sin(Math.PI / sides));
        Polygon polygon = new Polygon();

        for (int i = 0; i < sides; i++) {
            double angle = Math.toRadians(angleOffsetDegrees + (360.0 * i / sides));
            double x = centerX + radius * Math.cos(angle);
            double y = centerY + radius * Math.sin(angle);
            polygon.getPoints().addAll(x, y);
        }

        polygon.getStyleClass().add("tessellator-preview-cell");
        return polygon;
    }

    /* ============================================================
     * Tessellation execution
     * ============================================================ */

    private AnnotationTessellator.TessellationResult runTessellationWithProgress(
            TessellatorSlideContext context,
            Set<String> targetAnnotationIds,
            AnnotationTessellator.TilingShape shape,
            AnnotationTessellator.PartialPolygonMode partialMode,
            double tileAreaPx2,
            double orientationDegrees,
            double gridOffsetX,
            double gridOffsetY,
            boolean assignClasses
    ) {
        if (context == null || context.imageData() == null || targetAnnotationIds == null || targetAnnotationIds.isEmpty()) {
            return new AnnotationTessellator.TessellationResult(0, 0);
        }

        javafx.concurrent.Task<AnnotationTessellator.TessellationResult> task = new javafx.concurrent.Task<>() {
            @Override
            protected AnnotationTessellator.TessellationResult call() throws Exception {
                updateTitle(ShapeXResources.format("progress.step", 1, 3, ShapeXResources.get("progress.tessellation.preparing_slide")));
                updateProgress(-1, 1);
                updateMessage(ShapeXResources.get("progress.tessellation.preparing_slide"));

                List<PathObject> sourceAnnotations = context.imageData().getHierarchy().getAnnotationObjects()
                        .stream()
                        .filter(obj -> obj != null && obj.getROI() != null && obj.getROI().isArea())
                        .filter(obj -> targetAnnotationIds.contains(ProjectAnnotationService.getPathObjectId(obj)))
                        .toList();

                if (sourceAnnotations.isEmpty()) {
                    throw new IllegalStateException(
                            ShapeXResources.format("error.tessellator.annotations.not_found", context.slideName())
                    );
                }

                updateTitle(ShapeXResources.format("progress.step", 2, 3, ShapeXResources.get("progress.tessellation.build_polygons")));
                updateProgress(-1, 1);
                updateMessage(ShapeXResources.format("progress.tessellation.generated_polygons", 0));

                final long[] lastBuildUpdateNanos = {0L};
                final int[] lastBuildCount = {0};
                AnnotationTessellator.TessellationBuildResult build =
                        AnnotationTessellator.buildTessellation(
                                sourceAnnotations,
                                shape,
                                partialMode,
                                tileAreaPx2,
                                orientationDegrees,
                                gridOffsetX,
                                gridOffsetY,
                                assignClasses,
                                createdCount -> {
                                    long now = System.nanoTime();
                                    if (createdCount == 1
                                            || createdCount - lastBuildCount[0] >= 50
                                            || now - lastBuildUpdateNanos[0] >= 100_000_000L) {
                                        lastBuildCount[0] = createdCount;
                                        lastBuildUpdateNanos[0] = now;
                                        updateMessage(ShapeXResources.format("progress.tessellation.generated_polygons", createdCount));
                                    }
                                }
                        );

                int totalGenerated = build.generatedAnnotations().size();
                if (totalGenerated == 0) {
                    updateProgress(1, 1);
                    updateTitle(ShapeXResources.get("progress.completed"));
                    updateMessage(ShapeXResources.get("progress.tessellation.no_polygons"));
                    return new AnnotationTessellator.TessellationResult(build.sourceAnnotations(), 0);
                }

                updateTitle(ShapeXResources.format("progress.step", 3, 3, ShapeXResources.get("progress.tessellation.create_annotations")));
                updateProgress(0, totalGenerated);
                updateMessage(ShapeXResources.format("progress.tessellation.created_polygons", 0, totalGenerated));

                var hierarchy = context.imageData().getHierarchy();
                int done = 0;
                int updateStride = Math.max(1, totalGenerated / 200);
                long lastUpdateNanos = 0L;

                for (PathObject obj : build.generatedAnnotations()) {
                    hierarchy.addObject(obj, false);
                    done++;

                    long now = System.nanoTime();
                    if (done == totalGenerated
                            || done % updateStride == 0
                            || now - lastUpdateNanos >= 100_000_000L) {
                        updateProgress(done, totalGenerated);
                        updateMessage(ShapeXResources.format("progress.tessellation.created_polygons", done, totalGenerated));
                        lastUpdateNanos = now;
                    }
                }

                CountDownLatch latch = new CountDownLatch(1);
                Platform.runLater(() -> {
                    hierarchy.fireHierarchyChangedEvent(AnnotationTessellator.class);
                    latch.countDown();
                });
                latch.await();

                updateProgress(totalGenerated, totalGenerated);
                updateTitle(ShapeXResources.get("progress.completed"));
                updateMessage(ShapeXResources.get("progress.done"));
                return new AnnotationTessellator.TessellationResult(build.sourceAnnotations(), totalGenerated);
            }
        };

        ProgressBar progressBar = new ProgressBar();
        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.setPrefWidth(300);

        Label stepLabel = new Label();
        stepLabel.textProperty().bind(task.titleProperty());

        Label statusLabel = new Label();
        statusLabel.textProperty().bind(task.messageProperty());

        VBox box = new VBox(
                20,
                new Label(ShapeXResources.get("progress.tessellation.dialog.title")),
                stepLabel,
                progressBar,
                statusLabel
        );
        box.setStyle("-fx-padding: 20; -fx-alignment: center;");
        box.setPrefWidth(420);
        box.setPrefHeight(200);

        Stage dialog = new Stage();
        dialog.setTitle(ShapeXResources.get("title"));
        var owner = ControllerDialogs.resolveOwner(ownerNode);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setScene(new Scene(box));
        dialog.setResizable(false);
        dialog.sizeToScene();
        dialog.setMinWidth(400);
        dialog.setMinHeight(180);

        final AnnotationTessellator.TessellationResult[] result = {null};
        final String[] failureMessage = {null};

        task.setOnSucceeded(event -> {
            result[0] = task.getValue();
            tessellationInProgress = false;
            annotationWatchdogSuspender.accept(false);
            dialog.close();
        });
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            failureMessage[0] = ex != null ? ex.getMessage() : ShapeXResources.get("error.unknown");
            tessellationInProgress = false;
            annotationWatchdogSuspender.accept(false);
            dialog.close();
        });
        task.setOnCancelled(event -> {
            failureMessage[0] = ShapeXResources.get("status.cancelled");
            tessellationInProgress = false;
            annotationWatchdogSuspender.accept(false);
            dialog.close();
        });

        tessellatorPreviewRebuildDelay.stop();
        tessellationInProgress = true;
        annotationWatchdogSuspender.accept(true);
        Thread worker = new Thread(task, "shapex-tessellation");
        worker.setDaemon(true);
        worker.start();
        dialog.showAndWait();

        if (result[0] != null) {
            return result[0];
        }

        ControllerDialogs.showError(
                ownerNode,
                ShapeXResources.format(
                        "error.tessellation.failed",
                        failureMessage[0] != null ? failureMessage[0] : ShapeXResources.get("error.unknown")
                )
        );
        return null;
    }
}
