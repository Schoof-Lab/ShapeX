package qupath.ext.shapex.ui;

import qupath.ext.shapex.ShapeXResources;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import qupath.ext.shapex.core.CalibrationResolver;
import qupath.ext.shapex.core.MultiPlateAssignmentModel;
import qupath.ext.shapex.core.PlateAssignmentModel;
import qupath.ext.shapex.core.PlateLayoutUtils;
import qupath.ext.shapex.core.ProjectAnnotationService;
import qupath.ext.shapex.core.ShapeXAnnotationMetadata;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

final class PlateDesignManager {

    private static final int DEFAULT_ROWS = 16;
    private static final int DEFAULT_COLS = 24;
    private static final int WELLS_96_ROWS = 8;
    private static final int WELLS_96_COLS = 12;
    private static final int WELLS_384_ROWS = 16;
    private static final int WELLS_384_COLS = 24;
    private static final int WELLS_1536_ROWS = 32;
    private static final int WELLS_1536_COLS = 48;
    private static final int MAX_PLATE_DIMENSION = 99;
    private static final String SLIDE_ACTIVE_CLASS = "slide-active";
    private static final String SLIDE_ROW_CLASS = "slide-row";
    private static final String SLIDE_CALIBRATION_MARKER_CLASS = "slide-calibration-marker";
    private static final double DEFAULT_PLATE_VIEWPORT_WIDTH =
            PlateAssignmentView.preferredWidthForColumns(WELLS_384_COLS);
    private static final double DEFAULT_PLATE_VIEWPORT_HEIGHT =
            PlateAssignmentView.preferredHeightForRows(WELLS_384_ROWS);
    private static final double PLATE_DESIGNER_LEFT_MENU_WIDTH = 150.0;
    private static final double PLATE_DESIGNER_LEFT_GAP = 12.0;
    private static final double PLATE_DESIGNER_RIGHT_GAP = 0.0;
    private static final double PLATE_DESIGNER_RIGHT_MENU_WIDTH = 230.0;
    private static final double PLATE_SCROLL_CHROME_WIDTH = 24.0;
    private static final double PLATE_SCROLL_CHROME_HEIGHT = 24.0;
    // Manual footer alignment nudges. Increase LEFT to move the checkbox right,
    // increase RIGHT to move the navigation controls left, and make VERTICAL
    // more negative to move the complete footer closer to the plate.
    private static final double PLATE_CONTROLS_LEFT_NUDGE = 14.0;
    private static final double PLATE_CONTROLS_RIGHT_NUDGE = 2.0;
    private static final double PLATE_CONTROLS_VERTICAL_NUDGE = -8.0;
    // Side-menu nudges are applied relative to the visible plate viewport.
    private static final double PLATE_LEFT_MENU_HEADER_NUDGE = 0.0;
    private static final double PLATE_RIGHT_MENU_HEADER_NUDGE = 0.0;
    private static final double PLATE_SLIDE_LIST_BOTTOM_NUDGE = 0.0;

    private final Accordion settingsAccordion;
    private final ToggleGroup plateFormatToggleGroup;
    private final RadioButton customPlateRadio;
    private final RadioButton wells96PlateRadio;
    private final RadioButton wells384PlateRadio;
    private final RadioButton wells1536PlateRadio;
    private final TextField plateRows;
    private final TextField plateCols;
    private final Label wellCountLabel;
    private final CheckBox allAnnotationsCheckBox;
    private final CheckBox poolClassCheckBox;
    private final ScrollPane plateScrollPane;
    private final GridPane plateDesignerGrid;
    private final HBox plateControlsRow;
    private final VBox plateFormatMenu;
    private final VBox plateLegendMenu;
    private final TextField seedField;
    private final TextField negativeControlsField;
    private final CheckBox randomizeCheckBox;
    private final CheckBox customLayoutCheckBox;
    private final CheckBox mapImageToPlateCheckBox;
    private final CheckBox uniquePlatesCheckBox;
    private final Node shuffleButton;
    private final Node prevPlateButton;
    private final Node nextPlateButton;
    private final Node removePlateButton;
    private final Node resetPlateCountButton;
    private final Node addPlateButton;
    private final Label plateIndexLabel;
    private final Pane legendSample;
    private final Pane legendUnused;
    private final Pane legendNegative;
    private final Pane legendExcluded;
    private final Label legendSamplesCount;
    private final Label legendUnusedCount;
    private final Label legendExcludedCount;
    private final ScrollPane slideScrollPane;
    private final VBox slideListBox;
    private final Runnable onAnnotationContentChanged;

    private final StackPane plateViewportContainer = new StackPane();
    private final Rectangle slideListClip = new Rectangle();
    private final Set<String> persistentlyDisabledSamples = new HashSet<>();

    private CheckBox checkAllSlides;
    private boolean updatingSlideSelection = false;
    private boolean updatingPlateDimensions = false;
    private boolean committingPlateDimensions = false;
    private boolean updatingNegativeControlsField = false;
    private boolean suspendAnnotationWatchdog = false;
    private boolean highlightUnusedMode = false;
    private int lastValidRows = DEFAULT_ROWS;
    private int lastValidCols = DEFAULT_COLS;

    private LegendManager legendManager;
    private MultiPlateAssignmentModel plateSet;

    private PathObjectHierarchyListener hierarchyListener;
    private PathObjectHierarchy currentHierarchy;
    private Timeline annotationStateWatchdog;
    private long lastAnnotationStateHash = Long.MIN_VALUE;
    private CalibrationResolver.CalibrationStatus lastCalibrationStatus;

    PlateDesignManager(
            Accordion settingsAccordion,
            ToggleGroup plateFormatToggleGroup,
            RadioButton customPlateRadio,
            RadioButton wells96PlateRadio,
            RadioButton wells384PlateRadio,
            RadioButton wells1536PlateRadio,
            TextField plateRows,
            TextField plateCols,
            Label wellCountLabel,
            CheckBox allAnnotationsCheckBox,
            CheckBox poolClassCheckBox,
            ScrollPane plateScrollPane,
            GridPane plateDesignerGrid,
            HBox plateControlsRow,
            VBox plateFormatMenu,
            VBox plateLegendMenu,
            TextField seedField,
            TextField negativeControlsField,
            CheckBox randomizeCheckBox,
            CheckBox customLayoutCheckBox,
            CheckBox mapImageToPlateCheckBox,
            CheckBox uniquePlatesCheckBox,
            Node shuffleButton,
            Node prevPlateButton,
            Node nextPlateButton,
            Node removePlateButton,
            Node resetPlateCountButton,
            Node addPlateButton,
            Label plateIndexLabel,
            Pane legendSample,
            Pane legendUnused,
            Pane legendNegative,
            Pane legendExcluded,
            Label legendSamplesCount,
            Label legendUnusedCount,
            Label legendExcludedCount,
            ScrollPane slideScrollPane,
            VBox slideListBox,
            Runnable onAnnotationContentChanged
    ) {
        this.settingsAccordion = settingsAccordion;
        this.plateFormatToggleGroup = plateFormatToggleGroup;
        this.customPlateRadio = customPlateRadio;
        this.wells96PlateRadio = wells96PlateRadio;
        this.wells384PlateRadio = wells384PlateRadio;
        this.wells1536PlateRadio = wells1536PlateRadio;
        this.plateRows = plateRows;
        this.plateCols = plateCols;
        this.wellCountLabel = wellCountLabel;
        this.allAnnotationsCheckBox = allAnnotationsCheckBox;
        this.poolClassCheckBox = poolClassCheckBox;
        this.plateScrollPane = plateScrollPane;
        this.plateDesignerGrid = plateDesignerGrid;
        this.plateControlsRow = plateControlsRow;
        this.plateFormatMenu = plateFormatMenu;
        this.plateLegendMenu = plateLegendMenu;
        this.seedField = seedField;
        this.negativeControlsField = negativeControlsField;
        this.randomizeCheckBox = randomizeCheckBox;
        this.customLayoutCheckBox = customLayoutCheckBox;
        this.mapImageToPlateCheckBox = mapImageToPlateCheckBox;
        this.uniquePlatesCheckBox = uniquePlatesCheckBox;
        this.shuffleButton = shuffleButton;
        this.prevPlateButton = prevPlateButton;
        this.nextPlateButton = nextPlateButton;
        this.removePlateButton = removePlateButton;
        this.resetPlateCountButton = resetPlateCountButton;
        this.addPlateButton = addPlateButton;
        this.plateIndexLabel = plateIndexLabel;
        this.legendSample = legendSample;
        this.legendUnused = legendUnused;
        this.legendNegative = legendNegative;
        this.legendExcluded = legendExcluded;
        this.legendSamplesCount = legendSamplesCount;
        this.legendUnusedCount = legendUnusedCount;
        this.legendExcludedCount = legendExcludedCount;
        this.slideScrollPane = slideScrollPane;
        this.slideListBox = slideListBox;
        this.onAnnotationContentChanged = onAnnotationContentChanged;
    }

