package qupath.ext.shapex.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.ContextMenu;
import javafx.scene.Cursor;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import qupath.ext.shapex.ShapeXResources;
import qupath.ext.shapex.core.AnnotationUtils;
import qupath.ext.shapex.core.PlateLayoutUtils;
import qupath.ext.shapex.core.ProjectAnnotationService;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class PlateAssignmentView extends GridPane {

    /* ============================================================
     * Layout constants
     * ============================================================ */

    private static final double COL0_WIDTH    = 26;
    private static final double HEADER_HEIGHT = 22;
    private static final double CELL_SIDE     = 20;
    private static final double SQUARE_SIDE   = 16;
    private static final double GAP           = 4;
    private static final double PADDING_TOTAL = 16;

    private static final DataFormat DRAG_WELL_FORMAT =
            new DataFormat("application/x-shapex-plate-well");

    /* ============================================================
     * Hover coordination
     * ============================================================ */

    private final Map<String, Pane> wellNodes = new HashMap<>();
    private final Map<String, Label> rowHeaders = new HashMap<>();
    private final Map<Integer, Label> columnHeaders = new HashMap<>();
    private final Map<String, ProjectAnnotationService.SampleTooltipData> sampleTooltipData;
    private final boolean highlightUnusedMode;
    private ContextMenu activeContextMenu;

    public static double preferredWidthForColumns(int cols) {
        return PADDING_TOTAL + COL0_WIDTH + (cols * CELL_SIDE) + (cols * GAP);
    }

    public static double preferredHeightForRows(int rows) {
        return PADDING_TOTAL + HEADER_HEIGHT + (rows * CELL_SIDE) + (rows * GAP);
    }

    public static double columnHeaderCenterOffsetY() {
        return (PADDING_TOTAL / 2.0) + (HEADER_HEIGHT / 2.0);
    }

    public static double lastWellBottomOffsetY(int rows) {
        return preferredHeightForRows(rows) - (PADDING_TOTAL / 2.0);
    }

    /* ============================================================
     * Constructor
     * ============================================================ */

    public PlateAssignmentView(
            int rows,
            int cols,
            Map<String, String> sampleToWell,
            Map<String, Integer> sampleColors,
            Set<String> excludedWells,
            Set<String> negativeControls,
            Set<String> disabledSamples,
            Set<String> immutableWells,
            int sampleColorMode,
            boolean highlightUnusedMode,
            Map<String, ProjectAnnotationService.SampleTooltipData> sampleTooltipData,
            boolean customLayoutEnabled,
            int currentPlateIndex,
            int plateCount,
            Consumer<String> onWellClicked,
            BiConsumer<String, String> onWellSwap,
            BiConsumer<String, Integer> onMoveSampleToPlate,
            Consumer<String> onDisableSampleAtWell,
            Consumer<String> onEnableSampleAtWell,
            Consumer<String> onRowClicked,
            Consumer<Integer> onColumnClicked
    ) {

        this.sampleTooltipData = sampleTooltipData;
        this.highlightUnusedMode = highlightUnusedMode;

        setPadding(new Insets(8));
        setHgap(GAP);
        setVgap(GAP);

        /* Column constraints */
        ColumnConstraints col0 =
                new ColumnConstraints(COL0_WIDTH, COL0_WIDTH, COL0_WIDTH);
        col0.setHalignment(HPos.CENTER);
        getColumnConstraints().add(col0);

        for (int c = 1; c <= cols; c++) {
            ColumnConstraints cc =
                    new ColumnConstraints(CELL_SIDE, CELL_SIDE, CELL_SIDE);
            cc.setHalignment(HPos.CENTER);
            getColumnConstraints().add(cc);
        }

        /* Row constraints */
        RowConstraints header =
                new RowConstraints(HEADER_HEIGHT, HEADER_HEIGHT, HEADER_HEIGHT);
        header.setValignment(VPos.CENTER);
        getRowConstraints().add(header);

        for (int r = 1; r <= rows; r++) {
            RowConstraints rr =
                    new RowConstraints(CELL_SIDE, CELL_SIDE, CELL_SIDE);
            rr.setValignment(VPos.CENTER);
            getRowConstraints().add(rr);
        }

        add(new Label(""), 0, 0);

        /* Column headers */
        for (int c = 1; c <= cols; c++) {

            int colIndex = c;

            Label lbl = new Label(Integer.toString(c));
            lbl.setAlignment(Pos.CENTER);
            lbl.setTextOverrun(OverrunStyle.CLIP);
            lbl.getStyleClass().add("plate-header");
            lbl.setCursor(Cursor.HAND);

            columnHeaders.put(colIndex, lbl);

            lbl.setOnMouseClicked(e ->
                    onColumnClicked.accept(colIndex));

            add(lbl, c, 0);
        }

        /* Rows + wells */
        for (int r = 1; r <= rows; r++) {

            String rowName = PlateLayoutUtils.rowLabel(r - 1);

            Label rowHeaderLabel = new Label(rowName);
            rowHeaderLabel.setAlignment(Pos.CENTER);
            rowHeaderLabel.getStyleClass().add("plate-header");
            rowHeaderLabel.setCursor(Cursor.HAND);

            rowHeaders.put(rowName, rowHeaderLabel);

            rowHeaderLabel.setOnMouseClicked(e ->
                    onRowClicked.accept(rowName));

            add(rowHeaderLabel, 0, r);

            for (int c = 1; c <= cols; c++) {

                String well = rowName + Integer.toString(c);

                Pane cell = createSquare(
                        well,
                        sampleToWell,
                        sampleColors,
                        excludedWells,
                        negativeControls,
                        disabledSamples,
                        sampleColorMode
                );

                wellNodes.put(well, cell);

                cell.setOnMouseEntered(e -> highlightCross(well));
                cell.setOnMouseExited(e -> clearHighlight());

                cell.setCursor(Cursor.HAND);
                cell.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.SECONDARY) {
                        String sampleNameAtWell = null;
                        for (var entry : sampleToWell.entrySet()) {
                            if (well.equals(entry.getValue())) {
                                sampleNameAtWell = entry.getKey();
                                break;
                            }
                        }
                        if (sampleNameAtWell == null) {
                            e.consume();
                            return;
                        }

                        boolean sampleDisabled = disabledSamples.contains(sampleNameAtWell);

                        ContextMenu firstMenu = new ContextMenu();
                        firstMenu.setOnHidden(ev -> {
                            if (activeContextMenu == firstMenu) {
                                activeContextMenu = null;
                            }
                        });

                        if (sampleDisabled) {
                            MenuItem enableSampleItem = new MenuItem("Enable sample");
                            enableSampleItem.setOnAction(ev -> onEnableSampleAtWell.accept(well));
                            firstMenu.getItems().add(enableSampleItem);
                        } else {
                            MenuItem disableSampleItem = new MenuItem("Disable sample");
                            disableSampleItem.setOnAction(ev -> onDisableSampleAtWell.accept(well));

                            firstMenu.getItems().add(disableSampleItem);

                            if (customLayoutEnabled) {
                                MenuItem moveToPlateItem = new MenuItem("Move sample to another plate");
                                moveToPlateItem.setOnAction(ev -> {
                                    ContextMenu plateMenu = new ContextMenu();
                                    plateMenu.setOnHidden(hiddenEvent -> {
                                        if (activeContextMenu == plateMenu) {
                                            activeContextMenu = null;
                                        }
                                    });
                                    List<String> plateNames = new ArrayList<>();
                                    List<Integer> plateIndices = new ArrayList<>();

                                    for (int i = 0; i < plateCount; i++) {
                                        plateNames.add(ShapeXResources.format("plate.name.pretty", i + 1));
                                        plateIndices.add(i);
                                    }

                                    ListView<String> plateList = new ListView<>();
                                    plateList.getItems().setAll(plateNames);
                                    plateList.setPrefWidth(160);
                                    int visibleRows = Math.min(10, Math.max(1, plateCount));
                                    plateList.setPrefHeight(visibleRows * 26.0);

                                    plateList.setOnMouseClicked(listEvent -> {
                                        int selected = plateList.getSelectionModel().getSelectedIndex();
                                        if (selected < 0)
                                            return;
                                        int targetIndex = plateIndices.get(selected);
                                        if (targetIndex == currentPlateIndex)
                                            return;
                                        onMoveSampleToPlate.accept(well, targetIndex);
                                        plateMenu.hide();
                                    });

                                    CustomMenuItem listItem = new CustomMenuItem(plateList, false);
                                    plateMenu.getItems().add(listItem);
                                    showSingleContextMenu(plateMenu, cell, e.getScreenX(), e.getScreenY());
                                });
                                firstMenu.getItems().add(0, moveToPlateItem);
                            }
                        }

                        showSingleContextMenu(firstMenu, cell, e.getScreenX(), e.getScreenY());
                        e.consume();
                        return;
                    }
                    if (e.getButton() == MouseButton.PRIMARY) {
                        onWellClicked.accept(well);
                    }
                });

                if (customLayoutEnabled && !immutableWells.contains(well)) {
                    cell.setOnDragDetected(e -> {
                        Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                        ClipboardContent content = new ClipboardContent();
                        content.put(DRAG_WELL_FORMAT, well);
                        db.setContent(content);

                        // Use the actual well look as drag preview (avoid default file icon).
                        SnapshotParameters params = new SnapshotParameters();
                        params.setFill(Color.TRANSPARENT);
                        double oldOpacity = cell.getOpacity();
                        boolean hadHover = cell.getStyleClass().contains("plate-well-hover");
                        boolean hadCross = cell.getStyleClass().contains("plate-well-cross");
                        cell.getStyleClass().remove("plate-well-hover");
                        cell.getStyleClass().remove("plate-well-cross");
                        cell.setOpacity(0.82);
                        WritableImage dragImage = cell.snapshot(params, null);
                        if (hadHover) {
                            cell.getStyleClass().add("plate-well-hover");
                        }
                        if (hadCross) {
                            cell.getStyleClass().add("plate-well-cross");
                        }
                        cell.setOpacity(oldOpacity);
                        db.setDragView(dragImage, SQUARE_SIDE / 2.0, SQUARE_SIDE / 2.0);

                        e.consume();
                    });

                    cell.setOnDragDone(e -> {
                        clearHighlight();
                        e.consume();
                    });

                    cell.setOnDragOver(e -> {
                        Dragboard db = e.getDragboard();
                        if (!e.getGestureSource().equals(cell)
                                && db.hasContent(DRAG_WELL_FORMAT)
                                && !immutableWells.contains(well)) {
                            e.acceptTransferModes(TransferMode.MOVE);
                            highlightCross(well);
                        }
                        e.consume();
                    });

                    cell.setOnDragDropped(e -> {
                        Dragboard db = e.getDragboard();
                        boolean success = false;
                        if (db.hasContent(DRAG_WELL_FORMAT)) {
                            String sourceWell = (String) db.getContent(DRAG_WELL_FORMAT);
                            if (sourceWell != null
                                    && !sourceWell.equals(well)
                                    && !immutableWells.contains(sourceWell)) {
                                onWellSwap.accept(sourceWell, well);
                                success = true;
                            }
                        }
                        e.setDropCompleted(success);
                        clearHighlight();
                        e.consume();
                    });
                }

                add(cell, c, r);

                String tooltipText;

                if (excludedWells.contains(well)) {

                    tooltipText = ShapeXResources.format("plate.tooltip.excluded", well);

                }
                else if (negativeControls.contains(well)) {

                    tooltipText = ShapeXResources.format("plate.tooltip.negative_control", well);

                }
                else {

                    String sampleName = null;

                    for (var entry : sampleToWell.entrySet()) {
                        if (well.equals(entry.getValue())) {
                            sampleName = entry.getKey();
                            break;
                        }
                    }

                    if (sampleName != null) {
                        tooltipText = buildSampleTooltipText(sampleName);
                    } else {

                        tooltipText =
                                ShapeXResources.format("plate.tooltip.unused", well);
                    }
                }

                Tooltip.install(cell, new Tooltip(tooltipText));
            }
        }
    }

    /* ============================================================
     * Hover logic
     * ============================================================ */

    private void highlightCross(String well) {
        clearHighlight();

        String row = getWellRow(well);
        int col = getWellCol(well);

        for (var entry : wellNodes.entrySet()) {

            String w = entry.getKey();
            Pane p = entry.getValue();

            // Skip excluded wells
            if (p.getChildren().stream().anyMatch(n -> n instanceof Line))
                continue;

            boolean sameRow = getWellRow(w).equals(row);
            boolean sameCol = getWellCol(w) == col;

            if (sameRow || sameCol) {
                p.getStyleClass().add("plate-well-cross");
            }
        }

        Pane hovered = wellNodes.get(well);
        if (hovered != null) {
            hovered.getStyleClass().add("plate-well-hover");
        }

        Label rowHeader = rowHeaders.get(row);
        if (rowHeader != null)
            rowHeader.getStyleClass().add("plate-header-hover");

        Label colHeader = columnHeaders.get(col);
        if (colHeader != null)
            colHeader.getStyleClass().add("plate-header-hover");
    }

    private void clearHighlight() {

        wellNodes.values().forEach(p -> {
            p.getStyleClass().remove("plate-well-hover");
            p.getStyleClass().remove("plate-well-cross");
        });

        rowHeaders.values().forEach(l ->
                l.getStyleClass().remove("plate-header-hover"));

        columnHeaders.values().forEach(l ->
                l.getStyleClass().remove("plate-header-hover"));
    }

    private void showSingleContextMenu(
            ContextMenu menu,
            Pane owner,
            double screenX,
            double screenY
    ) {
        if (activeContextMenu != null) {
            activeContextMenu.hide();
        }
        activeContextMenu = menu;
        menu.show(owner, screenX, screenY);
    }

    private String buildSampleTooltipText(String sampleName) {
        ProjectAnnotationService.SampleTooltipData tooltipData =
                this.sampleTooltipData != null ? this.sampleTooltipData.get(sampleName) : null;

        String name = tooltipData != null && tooltipData.name() != null && !tooltipData.name().isBlank()
                ? tooltipData.name()
                : ShapeXResources.get("annotation.default_name");

        String className = tooltipData != null && tooltipData.className() != null && !tooltipData.className().isBlank()
                ? tooltipData.className()
                : fallbackTooltipClassName(sampleName);

        int annotationCount = tooltipData != null && tooltipData.annotationCount() > 0
                ? tooltipData.annotationCount()
                : 1;

        String detailLabel = annotationCount == 1
                ? ShapeXResources.get("plate.tooltip.object_id_label")
                : ShapeXResources.get("plate.tooltip.slides_label");

        String detailValue;
        if (annotationCount == 1) {
            detailValue = tooltipData != null && tooltipData.objectId() != null && !tooltipData.objectId().isBlank()
                    ? tooltipData.objectId()
                    : ShapeXResources.get("common.unknown.capitalized");
        } else {
            List<String> slides = tooltipData != null ? tooltipData.slides() : List.of();
            detailValue = formatSlideTooltipValue(slides);
        }

        return ShapeXResources.format(
                "plate.tooltip.sample_details",
                name,
                className,
                annotationCount,
                detailLabel,
                detailValue
        );
    }

    private String fallbackTooltipClassName(String sampleName) {
        String displaySampleName = AnnotationUtils.toDisplaySampleName(sampleName);
        if (displaySampleName == null || displaySampleName.isBlank()) {
            return ShapeXResources.get("annotation.unclassed");
        }

        if (displaySampleName.equals(ShapeXResources.get("annotation.unclassified.prefix"))) {
            return ShapeXResources.get("annotation.unclassed");
        }

        return displaySampleName;
    }

    private String formatSlideTooltipValue(List<String> slides) {
        if (slides == null || slides.isEmpty()) {
            return ShapeXResources.get("common.unknown.capitalized");
        }

        if (slides.size() == 1) {
            return slides.get(0);
        }

        return "\n" + slides.stream()
                .map(slide -> "   - " + slide)
                .collect(Collectors.joining("\n"));
    }

    /* ============================================================
     * Cell rendering
     * ============================================================ */

    private Pane createSquare(
            String well,
            Map<String, String> sampleToWell,
            Map<String, Integer> sampleColors,
            Set<String> excludedWells,
            Set<String> negativeControls,
            Set<String> disabledSamples,
            int sampleColorMode
    ) {

        Pane p = new Pane();
        p.setMinSize(SQUARE_SIDE, SQUARE_SIDE);
        p.setPrefSize(SQUARE_SIDE, SQUARE_SIDE);
        p.setMaxSize(SQUARE_SIDE, SQUARE_SIDE);

        /* ---------------------------
         * Excluded wells
         * --------------------------- */
        if (excludedWells.contains(well)) {
            p.setStyle("-fx-background-color: #c0c0c0; -fx-border-color: -fx-box-border;");
            Line diag = new Line(0, 0, SQUARE_SIDE, SQUARE_SIDE);
            diag.setStroke(Color.RED);
            diag.setStrokeWidth(1.5);
            p.getChildren().add(diag);
            return p;
        }

        /* ---------------------------
         * Negative controls
         * --------------------------- */
        if (negativeControls.contains(well)) {

            p.setStyle("-fx-background-color: #c0c0c0; -fx-border-color: -fx-box-border;");

            Circle circle = new Circle(
                    SQUARE_SIDE / 2.0,
                    SQUARE_SIDE / 2.0,
                    3,
                    Color.RED
            );

            p.getChildren().add(circle);
            return p;
        }

        /* ---------------------------
         * Sample wells
         * --------------------------- */
        for (var entry : sampleToWell.entrySet()) {
            if (well.equals(entry.getValue())) {

                boolean disabledSample = disabledSamples.contains(entry.getKey());

                if (sampleColorMode == 1) {
                    p.setStyle("-fx-background-color: "
                            + (disabledSample ? "-shapex-primary-alpha-high" : "-shapex-primary-color")
                            + "; -fx-border-color: -fx-box-border;");
                } else if (sampleColorMode == 2) {
                    p.setStyle("-fx-background-color: #c0c0c0; -fx-border-color: -fx-box-border;");

                    Polygon triangle = SampleTriangleFactory.createSampleTriangle(SQUARE_SIDE);
                    if (disabledSample) {
                        triangle.setOpacity(0.4);
                    }
                    p.getChildren().add(triangle);
                } else {
                    Integer argb = sampleColors.get(entry.getKey());
                    if (argb == null) {
                        p.setStyle("-fx-background-color: "
                                + (disabledSample ? "-shapex-primary-alpha-high" : "-shapex-primary-color")
                                + "; -fx-border-color: -fx-box-border;");
                    } else {
                        Color fxColor = argbToColor(argb);
                        if (disabledSample) {
                            fxColor = Color.color(
                                    fxColor.getRed(),
                                    fxColor.getGreen(),
                                    fxColor.getBlue(),
                                    0.35
                            );
                        }

                        p.setStyle(String.format(
                                "-fx-background-color: %s; -fx-border-color: -fx-box-border;",
                                toCssColor(fxColor)
                        ));
                    }
                }

                if (disabledSample) {
                    Line diag = new Line(0, 0, SQUARE_SIDE, SQUARE_SIDE);
                    diag.setStroke(Color.RED);
                    diag.setStrokeWidth(1.5);
                    p.getChildren().add(diag);
                }

                return p;
            }
        }

        /* ---------------------------
         * Unused wells
         * --------------------------- */
        if (highlightUnusedMode) {

            p.setStyle("-fx-background-color: #c0c0c0; -fx-border-color: -fx-box-border;");

            Circle circle = new Circle(
                    SQUARE_SIDE / 2.0,
                    SQUARE_SIDE / 2.0,
                    4
            );

            circle.setFill(null);
            circle.setStrokeWidth(1.5);
            circle.getStyleClass().add("shapex-secondary-stroke");

            p.getChildren().add(circle);

        } else {

            p.setStyle("-fx-background-color: -shapex-secondary-color; -fx-border-color: -fx-box-border;");
        }

        return p;
    }

    /* ============================================================
     * Color helpers
     * ============================================================ */

    private static Color argbToColor(int argb) {
        int a = (argb >> 24) & 0xff;
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        return Color.rgb(r, g, b, a / 255.0);
    }

    private static String toCssColor(Color c) {
        return String.format(
                "rgba(%d,%d,%d,%.3f)",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255),
                c.getOpacity()
        );
    }

    private static String getWellRow(String well) {
        int i = 0;
        while (i < well.length() && Character.isLetter(well.charAt(i))) {
            i++;
        }
        return i > 0 ? well.substring(0, i) : "";
    }

    private static int getWellCol(String well) {
        int i = 0;
        while (i < well.length() && Character.isLetter(well.charAt(i))) {
            i++;
        }
        return Integer.parseInt(well.substring(i));
    }

}