    /* ============================================================
     * Initialization
     * ============================================================ */

    void initialize() {
        legendManager = new LegendManager(
                legendSample,
                legendUnused,
                legendNegative,
                legendExcluded,
                legendSamplesCount,
                legendUnusedCount,
                legendExcludedCount
        );
        configureLegendInteractions();
        initializePlateViewportContainer();
        initializeSlideListCorners();
        setupPlateAssignment();
        setupPlateNavigation();
        setupIncludeAllAnnotationsToggle();
        setupPlateFormatSelector();
        setupAnnotationStateWatchdog();
        populateSlideList();
        handleImageChanged();

        int rows = parseOrDefault(plateRows.getText(), DEFAULT_ROWS);
        int cols = parseOrDefault(plateCols.getText(), DEFAULT_COLS);
        legendManager.updateCounts(plateSet, rows, cols);
    }

    private void initializeSlideListCorners() {
        if (slideScrollPane == null) {
            return;
        }
        slideListClip.setArcWidth(16.0);
        slideListClip.setArcHeight(16.0);
        slideListClip.widthProperty().bind(slideScrollPane.widthProperty());
        slideListClip.heightProperty().bind(slideScrollPane.heightProperty());
        slideScrollPane.setClip(slideListClip);
    }

    MultiPlateAssignmentModel getPlateSet() {
        return plateSet;
    }

    Set<String> getSelectedSlides() {
        Set<String> selected = new HashSet<>();
        for (ProjectImageEntry<BufferedImage> entry : getSelectedImageEntriesInListOrder()) {
            selected.add(entry.getImageName());
        }
        return selected;
    }

    boolean isIncludeAllAnnotationsEnabled() {
        return allAnnotationsCheckBox != null && allAnnotationsCheckBox.isSelected();
    }

    boolean isImageToPlateMappingEnabled() {
        return mapImageToPlateCheckBox != null && mapImageToPlateCheckBox.isSelected();
    }

    boolean isPoolClassesEnabled() {
        return poolClassCheckBox == null || poolClassCheckBox.isSelected();
    }

    boolean isUniquePlatesEnabled() {
        return uniquePlatesCheckBox != null && uniquePlatesCheckBox.isSelected();
    }

    int getPlateRows() {
        return parseOrDefault(plateRows.getText(), DEFAULT_ROWS);
    }

    int getPlateCols() {
        return parseOrDefault(plateCols.getText(), DEFAULT_COLS);
    }

    void setAnnotationWatchdogSuspended(boolean suspended) {
        suspendAnnotationWatchdog = suspended;
        if (annotationStateWatchdog == null) {
            return;
        }

        if (suspended) {
            annotationStateWatchdog.pause();
        } else {
            annotationStateWatchdog.play();
        }
    }

    void refreshAssignmentsFromAnnotations() {
        rebuildPlate();
        refreshSlideCalibrationMarkers();
        lastAnnotationStateHash = ProjectAnnotationService.computeAnnotationStateHash(
                QuPathGUI.getInstance().getImageData(),
                isIncludeAllAnnotationsEnabled()
        );
        if (onAnnotationContentChanged != null) {
            onAnnotationContentChanged.run();
        }
    }

    /* ============================================================
     * Project/image lifecycle
     * ============================================================ */

    void handleProjectChanged(Project<?> project) {
        populateSlideList();
        rebuildPlate();

        if (project == null) {
            lastAnnotationStateHash = Long.MIN_VALUE;
        } else {
            lastAnnotationStateHash = ProjectAnnotationService.computeAnnotationStateHash(
                    QuPathGUI.getInstance().getImageData(),
                    isIncludeAllAnnotationsEnabled()
            );
        }
    }

    void handleImageChanged() {
        var imageData = QuPathGUI.getInstance().getImageData();

        if (imageData == null) {
            detachAnnotationListener();
            lastCalibrationStatus = null;
            rebuildPlate();
            return;
        }

        attachAnnotationListener();
        lastAnnotationStateHash = ProjectAnnotationService.computeAnnotationStateHash(
                imageData,
                isIncludeAllAnnotationsEnabled()
        );
        lastCalibrationStatus = CalibrationResolver.checkCalibrationStatus(imageData.getHierarchy());
        refreshSlideCalibrationMarkers();
        updateSlideHighlight();

        if (getSelectedSlides().isEmpty()) {
            rebuildPlate();
        }
    }

    /* ============================================================
     * Annotation monitoring
     * ============================================================ */

    private void attachAnnotationListener() {
        var imageData = QuPathGUI.getInstance().getImageData();
        if (imageData == null) {
            return;
        }

        var hierarchy = imageData.getHierarchy();
        if (currentHierarchy != null && hierarchyListener != null) {
            currentHierarchy.removeListener(hierarchyListener);
        }

        hierarchyListener = event -> Platform.runLater(() -> {
            if (event.isChanging()) {
                return;
            }
            if (ShapeXAnnotationMetadata.isShapeXMetadataEvent(event.getSource())) {
                return;
            }
            if (suspendAnnotationWatchdog) {
                lastAnnotationStateHash = Long.MIN_VALUE;
                lastCalibrationStatus = null;
                return;
            }

            CalibrationResolver.CalibrationStatus currentCalibrationStatus = getCurrentCalibrationStatus();
            if (currentCalibrationStatus != lastCalibrationStatus) {
                lastCalibrationStatus = currentCalibrationStatus;
                refreshSlideCalibrationMarkers();
            }

            long currentAnnotationStateHash = ProjectAnnotationService.computeAnnotationStateHash(
                    QuPathGUI.getInstance().getImageData(),
                    isIncludeAllAnnotationsEnabled()
            );
            if (currentAnnotationStateHash == lastAnnotationStateHash) {
                return;
            }

            lastAnnotationStateHash = currentAnnotationStateHash;
            rebuildPlate();
            if (onAnnotationContentChanged != null) {
                onAnnotationContentChanged.run();
            }
        });

        hierarchy.addListener(hierarchyListener);
        currentHierarchy = hierarchy;
    }

    private void detachAnnotationListener() {
        if (currentHierarchy != null && hierarchyListener != null) {
            currentHierarchy.removeListener(hierarchyListener);
        }
        currentHierarchy = null;
        hierarchyListener = null;
    }

    private void setupIncludeAllAnnotationsToggle() {
        if (allAnnotationsCheckBox == null) {
            return;
        }

        allAnnotationsCheckBox.setSelected(false);
        allAnnotationsCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
            rebuildPlate();
            lastAnnotationStateHash = ProjectAnnotationService.computeAnnotationStateHash(
                    QuPathGUI.getInstance().getImageData(),
                    isIncludeAllAnnotationsEnabled()
            );
        });
    }

    private void setupAnnotationStateWatchdog() {
        annotationStateWatchdog = new Timeline(new KeyFrame(
                Duration.millis(1000),
                event -> checkAnnotationStateForPlateRefresh()
        ));
        annotationStateWatchdog.setCycleCount(Timeline.INDEFINITE);
        annotationStateWatchdog.play();
    }

    private void checkAnnotationStateForPlateRefresh() {
        if (suspendAnnotationWatchdog) {
            return;
        }
        if (settingsAccordion == null || settingsAccordion.getScene() == null) {
            return;
        }

        var window = settingsAccordion.getScene().getWindow();
        if (window == null || !window.isShowing()) {
            return;
        }

        CalibrationResolver.CalibrationStatus currentCalibrationStatus = getCurrentCalibrationStatus();
        if (currentCalibrationStatus != lastCalibrationStatus) {
            lastCalibrationStatus = currentCalibrationStatus;
            refreshSlideCalibrationMarkers();
        }

        long currentHash = ProjectAnnotationService.computeAnnotationStateHash(
                QuPathGUI.getInstance().getImageData(),
                isIncludeAllAnnotationsEnabled()
        );
        if (currentHash == lastAnnotationStateHash) {
            return;
        }

        lastAnnotationStateHash = currentHash;
        rebuildPlate();
        refreshSlideCalibrationMarkers();
        if (onAnnotationContentChanged != null) {
            onAnnotationContentChanged.run();
        }
    }

    private CalibrationResolver.CalibrationStatus getCurrentCalibrationStatus() {
        var imageData = QuPathGUI.getInstance().getImageData();
        return imageData == null
                ? null
                : CalibrationResolver.checkCalibrationStatus(imageData.getHierarchy());
    }

    /* ============================================================
     * Legend interactions
     * ============================================================ */

    private void configureLegendInteractions() {
        legendManager.updateColorMode();

        legendSample.setCursor(javafx.scene.Cursor.HAND);
        javafx.scene.control.Tooltip.install(
                legendSample,
                new javafx.scene.control.Tooltip(ShapeXResources.get("legend.sample.tooltip"))
        );
        legendSample.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            legendManager.toggleSampleColorMode();
            refreshPlateView();
        });
        legendSample.setOnContextMenuRequested(this::showSampleLegendContextMenu);

        legendUnused.setCursor(javafx.scene.Cursor.HAND);
        javafx.scene.control.Tooltip.install(
                legendUnused,
                new javafx.scene.control.Tooltip(ShapeXResources.get("legend.available.tooltip"))
        );
        legendUnused.setOnMouseClicked(event -> {
            highlightUnusedMode = !highlightUnusedMode;
            legendManager.setUnusedHighlight(highlightUnusedMode);
            refreshPlateView();
        });

        legendExcluded.setCursor(javafx.scene.Cursor.HAND);
        javafx.scene.control.Tooltip.install(
                legendExcluded,
                new javafx.scene.control.Tooltip(ShapeXResources.get("legend.unavailable.tooltip"))
        );
        legendExcluded.setOnMouseClicked(event -> {
            if (plateSet == null) {
                return;
            }

            for (String well : new HashSet<>(plateSet.getExcludedWells())) {
                toggleExcludeWell(well);
            }

            clampNegativeControlsToAllowed();
            refreshPlateView();
            updatePlateLabel();
            legendManager.updateCounts(plateSet, getPlateRows(), getPlateCols());
        });

        legendNegative.setCursor(javafx.scene.Cursor.HAND);
        javafx.scene.control.Tooltip.install(
                legendNegative,
                new javafx.scene.control.Tooltip(ShapeXResources.get("legend.negative.tooltip"))
        );
        legendNegative.setOnMouseClicked(event -> {
            if (plateSet == null) {
                return;
            }

            negativeControlsField.setText("0");
            refreshPlateView();
            updatePlateLabel();
            legendManager.updateCounts(plateSet, getPlateRows(), getPlateCols());
        });
    }

    private void showSampleLegendContextMenu(ContextMenuEvent event) {
        if (plateSet == null) {
            event.consume();
            return;
        }

        MenuItem enableAllItem = new MenuItem("Enable all");
        enableAllItem.setDisable(plateSet.getDisabledSamples().isEmpty());
        enableAllItem.setOnAction(e -> {
            if (plateSet.enableAllSamples()) {
                persistentlyDisabledSamples.clear();
                refreshPlateView();
                updatePlateLabel();
            }
        });

        ContextMenu contextMenu = new ContextMenu(enableAllItem);
        contextMenu.show(legendSample, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    /* ============================================================
     * Plate setup and rebuilding
     * ============================================================ */

    private void setupPlateAssignment() {
        installPlateDimensionFormatter(plateRows);
        installPlateDimensionFormatter(plateCols);
        installIntegerOnlyFormatter(negativeControlsField);
        installSelectAllOnEntry(negativeControlsField);

        plateRows.setText(Integer.toString(DEFAULT_ROWS));
        plateCols.setText(Integer.toString(DEFAULT_COLS));
        lastValidRows = DEFAULT_ROWS;
        lastValidCols = DEFAULT_COLS;

        plateSet = createPlateSet(
                DEFAULT_ROWS,
                DEFAULT_COLS,
                null,
                1,
                randomizeCheckBox == null || randomizeCheckBox.isSelected()
        );

        seedField.setText(plateSet.getSeed());
        syncNegativeControlsFieldFromCurrentPlate();

        negativeControlsField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (plateSet == null || updatingNegativeControlsField) {
                return;
            }

            int value = newValue.isBlank() ? 0 : Integer.parseInt(newValue);
            int maxAllowed = getMaxNegativeControlsAllowed();
            int clamped = Math.min(value, maxAllowed);
            String normalized = Integer.toString(clamped);

            if (!normalized.equals(newValue)) {
                negativeControlsField.setText(normalized);
                return;
            }

            if (isCustomLayoutEnabled()) {
                int effective = plateSet.setNegativeControlCountInPlace(clamped);
                if (effective != clamped) {
                    negativeControlsField.setText(Integer.toString(effective));
                    return;
                }
            } else {
                plateSet.setNegativeControlCount(clamped);
            }

            refreshPlateView();
            updatePlateLabel();
        });

        seedField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!newValue.isBlank() && !newValue.equals(plateSet.getSeed())) {
                plateSet.setSeed(newValue);
                refreshPlateView();
                updatePlateLabel();
            }
        });

        if (shuffleButton instanceof javafx.scene.control.Button button) {
            button.setOnAction(event -> {
                plateSet.shuffle();
                seedField.setText(plateSet.getSeed());
                refreshPlateView();
                updatePlateLabel();
            });
        }

        if (randomizeCheckBox != null) {
            randomizeCheckBox.setSelected(true);
            randomizeCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
                if (plateSet == null) {
                    return;
                }
                plateSet.setRandomizeSamples(newValue);
                refreshPlateView();
                updatePlateLabel();
                updateRandomizeDependentControls();
            });
        }

        if (customLayoutCheckBox != null) {
            customLayoutCheckBox.setSelected(false);
            customLayoutCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
                refreshPlateView();
                updatePlateLabel();
                updateRandomizeDependentControls();
            });
        }

        if (mapImageToPlateCheckBox != null) {
            mapImageToPlateCheckBox.setSelected(false);
            mapImageToPlateCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
                rebuildPlate();
                updateRandomizeDependentControls();
            });
        }

        if (poolClassCheckBox != null) {
            poolClassCheckBox.setSelected(true);
            poolClassCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> rebuildPlate());
        }

        if (uniquePlatesCheckBox != null) {
            uniquePlatesCheckBox.setSelected(false);
            uniquePlatesCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
                if (plateSet == null) {
                    return;
                }
                plateSet.setUniquePlateSettings(newValue);
                syncNegativeControlsFieldFromCurrentPlate();
                refreshPlateView();
                updatePlateLabel();
            });
        }

        plateRows.textProperty().addListener((obs, oldValue, newValue) -> handlePlateDimensionLiveChange());
        plateCols.textProperty().addListener((obs, oldValue, newValue) -> handlePlateDimensionLiveChange());
        plateRows.setOnAction(event -> commitPlateDimensions());
        plateCols.setOnAction(event -> commitPlateDimensions());
        plateRows.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitPlateDimensions();
            }
        });
        plateCols.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitPlateDimensions();
            }
        });

        updateWellCount();
        refreshPlateView();
        updatePlateLabel();
        updateRandomizeDependentControls();
    }

    private void setupPlateNavigation() {
        if (prevPlateButton instanceof javafx.scene.control.Button button) {
            button.setOnAction(event -> {
                if (plateSet == null) {
                    return;
                }
                plateSet.previousPlate();
                refreshPlateView();
                updatePlateLabel();
            });
        }

        if (nextPlateButton instanceof javafx.scene.control.Button button) {
            button.setOnAction(event -> {
                if (plateSet == null) {
                    return;
                }
                plateSet.nextPlate();
                refreshPlateView();
                updatePlateLabel();
            });
        }

        if (removePlateButton instanceof javafx.scene.control.Button button) {
            button.setOnAction(event -> {
                boolean removed = plateSet != null && (
                        isCustomLayoutEnabled()
                                ? plateSet.removePlateInPlace()
                                : plateSet.removePlate()
                );
                if (removed) {
                    refreshPlateView();
                    updatePlateLabel();
                }
            });
        }

        if (resetPlateCountButton instanceof javafx.scene.control.Button button) {
            button.setOnAction(event -> {
                if (plateSet == null || !plateSet.canResetPlateCountToMinimumRequired()) {
                    return;
                }
                plateSet.resetPlateCountToMinimumRequired();
                refreshPlateView();
                updatePlateLabel();
            });
        }

        if (addPlateButton instanceof javafx.scene.control.Button button) {
            button.setOnAction(event -> {
                boolean added = plateSet != null && (
                        isCustomLayoutEnabled()
                                ? plateSet.addBlankPlate()
                                : plateSet.addPlate()
                );
                if (added) {
                    refreshPlateView();
                    updatePlateLabel();
                }
            });
        }

        plateIndexLabel.setText(ShapeXResources.get("plate.index.initial"));
    }

    private void rebuildPlate() {
        Integer rowsParsed = parsePlateDimensionInput(plateRows.getText());
        Integer colsParsed = parsePlateDimensionInput(plateCols.getText());

        int rows = rowsParsed != null ? rowsParsed : lastValidRows;
        int cols = colsParsed != null ? colsParsed : lastValidCols;
        lastValidRows = rows;
        lastValidCols = cols;

        rebuildPlateWithDimensions(rows, cols, false);
    }

    private void rebuildPlateWithDimensions(int rows, int cols, boolean resetDimensionDependentState) {
        if (plateSet != null) {
            persistentlyDisabledSamples.addAll(plateSet.getDisabledSamples());
        }

        String preservedSeed = plateSet != null ? plateSet.getSeed() : null;
        int preservedRequestedPlateCount = plateSet != null ? plateSet.getRequestedPlateCount() : 1;
        int preservedNegativeControls = plateSet != null ? plateSet.getNegativeControlCount() : 0;
        Set<String> preservedExcludedWells = plateSet != null && !resetDimensionDependentState
                ? new HashSet<>(plateSet.getExcludedWells())
                : Set.of();
        boolean preservedUniquePlateSettings = plateSet != null && plateSet.isUniquePlateSettings();
        Set<String> preservedSharedExcludedWells = plateSet != null && !resetDimensionDependentState
                ? plateSet.getSharedExcludedWellsSnapshot()
                : Set.of();
        int preservedSharedNegativeControls = plateSet != null
                ? plateSet.getSharedNegativeControlCount()
                : 0;
        List<Set<String>> preservedExcludedWellsByPlate = preservedUniquePlateSettings
                && plateSet != null
                && !resetDimensionDependentState
                ? plateSet.getExcludedWellsByPlateSnapshot()
                : List.of();
        List<Integer> preservedNegativeCountsByPlate = preservedUniquePlateSettings && plateSet != null
                ? plateSet.getNegativeControlCountsByPlateSnapshot()
                : List.of();

        plateSet = createPlateSet(
                rows,
                cols,
                preservedSeed,
                preservedRequestedPlateCount,
                randomizeCheckBox == null || randomizeCheckBox.isSelected()
        );

        if (isUniquePlatesEnabled() || preservedUniquePlateSettings) {
            plateSet.setSharedPlateDefaults(preservedSharedExcludedWells, preservedSharedNegativeControls);
            plateSet.setUniquePlateSettings(true);
            plateSet.setPlateSpecificSettings(
                    preservedExcludedWellsByPlate,
                    preservedNegativeCountsByPlate
            );
        } else {
            plateSet.setExcludedWells(preservedExcludedWells);
            plateSet.setNegativeControlCount(preservedNegativeControls);
        }
        plateSet.setDisabledSamples(persistentlyDisabledSamples);

        clampNegativeControlsToAllowed();
        if (resetDimensionDependentState) {
            plateSet.resetPlateCountToMinimumRequired();
        }
        updateWellCount();
        refreshPlateView();
        updatePlateLabel();
        legendManager.updateCounts(plateSet, rows, cols);
    }

    private void refreshPlateView() {
        if (plateSet == null) {
            return;
        }

        PlateAssignmentModel plate = plateSet.getCurrentPlate();
        if (plate == null) {
            return;
        }
        syncNegativeControlsFieldFromCurrentPlate();

        int rows = getPlateRows();
        int cols = getPlateCols();
        updatePlateControlsLayout(cols);

        Map<String, Integer> sampleColors = new HashMap<>();
        Map<String, ProjectAnnotationService.SampleTooltipData> sampleTooltipData = new HashMap<>();

        Project<?> project = QuPathGUI.getInstance().getProject();
        List<ProjectImageEntry<BufferedImage>> selectedEntriesInOrder =
                getSelectedImageEntriesInListOrder();

        if (project != null) {
            if (selectedEntriesInOrder.isEmpty()) {
                var imageData = QuPathGUI.getInstance().getImageData();
                if (imageData != null) {
                    ProjectAnnotationService.collectSampleViewData(
                            imageData.getHierarchy(),
                            ProjectAnnotationService.getCurrentSlideName(),
                            isIncludeAllAnnotationsEnabled(),
                            isImageToPlateMappingEnabled(),
                            isPoolClassesEnabled(),
                            sampleColors,
                        sampleTooltipData
                    );
                }
            } else {
                for (ProjectImageEntry<BufferedImage> entry : selectedEntriesInOrder) {
                    try {
                        var imageData = ProjectAnnotationService.getImageDataForEntry(entry);
                        ProjectAnnotationService.collectSampleViewData(
                                imageData.getHierarchy(),
                                entry.getImageName(),
                                isIncludeAllAnnotationsEnabled(),
                                isImageToPlateMappingEnabled(),
                                isPoolClassesEnabled(),
                                sampleColors,
                                sampleTooltipData
                        );
                    } catch (Exception ignored) {}
                }
            }
        }

        Set<String> disabledSamples = plateSet.getDisabledSamples();
        Set<String> immutableWells = new HashSet<>(plateSet.getExcludedWells());
        for (var entry : plate.getSampleToWell().entrySet()) {
            if (disabledSamples.contains(entry.getKey())) {
                immutableWells.add(entry.getValue());
            }
        }

        PlateAssignmentView view = new PlateAssignmentView(
                rows,
                cols,
                plate.getSampleToWell(),
                sampleColors,
                plateSet.getExcludedWells(),
                plate.getNegativeControlWells(),
                disabledSamples,
                immutableWells,
                legendManager.getSampleColorMode(),
                highlightUnusedMode,
                sampleTooltipData,
                isCustomLayoutEnabled(),
                plateSet.getCurrentPlateIndex(),
                plateSet.getPlateCount(),
                well -> {
                    if (isCustomLayoutEnabled() && plateSet.isDisabledSampleAtCurrentWell(well)) {
                        return;
                    }
                    toggleExcludeWell(well);
                    clampNegativeControlsToAllowed();
                    refreshPlateView();
                    updatePlateLabel();
                },
                (fromWell, toWell) -> {
                    if (!isCustomLayoutEnabled()) {
                        return;
                    }
                    if (plateSet.swapWellsOnCurrentPlate(fromWell, toWell)) {
                        refreshPlateView();
                        updatePlateLabel();
                    }
                },
                (sourceWell, targetPlateIndex) -> {
                    if (!isCustomLayoutEnabled()) {
                        return;
                    }
                    if (plateSet.moveSampleFromCurrentPlateToPlate(sourceWell, targetPlateIndex)) {
                        refreshPlateView();
                        updatePlateLabel();
                    }
                },
                well -> {
                    String sample = getSampleAtCurrentWell(well);
                    if (plateSet.disableSampleAtCurrentWell(well)) {
                        if (sample != null) {
                            persistentlyDisabledSamples.add(sample);
                        }
                        refreshPlateView();
                        updatePlateLabel();
                    }
                },
                well -> {
                    String sample = getSampleAtCurrentWell(well);
                    if (plateSet.enableSampleAtCurrentWell(well)) {
                        if (sample != null) {
                            persistentlyDisabledSamples.remove(sample);
                        }
                        refreshPlateView();
                        updatePlateLabel();
                    }
                },
                rowName -> {
                    toggleRow(rowName, cols);
                    clampNegativeControlsToAllowed();
                    refreshPlateView();
                    updatePlateLabel();
                },
                column -> {
                    toggleColumn(column, rows);
                    clampNegativeControlsToAllowed();
                    refreshPlateView();
                    updatePlateLabel();
                }
        );

        plateViewportContainer.getChildren().setAll(view);
        updatePlateViewportContainerSize();
        Platform.runLater(() -> {
            updatePlateViewportContainerSize();
            alignPlateSideMenus(view, rows);
        });
        legendManager.updateCounts(plateSet, rows, cols);
    }

    private String getSampleAtCurrentWell(String well) {
        if (plateSet == null || well == null || plateSet.getCurrentPlate() == null) {
            return null;
        }
        for (var entry : plateSet.getCurrentPlate().getSampleToWell().entrySet()) {
            if (well.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /* ============================================================
     * Slide selection
     * ============================================================ */

    private void populateSlideList() {
        slideListBox.getChildren().clear();
        checkAllSlides = null;

        Project<?> project = QuPathGUI.getInstance().getProject();
        if (project == null) {
            showSlideListPlaceholder(ShapeXResources.get("project.none.open"));
            return;
        }
        if (project.getImageList().isEmpty()) {
            showSlideListPlaceholder(ShapeXResources.get("project.images.none"));
            return;
        }

        checkAllSlides = new CheckBox(ShapeXResources.get("slides.check_all"));
        checkAllSlides.getStyleClass().add("check-all-checkbox");
        checkAllSlides.setMaxWidth(Double.MAX_VALUE);
        checkAllSlides.setAlignment(Pos.CENTER_LEFT);
        checkAllSlides.selectedProperty().addListener((obs, oldValue, newValue) -> {
            if (updatingSlideSelection) {
                return;
            }

            updatingSlideSelection = true;
            for (Node node : slideListBox.getChildren()) {
                CheckBox checkBox = getSlideCheckBox(node);
                if (checkBox != null && checkBox != checkAllSlides) {
                    checkBox.setSelected(newValue);
                }
            }
            updatingSlideSelection = false;
            rebuildPlate();
        });
        slideListBox.getChildren().add(checkAllSlides);

        for (ProjectImageEntry<BufferedImage> entry : getProjectImageEntriesInCanonicalOrder(project)) {
            String name = entry.getImageName();
            CheckBox checkBox = new CheckBox(name);
            checkBox.setUserData(entry);
            checkBox.setMaxWidth(Double.MAX_VALUE);
            checkBox.setAlignment(Pos.CENTER_LEFT);
            checkBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
                if (updatingSlideSelection) {
                    return;
                }

                updatingSlideSelection = true;

                boolean allChecked = true;
                for (Node node : slideListBox.getChildren()) {
                    CheckBox other = getSlideCheckBox(node);
                    if (other == null) {
                        continue;
                    }
                    if (other == checkAllSlides) {
                        continue;
                    }
                    if (!other.isSelected()) {
                        allChecked = false;
                        break;
                    }
                }

                checkAllSlides.setSelected(allChecked);
                updatingSlideSelection = false;
                rebuildPlate();
            });

            slideListBox.getChildren().add(createSlideRow(checkBox));
        }

        refreshSlideCalibrationMarkers();
        updateSlideHighlight();
    }

    private void updateSlideHighlight() {
        var project = QuPathGUI.getInstance().getProject();
        if (project == null) {
            return;
        }

        var currentImageData = QuPathGUI.getInstance().getImageData();
        ProjectImageEntry<?> currentEntry = currentImageData == null
                ? null
                : project.getEntry(currentImageData);

        for (Node node : slideListBox.getChildren()) {
            CheckBox checkBox = getSlideCheckBox(node);
            if (checkBox == null) {
                continue;
            }

            node.getStyleClass().remove(SLIDE_ACTIVE_CLASS);
            checkBox.getStyleClass().remove(SLIDE_ACTIVE_CLASS);
            if (isSameProjectEntry(currentEntry, getProjectImageEntry(checkBox))) {
                (node == checkBox ? checkBox : node).getStyleClass().add(SLIDE_ACTIVE_CLASS);
            }
        }
    }

    void refreshSlideCalibrationMarkers() {
        Project<?> project = QuPathGUI.getInstance().getProject();
        if (project == null) {
            return;
        }

        for (Node node : slideListBox.getChildren()) {
            CheckBox checkBox = getSlideCheckBox(node);
            Label marker = getCalibrationMarker(node);
            if (checkBox == null || marker == null || checkBox == checkAllSlides) {
                continue;
            }

            CalibrationMarkerState state = getCalibrationMarkerState(getProjectImageEntry(checkBox));
            String tooltipText = ShapeXResources.format(
                    "slides.calibration_missing.tooltip",
                    state.pointsNeeded(),
                    state.pointsFound()
            );
            marker.getTooltip().setText(tooltipText);
            marker.setAccessibleText(tooltipText);
            marker.setVisible(state.missingCalibration());
        }
    }

    private Node createSlideRow(CheckBox checkBox) {
        Label calibrationMarker = new Label(ShapeXResources.get("slides.calibration_missing.marker"));
        calibrationMarker.getStyleClass().add(SLIDE_CALIBRATION_MARKER_CLASS);
        String tooltipText = ShapeXResources.format("slides.calibration_missing.tooltip", 3, 0);
        calibrationMarker.setTooltip(new Tooltip(tooltipText));
        calibrationMarker.setAccessibleText(tooltipText);
        calibrationMarker.setVisible(false);

        HBox row = new HBox(4, checkBox, calibrationMarker);
        row.getStyleClass().add(SLIDE_ROW_CLASS);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(checkBox, Priority.ALWAYS);
        return row;
    }

    private CalibrationMarkerState getCalibrationMarkerState(
            ProjectImageEntry<BufferedImage> entry
    ) {
        if (entry == null) {
            return new CalibrationMarkerState(false, 0, 0);
        }

        try {
            var imageData = ProjectAnnotationService.getImageDataForEntry(entry);
            var hierarchy = imageData.getHierarchy();
            int pointsFound = Math.min(3, CalibrationResolver.countCalibrationPoints(hierarchy));
            int pointsNeeded = Math.max(0, 3 - pointsFound);
            boolean missingCalibration = CalibrationResolver.checkCalibrationStatus(hierarchy)
                    == CalibrationResolver.CalibrationStatus.MISSING;
            return new CalibrationMarkerState(missingCalibration, pointsFound, pointsNeeded);
        } catch (Exception e) {
            return new CalibrationMarkerState(true, 0, 3);
        }
    }

    private record CalibrationMarkerState(
            boolean missingCalibration,
            int pointsFound,
            int pointsNeeded
    ) {}

    @SuppressWarnings("unchecked")
    private static List<ProjectImageEntry<BufferedImage>> getProjectImageEntriesInCanonicalOrder(
            Project<?> project
    ) {
        List<ProjectImageEntry<BufferedImage>> entries = new ArrayList<>();
        for (ProjectImageEntry<?> entry : project.getImageList()) {
            entries.add((ProjectImageEntry<BufferedImage>) entry);
        }
        entries.sort(Comparator
                .comparing((ProjectImageEntry<BufferedImage> entry) -> safeSortValue(entry.getImageName()))
                .thenComparing(entry -> safeSortValue(entry.getID())));
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> getProjectImageEntry(CheckBox checkBox) {
        Object userData = checkBox == null ? null : checkBox.getUserData();
        return userData instanceof ProjectImageEntry<?> entry
                ? (ProjectImageEntry<BufferedImage>) entry
                : null;
    }

    private static boolean isSameProjectEntry(
            ProjectImageEntry<?> first,
            ProjectImageEntry<?> second
    ) {
        if (first == null || second == null) {
            return false;
        }
        String firstId = first.getID();
        return first == second || (firstId != null && Objects.equals(firstId, second.getID()));
    }

    private static String safeSortValue(String value) {
        return value == null ? "" : value;
    }

    private CheckBox getSlideCheckBox(Node node) {
        if (node instanceof CheckBox checkBox) {
            return checkBox;
        }
        if (node instanceof HBox row) {
            for (Node child : row.getChildren()) {
                if (child instanceof CheckBox checkBox) {
                    return checkBox;
                }
            }
        }
        return null;
    }

    private Label getCalibrationMarker(Node node) {
        if (node instanceof HBox row) {
            for (Node child : row.getChildren()) {
                if (child instanceof Label label
                        && label.getStyleClass().contains(SLIDE_CALIBRATION_MARKER_CLASS)) {
                    return label;
                }
            }
        }
        return null;
    }

    private void showSlideListPlaceholder(String message) {
        slideListBox.getChildren().clear();
        Label placeholder = new Label(message);
        placeholder.getStyleClass().add("italic-message");
        placeholder.setMaxWidth(Double.MAX_VALUE);
        placeholder.setAlignment(Pos.CENTER);

        StackPane placeholderPane = new StackPane(placeholder);
        placeholderPane.setMaxWidth(Double.MAX_VALUE);
        placeholderPane.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> slideScrollPane == null
                        ? 0.0
                        : Math.max(0.0, slideScrollPane.getViewportBounds().getHeight()),
                slideScrollPane == null ? slideListBox.heightProperty() : slideScrollPane.viewportBoundsProperty()
        ));
        slideListBox.getChildren().add(placeholderPane);
    }

    private List<ProjectImageEntry<BufferedImage>> getSelectedImageEntriesInListOrder() {
        List<ProjectImageEntry<BufferedImage>> selected = new ArrayList<>();
        for (Node node : slideListBox.getChildren()) {
            CheckBox checkBox = getSlideCheckBox(node);
            if (checkBox == null) {
                continue;
            }
            if (checkBox == checkAllSlides) {
                continue;
            }
            if (checkBox.isSelected()) {
                ProjectImageEntry<BufferedImage> entry = getProjectImageEntry(checkBox);
                if (entry != null) {
                    selected.add(entry);
                }
            }
        }
        return selected;
    }

    private List<String> getSamplesInAssignmentOrder() {
        Project<?> project = QuPathGUI.getInstance().getProject();
        return ProjectAnnotationService.getSamplesForEntriesInAssignmentOrder(
                project,
                getSelectedImageEntriesInListOrder(),
                isIncludeAllAnnotationsEnabled(),
                false,
                isPoolClassesEnabled()
        );
    }

    private List<List<String>> getSamplesGroupedBySlideInAssignmentOrder() {
        Project<?> project = QuPathGUI.getInstance().getProject();
        return ProjectAnnotationService.getSamplesGroupedByEntry(
                project,
                getSelectedImageEntriesInListOrder(),
                isIncludeAllAnnotationsEnabled(),
                true,
                isPoolClassesEnabled()
        );
    }

    private MultiPlateAssignmentModel createPlateSet(
            int rows,
            int cols,
            String seed,
            int desiredPlateCount,
            boolean randomizeSamples
    ) {
        if (isImageToPlateMappingEnabled()) {
            return new MultiPlateAssignmentModel(
                    getSamplesGroupedBySlideInAssignmentOrder(),
                    rows,
                    cols,
                    seed,
                    randomizeSamples
            );
        }

        return new MultiPlateAssignmentModel(
                getSamplesInAssignmentOrder(),
                rows,
                cols,
                seed,
                desiredPlateCount,
                randomizeSamples
        );
    }

    /* ============================================================
     * Plate viewport sizing
     * ============================================================ */

    private void initializePlateViewportContainer() {
        plateViewportContainer.setAlignment(Pos.TOP_CENTER);
        plateScrollPane.setPrefViewportWidth(DEFAULT_PLATE_VIEWPORT_WIDTH);
        plateScrollPane.setPrefViewportHeight(DEFAULT_PLATE_VIEWPORT_HEIGHT);
        updatePlateExpansionLimits(DEFAULT_PLATE_VIEWPORT_WIDTH, DEFAULT_PLATE_VIEWPORT_HEIGHT);
        plateScrollPane.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            updatePlateViewportContainerSize();
            Platform.runLater(this::alignCurrentPlateLayout);
        });
        plateScrollPane.setContent(plateViewportContainer);
        updatePlateViewportContainerSize();
    }

    private void updatePlateViewportContainerSize() {
        double viewportWidth = plateScrollPane.getViewportBounds().getWidth();
        double viewportHeight = plateScrollPane.getViewportBounds().getHeight();

        double plateWidth = 0.0;
        double plateHeight = 0.0;

        if (!plateViewportContainer.getChildren().isEmpty()) {
            Node plateNode = plateViewportContainer.getChildren().get(0);
            if (plateNode instanceof Region region) {
                plateWidth = region.prefWidth(-1);
                plateHeight = region.prefHeight(-1);
            } else {
                plateWidth = plateNode.getLayoutBounds().getWidth();
                plateHeight = plateNode.getLayoutBounds().getHeight();
            }
        }

        double targetWidth = Math.max(viewportWidth, plateWidth);
        double targetHeight = Math.max(viewportHeight, plateHeight);
        updatePlateExpansionLimits(plateWidth, plateHeight);
        plateViewportContainer.setMinSize(targetWidth, targetHeight);
        plateViewportContainer.setPrefSize(targetWidth, targetHeight);
        updatePlateControlsLayout(getPlateCols());
    }

    private void updatePlateExpansionLimits(double plateWidth, double plateHeight) {
        double maxViewportWidth = Math.max(DEFAULT_PLATE_VIEWPORT_WIDTH, plateWidth);
        double maxViewportHeight = Math.max(DEFAULT_PLATE_VIEWPORT_HEIGHT, plateHeight);
        double maxScrollPaneWidth = maxViewportWidth + PLATE_SCROLL_CHROME_WIDTH;
        double maxScrollPaneHeight = maxViewportHeight + PLATE_SCROLL_CHROME_HEIGHT;

        plateScrollPane.setMaxWidth(maxScrollPaneWidth);
        plateScrollPane.setMaxHeight(maxScrollPaneHeight);

        if (plateDesignerGrid != null) {
            plateDesignerGrid.setMaxWidth(
                    PLATE_DESIGNER_LEFT_MENU_WIDTH
                            + PLATE_DESIGNER_LEFT_GAP
                            + maxScrollPaneWidth
                            + PLATE_DESIGNER_RIGHT_GAP
                            + PLATE_DESIGNER_RIGHT_MENU_WIDTH
            );
        }
    }

    private void updatePlateControlsLayout(int columns) {
        if (plateControlsRow == null) {
            return;
        }

        double plateWidth = PlateAssignmentView.preferredWidthForColumns(columns);
        double viewportWidth = plateScrollPane == null
                ? 0.0
                : plateScrollPane.getViewportBounds().getWidth();
        double availableWidth = viewportWidth > 0.0
                ? Math.max(DEFAULT_PLATE_VIEWPORT_WIDTH, viewportWidth)
                : DEFAULT_PLATE_VIEWPORT_WIDTH;
        double controlsWidth = Math.max(
                DEFAULT_PLATE_VIEWPORT_WIDTH,
                Math.min(plateWidth, availableWidth)
        );
        plateControlsRow.setMinWidth(controlsWidth);
        plateControlsRow.setPrefWidth(controlsWidth);
        plateControlsRow.setMaxWidth(controlsWidth);
        plateControlsRow.setPadding(new Insets(
                0.0,
                PLATE_CONTROLS_RIGHT_NUDGE,
                0.0,
                PLATE_CONTROLS_LEFT_NUDGE
        ));
        plateControlsRow.setTranslateY(PLATE_CONTROLS_VERTICAL_NUDGE);
    }

    private void alignPlateSideMenus(PlateAssignmentView view, int rows) {
        if (!plateViewportContainer.getChildren().contains(view)) {
            return;
        }

        Bounds viewportBounds = getPlateViewportBoundsInScene();
        if (viewportBounds == null) {
            return;
        }
        double columnHeaderCenterY = viewportBounds.getMinY()
                + PlateAssignmentView.columnHeaderCenterOffsetY();
        alignMenuAnchor(
                plateFormatMenu,
                wells96PlateRadio,
                columnHeaderCenterY,
                PLATE_LEFT_MENU_HEADER_NUDGE
        );
        alignMenuAnchor(
                plateLegendMenu,
                legendSample,
                columnHeaderCenterY,
                PLATE_RIGHT_MENU_HEADER_NUDGE
        );
        Platform.runLater(() -> alignSlideListBottom(view, rows));
    }

    private void alignMenuAnchor(Node menu, Node anchor, double targetCenterY, double nudge) {
        if (menu == null || anchor == null || !Double.isFinite(targetCenterY)) {
            return;
        }

        var anchorBounds = anchor.localToScene(anchor.getBoundsInLocal());
        if (anchorBounds == null) {
            return;
        }

        double anchorCenterY = (anchorBounds.getMinY() + anchorBounds.getMaxY()) / 2.0;
        double correction = targetCenterY - anchorCenterY + nudge;
        if (Math.abs(correction) > 0.25) {
            menu.setTranslateY(menu.getTranslateY() + correction);
        }
    }

    private void alignSlideListBottom(PlateAssignmentView view, int rows) {
        if (slideScrollPane == null || !plateViewportContainer.getChildren().contains(view)) {
            return;
        }

        Bounds viewportBounds = getPlateViewportBoundsInScene();
        var slideListBounds = slideScrollPane.localToScene(slideScrollPane.getBoundsInLocal());
        if (viewportBounds == null || slideListBounds == null) {
            return;
        }

        // Keep the side menus at least as tall as a 384-well plate, matching
        // the width floor used by the controls below the plate.
        int layoutRows = Math.max(rows, WELLS_384_ROWS);
        double visiblePlateHeight = Math.min(
                viewportBounds.getHeight(),
                PlateAssignmentView.lastWellBottomOffsetY(layoutRows)
        );
        double targetBottomY = viewportBounds.getMinY()
                + visiblePlateHeight
                + PLATE_SLIDE_LIST_BOTTOM_NUDGE;
        double targetHeight = Math.max(40.0, targetBottomY - slideListBounds.getMinY());
        if (Math.abs(slideScrollPane.getPrefHeight() - targetHeight) > 0.5) {
            slideScrollPane.setMinHeight(targetHeight);
            slideScrollPane.setPrefHeight(targetHeight);
            slideScrollPane.setMaxHeight(targetHeight);
        }
    }

    private Bounds getPlateViewportBoundsInScene() {
        Node viewport = plateScrollPane == null ? null : plateScrollPane.lookup(".viewport");
        if (viewport == null) {
            return null;
        }
        return viewport.localToScene(viewport.getBoundsInLocal());
    }

    private void alignCurrentPlateLayout() {
        if (plateViewportContainer.getChildren().isEmpty()) {
            return;
        }
        Node plateNode = plateViewportContainer.getChildren().get(0);
        if (plateNode instanceof PlateAssignmentView view) {
            alignPlateSideMenus(view, getPlateRows());
        }
    }

    /* ============================================================
     * Plate dimension controls
     * ============================================================ */

    private void setupPlateFormatSelector() {
        if (plateFormatToggleGroup == null) {
            return;
        }

        plateFormatToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                return;
            }

            if (newToggle == wells96PlateRadio) {
                applyPlateFormatDimensions(WELLS_96_ROWS, WELLS_96_COLS);
            } else if (newToggle == wells384PlateRadio) {
                applyPlateFormatDimensions(WELLS_384_ROWS, WELLS_384_COLS);
            } else if (newToggle == wells1536PlateRadio) {
                applyPlateFormatDimensions(WELLS_1536_ROWS, WELLS_1536_COLS);
            } else if (newToggle == customPlateRadio) {
                rebuildPlateWithDimensions(lastValidRows, lastValidCols, true);
            }

            updatePlateDimensionFieldLockState();
        });

        updatePlateDimensionFieldLockState();
    }

    private void applyPlateFormatDimensions(int rows, int cols) {
        String rowText = Integer.toString(rows);
        String colText = Integer.toString(cols);

        updatingPlateDimensions = true;
        try {
            plateRows.setText(rowText);
            plateCols.setText(colText);
        } finally {
            updatingPlateDimensions = false;
        }

        lastValidRows = rows;
        lastValidCols = cols;
        rebuildPlateWithDimensions(rows, cols, true);
    }

    private void updatePlateDimensionFieldLockState() {
        boolean customSelected = plateFormatToggleGroup != null
                && plateFormatToggleGroup.getSelectedToggle() == customPlateRadio;

        plateRows.setEditable(customSelected);
        plateCols.setEditable(customSelected);
        plateRows.setDisable(!customSelected);
        plateCols.setDisable(!customSelected);
    }

    private void handlePlateDimensionLiveChange() {
        if (updatingPlateDimensions || committingPlateDimensions) {
            return;
        }

        Integer rowsParsed = parsePlateDimensionInput(plateRows.getText());
        Integer colsParsed = parsePlateDimensionInput(plateCols.getText());
        if (rowsParsed == null || colsParsed == null) {
            return;
        }

        lastValidRows = rowsParsed;
        lastValidCols = colsParsed;
        rebuildPlateWithDimensions(rowsParsed, colsParsed, true);
    }

    private void commitPlateDimensions() {
        if (committingPlateDimensions) {
            return;
        }

        int rowsBefore = lastValidRows;
        int colsBefore = lastValidCols;

        committingPlateDimensions = true;
        try {
            commitPlateDimensionField(plateRows, true);
            commitPlateDimensionField(plateCols, false);
        } finally {
            committingPlateDimensions = false;
        }

        if (lastValidRows != rowsBefore || lastValidCols != colsBefore) {
            rebuildPlateWithDimensions(lastValidRows, lastValidCols, true);
        }
    }

    private void commitPlateDimensionField(TextField field, boolean isRowsField) {
        Integer parsed = parsePlateDimensionInput(field.getText());
        int previous = isRowsField ? lastValidRows : lastValidCols;
        int committed = parsed != null ? parsed : previous;

        if (isRowsField) {
            lastValidRows = committed;
        } else {
            lastValidCols = committed;
        }

        String normalized = Integer.toString(committed);
        if (!normalized.equals(field.getText())) {
            field.setText(normalized);
        }
    }

    /* ============================================================
     * Plate interactions
     * ============================================================ */

    private void toggleRow(String rowName, int cols) {
        boolean allExcluded = true;
        for (int c = 1; c <= cols; c++) {
            String well = rowName + c;
            if (!plateSet.getExcludedWells().contains(well)) {
                allExcluded = false;
                break;
            }
        }

        for (int c = 1; c <= cols; c++) {
            String well = rowName + c;
            boolean currentlyExcluded = plateSet.getExcludedWells().contains(well);
            if (allExcluded) {
                if (currentlyExcluded) {
                    toggleExcludeWell(well);
                }
            } else if (!currentlyExcluded) {
                toggleExcludeWell(well);
            }
        }
    }

    private void toggleColumn(int col, int rows) {
        boolean allExcluded = true;
        for (int r = 0; r < rows; r++) {
            String well = PlateLayoutUtils.rowLabel(r) + col;
            if (!plateSet.getExcludedWells().contains(well)) {
                allExcluded = false;
                break;
            }
        }

        for (int r = 0; r < rows; r++) {
            String well = PlateLayoutUtils.rowLabel(r) + col;
            boolean currentlyExcluded = plateSet.getExcludedWells().contains(well);
            if (allExcluded) {
                if (currentlyExcluded) {
                    toggleExcludeWell(well);
                }
            } else if (!currentlyExcluded) {
                toggleExcludeWell(well);
            }
        }
    }

    private void toggleExcludeWell(String well) {
        if (plateSet == null || well == null) {
            return;
        }

        if (isCustomLayoutEnabled()) {
            plateSet.toggleExcludeInPlace(well);
        } else {
            plateSet.toggleExclude(well);
        }
    }

    private int getMaxNegativeControlsAllowed() {
        if (plateSet == null) {
            return 0;
        }

        int totalWells = getPlateRows() * getPlateCols();
        int excluded = plateSet.getExcludedWells() != null ? plateSet.getExcludedWells().size() : 0;
        return Math.max(0, totalWells - excluded - 1);
    }

    private void clampNegativeControlsToAllowed() {
        if (plateSet == null) {
            return;
        }

        int current = negativeControlsField.getText().isBlank()
                ? 0
                : Integer.parseInt(negativeControlsField.getText());
        int clamped = Math.min(current, getMaxNegativeControlsAllowed());

        if (clamped != current) {
            negativeControlsField.setText(Integer.toString(clamped));
        }
    }

    private void updatePlateLabel() {
        if (plateSet == null) {
            return;
        }

        boolean manualPlateAdjustmentEnabled = isRandomizeEnabled() || isCustomLayoutEnabled();

        plateIndexLabel.setText(
                (plateSet.getCurrentPlateIndex() + 1)
                        + " / "
                        + plateSet.getPlateCount()
        );

        if (prevPlateButton instanceof javafx.scene.control.Button button) {
            button.setDisable(plateSet.getCurrentPlateIndex() == 0);
        }
        if (nextPlateButton instanceof javafx.scene.control.Button button) {
            button.setDisable(plateSet.getCurrentPlateIndex() >= plateSet.getPlateCount() - 1);
        }
        if (removePlateButton instanceof javafx.scene.control.Button button) {
            button.setDisable(!manualPlateAdjustmentEnabled || !canRemoveCurrentPlate());
        }
        if (resetPlateCountButton instanceof javafx.scene.control.Button button) {
            button.setDisable(!manualPlateAdjustmentEnabled
                    || !plateSet.canResetPlateCountToMinimumRequired());
        }
        if (addPlateButton instanceof javafx.scene.control.Button button) {
            button.setDisable(!manualPlateAdjustmentEnabled || !plateSet.canAddPlate());
        }
    }

    private boolean isRandomizeEnabled() {
        return randomizeCheckBox == null || randomizeCheckBox.isSelected();
    }

    private boolean isCustomLayoutEnabled() {
        return customLayoutCheckBox != null && customLayoutCheckBox.isSelected();
    }

    private boolean canRemoveCurrentPlate() {
        if (plateSet == null) {
            return false;
        }
        return isCustomLayoutEnabled()
                ? plateSet.canRemovePlateInPlace()
                : plateSet.canRemovePlate();
    }

    private void updateRandomizeDependentControls() {
        boolean randomizeEnabled = isRandomizeEnabled();
        boolean customEnabled = isCustomLayoutEnabled();
        boolean manualPlateAdjustmentEnabled = randomizeEnabled || customEnabled;

        if (mapImageToPlateCheckBox != null) {
            if (customEnabled && mapImageToPlateCheckBox.isSelected()) {
                mapImageToPlateCheckBox.setSelected(false);
            }
            mapImageToPlateCheckBox.setDisable(customEnabled);
        }

        if (randomizeCheckBox != null) {
            randomizeCheckBox.setDisable(customEnabled);
        }

        if (seedField != null) {
            seedField.setDisable(!randomizeEnabled || customEnabled);
            seedField.setEditable(randomizeEnabled && !customEnabled);
        }

        if (shuffleButton instanceof javafx.scene.control.Button button) {
            button.setDisable(!randomizeEnabled || customEnabled);
        }

        if (plateSet != null) {
            if (removePlateButton instanceof javafx.scene.control.Button button) {
                button.setDisable(!manualPlateAdjustmentEnabled || !canRemoveCurrentPlate());
            }
            if (resetPlateCountButton instanceof javafx.scene.control.Button button) {
                button.setDisable(!manualPlateAdjustmentEnabled
                        || !plateSet.canResetPlateCountToMinimumRequired());
            }
            if (addPlateButton instanceof javafx.scene.control.Button button) {
                button.setDisable(!manualPlateAdjustmentEnabled || !plateSet.canAddPlate());
            }
        }
    }

    private void syncNegativeControlsFieldFromCurrentPlate() {
        if (negativeControlsField == null || plateSet == null) {
            return;
        }

        String targetValue = Integer.toString(Math.max(0, plateSet.getNegativeControlCount()));
        if (targetValue.equals(negativeControlsField.getText())) {
            return;
        }

        updatingNegativeControlsField = true;
        try {
            negativeControlsField.setText(targetValue);
        } finally {
            updatingNegativeControlsField = false;
        }
    }

    private void updateWellCount() {
        int rows = getPlateRows();
        int cols = getPlateCols();
        wellCountLabel.setText(ShapeXResources.format("plate.well.count", rows * cols));
    }

    /* ============================================================
     * Helpers
     * ============================================================ */

    private Integer parsePlateDimensionInput(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            int value = Integer.parseInt(text);
            if (value < 1 || value > MAX_PLATE_DIMENSION) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseOrDefault(String text, int defaultValue) {
        try {
            int value = Integer.parseInt(text);
            if (value <= 0) {
                return defaultValue;
            }
            return Math.min(value, MAX_PLATE_DIMENSION);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void installIntegerOnlyFormatter(TextField field) {
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String next = change.getControlNewText();
            if (next.isEmpty() || next.matches("\\d+")) {
                return change;
            }
            return null;
        };
        field.setTextFormatter(new TextFormatter<>(filter));
    }

    private void installSelectAllOnEntry(TextField field) {
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                Platform.runLater(() -> {
                    if (field.isFocused()) {
                        field.selectAll();
                    }
                });
            }
        });
        field.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                Platform.runLater(() -> {
                    if (field.isFocused()) {
                        field.selectAll();
                    }
                });
            }
        });
    }

    private void installPlateDimensionFormatter(TextField field) {
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String next = change.getControlNewText();
            if (next.isEmpty()) {
                return change;
            }
            if (!next.matches("\\d+")) {
                return null;
            }

            if (new BigInteger(next).compareTo(BigInteger.valueOf(MAX_PLATE_DIMENSION)) > 0) {
                String maximum = Integer.toString(MAX_PLATE_DIMENSION);
                change.setRange(0, change.getControlText().length());
                change.setText(maximum);
                change.setCaretPosition(maximum.length());
                change.setAnchor(maximum.length());
            }
            return change;
        };
        field.setTextFormatter(new TextFormatter<>(filter));
    }
}
