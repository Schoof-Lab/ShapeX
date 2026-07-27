package qupath.ext.shapex.ui;

import qupath.ext.shapex.ShapeXResources;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import qupath.ext.shapex.core.IDotRow;
import qupath.ext.shapex.core.Processor;
import qupath.ext.shapex.core.ShapeXColors;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

public class Controller {

    private static final String FXML_PATH = "/qupath/ext/shapex/ui/shapex_control.fxml";
    private static final URI USER_GUIDE_URI = URI.create(
            ShapeXResources.get("window.user_guide.url")
    );
    private static final double FALLBACK_TITLE_PANE_HEADER_HEIGHT = 24.0;
    private static final double STANDARD_WINDOW_SIZE_TOLERANCE = 2.0;
    private static final Pattern THEME_STYLE_PATTERN = Pattern.compile(
            "\\s*-shapex-(?:primary|secondary)[a-zA-Z0-9-]*\\s*:\\s*[^;]+;?"
    );

    private Color themePrimaryColor = Color.web(ShapeXColors.PRIMARY_HEX);
    private Color themeSecondaryColor = Color.web(ShapeXColors.SECONDARY_HEX);

    private Path baseOutputDirectory;
    private boolean updatingOutputDirectoryField = false;
    private boolean applyingProgrammaticWindowSize = false;
    private boolean automaticWindowResizePending = false;
    private Runnable pendingAutomaticWindowResizeTask;
    private boolean windowSizingInitialized = false;
    private boolean userManagedWindowSize = false;
    private double automaticWindowWidth = -1.0;
    private double automaticWindowHeight = -1.0;
    private double cachedWindowDecorationWidth = -1.0;
    private double cachedWindowDecorationHeight = -1.0;
    private Stage windowSizingStage;

    private PlateDesignManager plateDesignManager;
    private TessellatorManager tessellatorManager;
    private IdotTableManager idotTableManager;
    private OutputWorkflow outputWorkflow;

    public record ControllerRoot(Parent root, Controller controller) {}

    public static ControllerRoot createInstanceWithController() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    Controller.class.getResource(FXML_PATH),
                    ShapeXResources.bundle()
            );

            Controller controller = new Controller();
            loader.setController(controller);

            Parent root = loader.load();
            return new ControllerRoot(root, controller);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ShapeX UI", e);
        }
    }

    /* ============================================================
     * FXML fields
     * ============================================================ */

    @FXML private TextField outputDirectoryField;
    @FXML private Button browseProjectButton;
    @FXML private Button windowSizeToggleButton;

    @FXML private Accordion settingsAccordion;
    @FXML private Accordion outputsAccordion;
    @FXML private TitledPane platesPane;

    @FXML private ToggleButton flipImageVerticalButton;
    @FXML private ToggleButton flipImageHorizontalButton;
    @FXML private Button generateFlippedImageButton;

    @FXML private TextField plateRows;
    @FXML private TextField plateCols;
    @FXML private Label wellCountLabel;
    @FXML private ToggleGroup plateFormatToggleGroup;
    @FXML private CheckBox allAnnotationsCheckBox;
    @FXML private CheckBox poolClassCheckBox;
    @FXML private RadioButton customPlateRadio;
    @FXML private RadioButton wells96PlateRadio;
    @FXML private RadioButton wells384PlateRadio;
    @FXML private RadioButton wells1536PlateRadio;
    @FXML private GridPane plateDesignerGrid;
    @FXML private HBox plateControlsRow;
    @FXML private VBox plateFormatMenu;
    @FXML private VBox plateLegendMenu;

    @FXML private Button shuffleButton;
    @FXML private TextField seedField;
    @FXML private TextField negativeControlsField;
    @FXML private CheckBox randomizeCheckBox;
    @FXML private CheckBox customLayoutCheckBox;
    @FXML private CheckBox mapImageToPlateCheckBox;
    @FXML private CheckBox uniquePlatesCheckBox;

    @FXML private ScrollPane plateScrollPane;

    @FXML private Button prevPlateButton;
    @FXML private Button nextPlateButton;
    @FXML private Button removePlateButton;
    @FXML private Button resetPlateCountButton;
    @FXML private Button addPlateButton;
    @FXML private Label plateIndexLabel;

    @FXML private Pane legendSample;
    @FXML private Pane legendUnused;
    @FXML private Pane legendNegative;
    @FXML private Pane legendExcluded;

    @FXML private CheckBox xmlLMDCheckBox;

    @FXML private Label legendSamplesCount;
    @FXML private Label legendUnusedCount;
    @FXML private Label legendExcludedCount;

    @FXML private ScrollPane slideScrollPane;
    @FXML private VBox slideListBox;
    @FXML private ListView<PathObject> tessellatorClassListView;

    @FXML private TableView<IDotRow> idotTable;
    @FXML private TableColumn<IDotRow, String> idotNameColumn;
    @FXML private TableColumn<IDotRow, Double> idotVolumeColumn;
    @FXML private TableColumn<IDotRow, String> idotLiquidColumn;

    @FXML private Button idotAddRowButton;
    @FXML private Button idotRemoveRowButton;
    @FXML private CheckBox IDOTCheckBox;

    @FXML private CheckBox overviewCheckBox;
    @FXML private CheckBox anndataCheckBox;
    @FXML private RadioButton numbersRadio;
    @FXML private RadioButton openDocRadio;
    @FXML private RadioButton csvRadio;
    @FXML private RadioButton tsvRadio;
    @FXML private RadioButton txtRadio;

    @FXML private ToggleGroup tessellatorShapeToggleGroup;
    @FXML private RadioButton tessellatorTriangleRadio;
    @FXML private RadioButton tessellatorSquareRadio;
    @FXML private RadioButton tessellatorHexagonRadio;
    @FXML private Pane tessellatorSlidePreviewPane;
    @FXML private Pane tessellatorPreviewPane;
    @FXML private ToggleGroup tessellatorPartialModeToggleGroup;
    @FXML private RadioButton tessellatorFitShapeRadio;
    @FXML private RadioButton tessellatorRemovePartialRadio;
    @FXML private RadioButton tessellatorAddPartialRadio;
    @FXML private CheckBox tessellatorHideGridCheckBox;
    @FXML private TextField tessellatorTileAreaField;
    @FXML private CheckBox tessellatorShowImageCheckBox;
    @FXML private CheckBox tessellatorAssignClassesCheckBox;

    /* ============================================================
     * Lifecycle
     * ============================================================ */

    @FXML
    private void initialize() {
        QuPathGUI gui = QuPathGUI.getInstance();

        gui.projectProperty().addListener((obs, oldProject, newProject) ->
                Platform.runLater(() -> handleProjectChanged(newProject))
        );
        gui.imageDataProperty().addListener((obs, oldImage, newImage) ->
                Platform.runLater(this::handleImageChanged)
        );

        setupOutputDirectoryField();
        updateBaseOutputDirectory(gui.getProject());

        outputsAccordion.setExpandedPane(null);
        settingsAccordion.setExpandedPane(platesPane);
        setupAccordionAutoResize();

        plateDesignManager = new PlateDesignManager(
                settingsAccordion,
                plateFormatToggleGroup,
                customPlateRadio,
                wells96PlateRadio,
                wells384PlateRadio,
                wells1536PlateRadio,
                plateRows,
                plateCols,
                wellCountLabel,
                allAnnotationsCheckBox,
                poolClassCheckBox,
                plateScrollPane,
                plateDesignerGrid,
                plateControlsRow,
                plateFormatMenu,
                plateLegendMenu,
                seedField,
                negativeControlsField,
                randomizeCheckBox,
                customLayoutCheckBox,
                mapImageToPlateCheckBox,
                uniquePlatesCheckBox,
                shuffleButton,
                prevPlateButton,
                nextPlateButton,
                removePlateButton,
                resetPlateCountButton,
                addPlateButton,
                plateIndexLabel,
                legendSample,
                legendUnused,
                legendNegative,
                legendExcluded,
                legendSamplesCount,
                legendUnusedCount,
                legendExcludedCount,
                slideScrollPane,
                slideListBox,
                () -> {
                    if (tessellatorManager != null) {
                        tessellatorManager.populateTessellatorClassList();
                    }
                }
        );

        tessellatorManager = new TessellatorManager(
                settingsAccordion,
                flipImageVerticalButton,
                flipImageHorizontalButton,
                generateFlippedImageButton,
                tessellatorShapeToggleGroup,
                tessellatorTriangleRadio,
                tessellatorSquareRadio,
                tessellatorHexagonRadio,
                tessellatorSlidePreviewPane,
                tessellatorPreviewPane,
                tessellatorPartialModeToggleGroup,
                tessellatorFitShapeRadio,
                tessellatorRemovePartialRadio,
                tessellatorAddPartialRadio,
                tessellatorHideGridCheckBox,
                tessellatorTileAreaField,
                tessellatorShowImageCheckBox,
                tessellatorAssignClassesCheckBox,
                tessellatorClassListView,
                suspended -> {
                    if (plateDesignManager != null) {
                        plateDesignManager.setAnnotationWatchdogSuspended(suspended);
                    }
                }
        );

        idotTableManager = new IdotTableManager(
                idotTable,
                idotNameColumn,
                idotVolumeColumn,
                idotLiquidColumn,
                idotAddRowButton,
                idotRemoveRowButton,
                IDOTCheckBox
        );

        outputWorkflow = new OutputWorkflow(
                browseProjectButton,
                xmlLMDCheckBox,
                IDOTCheckBox,
                overviewCheckBox,
                anndataCheckBox,
                numbersRadio,
                openDocRadio,
                csvRadio,
                tsvRadio,
                txtRadio,
                plateDesignManager,
                idotTableManager
        );

        plateDesignManager.initialize();
        tessellatorManager.initialize();
        idotTableManager.initialize();
        tessellatorManager.handleProjectChanged();
    }

    private void handleProjectChanged(Project<?> project) {
        updateBaseOutputDirectory(project);

        if (plateDesignManager != null) {
            plateDesignManager.handleProjectChanged(project);
        }
        if (tessellatorManager != null) {
            tessellatorManager.handleProjectChanged();
        }
    }

    private void handleImageChanged() {
        if (plateDesignManager != null) {
            plateDesignManager.handleImageChanged();
        }
        if (tessellatorManager != null) {
            tessellatorManager.handleImageChanged();
        }
    }

    private void updateBaseOutputDirectory(Project<?> project) {
        if (project != null) {
            baseOutputDirectory = Processor.getDefaultOutputDirectory(project);
            setOutputDirectoryFieldText(baseOutputDirectory, ShapeXResources.get("output.directory.prompt"));
        } else {
            baseOutputDirectory = null;
            setOutputDirectoryFieldText(null, ShapeXResources.get("project.none.open"));
        }
    }

    private void setupOutputDirectoryField() {
        if (outputDirectoryField == null) {
            return;
        }
        outputDirectoryField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (updatingOutputDirectoryField) {
                return;
            }
            baseOutputDirectory = parseOutputDirectoryPath(newValue);
            outputDirectoryField.setPromptText(ShapeXResources.get("output.directory.prompt"));
        });
    }

    private void setOutputDirectoryFieldText(Path path, String emptyPrompt) {
        if (outputDirectoryField == null) {
            return;
        }
        updatingOutputDirectoryField = true;
        try {
            outputDirectoryField.setText(path == null ? "" : path.toString());
            outputDirectoryField.setPromptText(emptyPrompt);
        } finally {
            updatingOutputDirectoryField = false;
        }
    }

    private Path parseOutputDirectoryPath(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            if (value.equals("~")) {
                return Path.of(System.getProperty("user.home"));
            }
            if (value.startsWith("~/") || value.startsWith("~\\")) {
                return Path.of(System.getProperty("user.home")).resolve(value.substring(2));
            }
            return Path.of(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /* ============================================================
     * Actions
     * ============================================================ */

    @FXML
    private void handleBrowseProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(ShapeXResources.get("output.directory.select.title"));

        baseOutputDirectory = parseOutputDirectoryPath(outputDirectoryField == null
                ? null
                : outputDirectoryField.getText());
        Path initialDirectory = getExistingDirectoryOrParent(baseOutputDirectory);
        if (initialDirectory != null) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }

        Window window = browseProjectButton.getScene().getWindow();
        var directory = chooser.showDialog(window);

        if (directory != null) {
            baseOutputDirectory = directory.toPath();
            setOutputDirectoryFieldText(baseOutputDirectory, ShapeXResources.get("output.directory.prompt"));
        }
    }

    private Path getExistingDirectoryOrParent(Path path) {
        Path current = path;
        while (current != null) {
            try {
                if (Files.isDirectory(current)) {
                    return current;
                }
            } catch (RuntimeException ignored) {
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    @FXML
    private void handleRun(ActionEvent event) {
        baseOutputDirectory = parseOutputDirectoryPath(outputDirectoryField == null
                ? null
                : outputDirectoryField.getText());
        outputWorkflow.handleRun(baseOutputDirectory);
    }

    @FXML
    private void handleGenerateFlippedImage(ActionEvent event) {
        tessellatorManager.handleGenerateFlippedImage();
    }

    @FXML
    private void handleTessellateAnnotations(ActionEvent event) {
        tessellatorManager.handleTessellateAnnotations();
    }

    /* ============================================================
     * Layout glue
     * ============================================================ */

    private void setupAccordionAutoResize() {
        Runnable resizeTask = () -> applyAutomaticWindowSize(false);

        registerAccordionAutoResize(settingsAccordion, resizeTask);
        scheduleAutomaticWindowResize(() -> applyAutomaticWindowSize(true));
    }

    @FXML
    private void handleToggleWindowSize(ActionEvent event) {
        toggleWindowSize();
    }

    @FXML
    private void handleOpenUserGuide(ActionEvent event) {
        if (!GuiTools.browseURI(USER_GUIDE_URI)) {
            ControllerDialogs.showError(
                    getEventSourceNode(event),
                    ShapeXResources.format("window.user_guide.open.error", USER_GUIDE_URI)
            );
        }
    }

    @FXML
    private void handleShowThemeColorPicker(ActionEvent event) {
        openThemeColorPicker(getEventSourceNode(event));
    }

    private Node getEventSourceNode(ActionEvent event) {
        return event != null && event.getSource() instanceof Node node
                ? node
                : windowSizeToggleButton;
    }

    private void openThemeColorPicker(Node ownerNode) {
        Window owner = ControllerDialogs.resolveOwner(ownerNode);
        if (owner == null) {
            ControllerDialogs.showError(
                    ownerNode,
                    ShapeXResources.format("window.theme.color_picker.error", ShapeXResources.get("error.unknown"))
            );
            return;
        }

        try {
            Class<?> dialogClass = Class.forName("com.sun.javafx.scene.control.CustomColorDialog");
            Object dialog = dialogClass.getConstructor(Window.class).newInstance(owner);
            Color primaryColor = themePrimaryColor;
            Runnable noOp = () -> {
            };

            dialogClass.getMethod("setCurrentColor", Color.class).invoke(dialog, primaryColor);
            dialogClass.getMethod("setCustomColor", Color.class).invoke(dialog, primaryColor);
            dialogClass.getMethod("setOnSave", Runnable.class).invoke(dialog, noOp);
            dialogClass.getMethod("setOnCancel", Runnable.class).invoke(dialog, noOp);
            installThemeTargetControls(dialogClass, dialog, ownerNode);
            dialogClass.getMethod("show").invoke(dialog);
        } catch (ReflectiveOperationException | RuntimeException e) {
            showThemeColorPickerError(ownerNode, e);
        }
    }

    private void installThemeTargetControls(Class<?> dialogClass, Object dialog, Node ownerNode) {
        if (!(dialog instanceof HBox dialogRoot)) {
            return;
        }

        VBox controlsPane = findThemeDialogControlsPane(dialogRoot);
        if (controlsPane == null || findDescendantById(controlsPane, "shapex-theme-target-controls") != null) {
            return;
        }

        RadioButton primaryRadio = new RadioButton(ShapeXResources.get("window.theme.primary"));
        RadioButton secondaryRadio = new RadioButton(ShapeXResources.get("window.theme.secondary"));
        ToggleGroup targetGroup = new ToggleGroup();
        primaryRadio.setToggleGroup(targetGroup);
        secondaryRadio.setToggleGroup(targetGroup);
        primaryRadio.setSelected(true);

        HBox targetControls = new HBox(12.0, primaryRadio, secondaryRadio);
        targetControls.setId("shapex-theme-target-controls");
        targetControls.getStyleClass().add("shapex-theme-target-controls");
        targetControls.setAlignment(Pos.CENTER_LEFT);

        targetGroup.selectedToggleProperty().addListener((obs, oldSelection, newSelection) -> {
            Color color = newSelection == secondaryRadio
                    ? themeSecondaryColor
                    : themePrimaryColor;
            setThemeDialogColor(dialogClass, dialog, color, ownerNode);
        });

        Runnable resetAction = () -> {
            resetThemeColors(ownerNode);
            Color selectedColor = targetGroup.getSelectedToggle() == secondaryRadio
                    ? themeSecondaryColor
                    : themePrimaryColor;
            setThemeDialogColor(dialogClass, dialog, selectedColor, ownerNode);
        };

        installLiveThemeColorUpdater(dialogClass, dialog, targetGroup, secondaryRadio, ownerNode);
        configureThemeDialogButtons(controlsPane, targetControls, resetAction);
    }

    private VBox findThemeDialogControlsPane(HBox dialogRoot) {
        for (Node child : dialogRoot.getChildren()) {
            if (child instanceof VBox vBox
                    && findDescendantById(vBox, "settings-pane") != null
                    && findDescendantById(vBox, "buttons-hbox") != null) {
                return vBox;
            }
        }
        return null;
    }

    private Node findDescendantById(Parent parent, String id) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof Parent childParent) {
                Node descendant = findDescendantById(childParent, id);
                if (descendant != null) {
                    return descendant;
                }
            }
        }
        return null;
    }

    private void setThemeDialogColor(Class<?> dialogClass, Object dialog, Color color, Node ownerNode) {
        try {
            Method setCurrentColor = dialogClass.getMethod("setCurrentColor", Color.class);
            Method setCustomColor = dialogClass.getMethod("setCustomColor", Color.class);
            setCurrentColor.invoke(dialog, color);
            setCustomColor.invoke(dialog, color);
            setThemeDialogOpacity(dialog, color.getOpacity());
        } catch (ReflectiveOperationException | RuntimeException e) {
            showThemeColorPickerError(ownerNode, e);
        }
    }

    private void setThemeDialogOpacity(Object dialog, double opacity) {
        if (!(dialog instanceof Parent parent)) {
            return;
        }
        Node settingsPaneNode = findDescendantById(parent, "settings-pane");
        if (!(settingsPaneNode instanceof GridPane settingsPane)) {
            return;
        }

        double opacityPercent = clamp01(opacity) * 100.0;
        for (Node child : settingsPane.getChildren()) {
            Integer rowIndex = GridPane.getRowIndex(child);
            if (rowIndex != null && rowIndex == 5 && child instanceof Slider slider) {
                slider.setValue(opacityPercent);
                return;
            }
        }
    }

    private void installLiveThemeColorUpdater(
            Class<?> dialogClass,
            Object dialog,
            ToggleGroup targetGroup,
            RadioButton secondaryRadio,
            Node ownerNode
    ) {
        try {
            Object property = dialogClass.getMethod("customColorProperty").invoke(dialog);
            if (property instanceof ObjectProperty<?> customColorProperty) {
                customColorProperty.addListener((obs, oldColor, newColor) -> {
                    if (newColor instanceof Color color) {
                        applySelectedThemeColor(targetGroup, secondaryRadio, color, ownerNode);
                    }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            showThemeColorPickerError(ownerNode, e);
        }
    }

    private void configureThemeDialogButtons(VBox controlsPane, Node targetControls, Runnable resetAction) {
        Node buttonBoxNode = findDescendantById(controlsPane, "buttons-hbox");
        if (!(buttonBoxNode instanceof HBox buttonBox)) {
            return;
        }

        Button saveButton = null;
        Button useButton = null;
        Button okButton = null;

        for (Node child : buttonBox.getChildren()) {
            if (child instanceof Button button) {
                if (button.isDefaultButton()) {
                    saveButton = button;
                } else if (!button.isCancelButton()) {
                    useButton = button;
                } else {
                    okButton = button;
                }
            }
        }

        if (saveButton != null) {
            buttonBox.getChildren().remove(saveButton);
        }

        if (useButton != null) {
            buttonBox.getChildren().remove(useButton);
        }

        if (okButton != null) {
            okButton.setText(ShapeXResources.get("window.theme.ok"));
            okButton.setCancelButton(false);
            okButton.setDefaultButton(true);
            Button closeButton = okButton;
            okButton.setOnAction(event -> hideThemeDialog(closeButton));
        }

        Button resetButton = new Button(ShapeXResources.get("window.theme.reset"));
        resetButton.setOnAction(event -> resetAction.run());

        if (targetControls != null) {
            buttonBox.getChildren().add(0, targetControls);
        }
        buttonBox.getChildren().add(targetControls == null ? 0 : 1, resetButton);
    }

    private void hideThemeDialog(Node node) {
        if (node.getScene() != null && node.getScene().getWindow() != null) {
            node.getScene().getWindow().hide();
        }
    }

    private void applySelectedThemeColor(
            ToggleGroup targetGroup,
            RadioButton secondaryRadio,
            Color color,
            Node ownerNode
    ) {
        if (color == null) {
            return;
        }
        if (targetGroup.getSelectedToggle() == secondaryRadio) {
            themeSecondaryColor = color;
        } else {
            themePrimaryColor = color;
        }
        applyThemeColors(ownerNode);
    }

    private void resetThemeColors(Node ownerNode) {
        themePrimaryColor = Color.web(ShapeXColors.PRIMARY_HEX);
        themeSecondaryColor = Color.web(ShapeXColors.SECONDARY_HEX);
        applyThemeColors(ownerNode);
    }

    private void applyThemeColors(Node ownerNode) {
        updateShapeXThemeColors();
        if (ownerNode == null || ownerNode.getScene() == null) {
            return;
        }
        Parent root = ownerNode.getScene().getRoot();
        if (root == null) {
            return;
        }

        String existingStyle = root.getStyle();
        String baseStyle = existingStyle == null
                ? ""
                : THEME_STYLE_PATTERN.matcher(existingStyle).replaceAll("").trim();
        String themeStyle = createThemeStyle(themePrimaryColor, themeSecondaryColor);
        root.setStyle(baseStyle.isBlank() ? themeStyle : ensureTrailingSemicolon(baseStyle) + " " + themeStyle);
    }

    private void updateShapeXThemeColors() {
        ShapeXColors.setCurrentThemeColors(
                colorComponentToInt(themePrimaryColor.getRed()),
                colorComponentToInt(themePrimaryColor.getGreen()),
                colorComponentToInt(themePrimaryColor.getBlue()),
                colorComponentToInt(themeSecondaryColor.getRed()),
                colorComponentToInt(themeSecondaryColor.getGreen()),
                colorComponentToInt(themeSecondaryColor.getBlue())
        );
    }

    private static String createThemeStyle(Color primaryColor, Color secondaryColor) {
        StringBuilder style = new StringBuilder(640);
        appendColorDeclarations(style, "primary", primaryColor, new int[] {16, 18, 28, 34, 55, 85, 86});
        appendAlphaAliases(style, "primary", primaryColor);
        appendColorDeclaration(style, "primary-hover-color", lighten(primaryColor, 0.16));
        appendColorDeclaration(style, "primary-pressed-color", darken(primaryColor, 0.18));
        appendColorDeclaration(style, "primary-stroke-color", darken(primaryColor, 0.25));
        appendColorDeclarations(style, "secondary", secondaryColor, new int[] {16, 20, 22, 55, 65, 86});
        appendAlphaAliases(style, "secondary", secondaryColor);
        appendColorDeclaration(style, "secondary-hover-color", lighten(secondaryColor, 0.16));
        appendColorDeclaration(style, "secondary-pressed-color", darken(secondaryColor, 0.18));
        return style.toString().trim();
    }

    private static void appendColorDeclarations(StringBuilder style, String name, Color color, int[] alphaPercentages) {
        appendColorDeclaration(style, name + "-color", color);
        for (int alphaPercentage : alphaPercentages) {
            appendColorDeclaration(
                    style,
                    name + "-alpha-" + alphaPercentage,
                    colorWithOpacity(color, alphaPercentage / 100.0)
            );
        }
    }

    private static void appendAlphaAliases(StringBuilder style, String name, Color color) {
        appendColorDeclaration(style, name + "-alpha-high", colorWithOpacity(color, 0.20));
        appendColorDeclaration(style, name + "-alpha-med", colorWithOpacity(color, 0.60));
        appendColorDeclaration(style, name + "-alpha-low", colorWithOpacity(color, 0.85));
    }

    private static void appendColorDeclaration(StringBuilder style, String name, Color color) {
        style.append("-shapex-")
                .append(name)
                .append(": ")
                .append(toCssColor(color))
                .append("; ");
    }

    private static Color lighten(Color color, double amount) {
        return mix(color, Color.WHITE, amount);
    }

    private static Color darken(Color color, double amount) {
        return mix(color, Color.BLACK, amount);
    }

    private static Color mix(Color color, Color target, double amount) {
        double clampedAmount = clamp01(amount);
        return new Color(
                color.getRed() + (target.getRed() - color.getRed()) * clampedAmount,
                color.getGreen() + (target.getGreen() - color.getGreen()) * clampedAmount,
                color.getBlue() + (target.getBlue() - color.getBlue()) * clampedAmount,
                color.getOpacity()
        );
    }

    private static Color colorWithOpacity(Color color, double opacity) {
        return new Color(
                color.getRed(),
                color.getGreen(),
                color.getBlue(),
                clamp01(color.getOpacity() * opacity)
        );
    }

    private static String toCssColor(Color color) {
        int red = colorComponentToInt(color.getRed());
        int green = colorComponentToInt(color.getGreen());
        int blue = colorComponentToInt(color.getBlue());
        double opacity = clamp01(color.getOpacity());
        if (opacity >= 0.999) {
            return String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue);
        }
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", red, green, blue, opacity);
    }

    private static int colorComponentToInt(double value) {
        return (int) Math.round(clamp01(value) * 255.0);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String ensureTrailingSemicolon(String style) {
        return style.endsWith(";") ? style : style + ";";
    }

    private void showThemeColorPickerError(Node ownerNode, Throwable error) {
        Throwable cause = error instanceof InvocationTargetException invocationException && invocationException.getCause() != null
                ? invocationException.getCause()
                : error;
        String message = cause.getMessage();
        ControllerDialogs.showError(
                ownerNode,
                ShapeXResources.format(
                        "window.theme.color_picker.error",
                        message == null || message.isBlank() ? cause.getClass().getSimpleName() : message
                )
        );
    }

    private void toggleWindowSize() {
        var scene = settingsAccordion.getScene();
        if (scene == null || !(scene.getWindow() instanceof Stage stage)) {
            return;
        }

        registerWindowSizingStage(stage);
        if (!isUsableWindowSize(automaticWindowWidth)
                || !isUsableWindowSize(automaticWindowHeight)) {
            applyAutomaticWindowSize(true);
        }
        if (!isUsableWindowSize(automaticWindowWidth)
                || !isUsableWindowSize(automaticWindowHeight)) {
            return;
        }

        pendingAutomaticWindowResizeTask = null;
        applyingProgrammaticWindowSize = true;
        if (isStandardWindowSize(stage)) {
            userManagedWindowSize = true;
            fillCurrentScreen(stage);
        } else {
            userManagedWindowSize = false;
            applyAutomaticWindowSize(true);
        }

        Platform.runLater(() -> {
            applyingProgrammaticWindowSize = false;
            windowSizingInitialized = true;
        });
    }

    private boolean isStandardWindowSize(Stage stage) {
        return Math.abs(stage.getWidth() - automaticWindowWidth) <= STANDARD_WINDOW_SIZE_TOLERANCE
                && Math.abs(stage.getHeight() - automaticWindowHeight) <= STANDARD_WINDOW_SIZE_TOLERANCE;
    }

    private void fillCurrentScreen(Stage stage) {
        Rectangle2D visualBounds = getCurrentScreen(stage).getVisualBounds();
        stage.setX(visualBounds.getMinX());
        stage.setY(visualBounds.getMinY());
        stage.setWidth(visualBounds.getWidth());
        stage.setHeight(visualBounds.getHeight());
    }

    private Screen getCurrentScreen(Stage stage) {
        double centerX = stage.getX() + stage.getWidth() / 2.0;
        double centerY = stage.getY() + stage.getHeight() / 2.0;
        var centerScreens = Screen.getScreensForRectangle(centerX, centerY, 1.0, 1.0);
        if (!centerScreens.isEmpty()) {
            return centerScreens.get(0);
        }

        Rectangle2D windowBounds = new Rectangle2D(
                stage.getX(),
                stage.getY(),
                stage.getWidth(),
                stage.getHeight()
        );
        Screen bestScreen = Screen.getPrimary();
        double largestIntersection = -1.0;
        for (Screen screen : Screen.getScreens()) {
            double intersection = getIntersectionArea(windowBounds, screen.getBounds());
            if (intersection > largestIntersection) {
                bestScreen = screen;
                largestIntersection = intersection;
            }
        }
        return bestScreen;
    }

    private double getIntersectionArea(Rectangle2D first, Rectangle2D second) {
        double width = Math.max(
                0.0,
                Math.min(first.getMaxX(), second.getMaxX())
                        - Math.max(first.getMinX(), second.getMinX())
        );
        double height = Math.max(
                0.0,
                Math.min(first.getMaxY(), second.getMaxY())
                        - Math.max(first.getMinY(), second.getMinY())
        );
        return width * height;
    }

    private void applyAutomaticWindowSize(boolean force) {
        if (!force && userManagedWindowSize) {
            return;
        }

        var scene = settingsAccordion.getScene();
        if (scene == null || !(scene.getWindow() instanceof Stage stage)) {
            return;
        }

        registerWindowSizingStage(stage);

        applyingProgrammaticWindowSize = true;
        Priority originalAccordionVgrow = VBox.getVgrow(settingsAccordion);
        double originalAccordionMaxHeight = settingsAccordion.getMaxHeight();
        Parent root = scene.getRoot();

        try {
            VBox.setVgrow(settingsAccordion, Priority.NEVER);
            settingsAccordion.setMaxHeight(Region.USE_PREF_SIZE);
            root.applyCss();
            root.layout();

            double decorationWidth = getCachedWindowDecorationWidth(stage, scene);
            double decorationHeight = getCachedWindowDecorationHeight(stage, scene);
            if (!isUsableWindowSize(automaticWindowWidth)) {
                automaticWindowWidth = Math.ceil(getPreferredWidth(root) + decorationWidth);
            }

            double targetSceneWidth = Math.max(0.0, automaticWindowWidth - decorationWidth);
            double compactSceneHeight = getCompactPreferredHeight(root, targetSceneWidth);
            double calculatedWindowHeight = Math.min(
                    Math.ceil(compactSceneHeight + decorationHeight),
                    getCurrentScreen(stage).getVisualBounds().getHeight()
            );

            automaticWindowHeight = calculatedWindowHeight;

            stage.setWidth(automaticWindowWidth);
            stage.setHeight(calculatedWindowHeight);
        } finally {
            VBox.setVgrow(settingsAccordion, originalAccordionVgrow);
            settingsAccordion.setMaxHeight(originalAccordionMaxHeight);
            root.applyCss();
            root.layout();

            Platform.runLater(() -> {
                applyingProgrammaticWindowSize = false;
                windowSizingInitialized = true;
            });
        }
    }

    private double getPreferredWidth(Parent root) {
        if (root instanceof Region region) {
            double preferredWidth = region.prefWidth(-1);
            if (isUsableWindowSize(preferredWidth)) {
                return preferredWidth;
            }
        }
        return root.getLayoutBounds().getWidth();
    }

    private double getCompactPreferredHeight(Node node, double width) {
        if (node == null || !node.isManaged()) {
            return 0.0;
        }

        // Composite controls are measured recursively from the active layout state. In particular,
        // AccordionSkin retains its previous pane internally, so native prefHeight values can leak
        // a previously expanded pane into the current pane's standard height.
        if (node instanceof Accordion accordion) {
            return getAccordionCompactPreferredHeight(accordion, width);
        }
        if (node instanceof VBox vBox) {
            return getVBoxCompactPreferredHeight(vBox, width);
        }
        if (node instanceof HBox hBox) {
            return getHBoxCompactPreferredHeight(hBox, width);
        }
        if (node instanceof StackPane stackPane) {
            return getStackPaneCompactPreferredHeight(stackPane, width);
        }
        if (node instanceof Region region) {
            return getRegionPreferredHeight(region, width);
        }
        return node.getLayoutBounds().getHeight();
    }

    private double getAccordionCompactPreferredHeight(Accordion accordion, double width) {
        Insets insets = getInsets(accordion);
        double paneWidth = getContentWidth(width, insets);
        double fallbackHeaderHeight = getAccordionFallbackHeaderHeight(accordion, paneWidth);
        double panesHeight = insets.getTop() + insets.getBottom();
        TitledPane expandedPane = accordion.getExpandedPane();

        for (TitledPane pane : accordion.getPanes()) {
            panesHeight += getTitledPaneCompactPreferredHeight(
                    pane,
                    paneWidth,
                    fallbackHeaderHeight,
                    pane == expandedPane
            );
        }

        return applyExplicitHeightFloor(accordion, panesHeight);
    }

    private double getTitledPaneCompactPreferredHeight(
            TitledPane pane,
            double width,
            double fallbackHeaderHeight,
            boolean expanded
    ) {
        double headerHeight = Math.max(getTitledPaneHeaderHeight(pane, width), fallbackHeaderHeight);
        if (!expanded || pane.getContent() == null) {
            return applyExplicitHeightFloor(pane, headerHeight);
        }

        double contentHeight = getCompactPreferredHeight(pane.getContent(), width);
        return applyExplicitHeightFloor(pane, headerHeight + contentHeight);
    }

    private double getAccordionFallbackHeaderHeight(Accordion accordion, double width) {
        double headerHeight = 0.0;
        for (TitledPane pane : accordion.getPanes()) {
            headerHeight = Math.max(headerHeight, getTitledPaneHeaderHeight(pane, width));
        }
        return isUsableWindowSize(headerHeight) ? headerHeight : FALLBACK_TITLE_PANE_HEADER_HEIGHT;
    }

    private double getTitledPaneHeaderHeight(TitledPane pane, double width) {
        Node titleNode = pane.lookup(".title");
        if (titleNode instanceof Region titleRegion) {
            double titleHeight = titleRegion.prefHeight(width);
            if (isUsableWindowSize(titleHeight)) {
                return titleHeight;
            }
        }
        if (titleNode != null && isUsableWindowSize(titleNode.getLayoutBounds().getHeight())) {
            return titleNode.getLayoutBounds().getHeight();
        }
        return FALLBACK_TITLE_PANE_HEADER_HEIGHT;
    }

    private double getVBoxCompactPreferredHeight(VBox vBox, double width) {
        Insets insets = getInsets(vBox);
        double childWidth = getContentWidth(width, insets);
        double totalHeight = insets.getTop() + insets.getBottom();
        int managedChildren = 0;

        for (Node child : vBox.getChildren()) {
            if (!child.isManaged()) {
                continue;
            }
            if (managedChildren > 0) {
                totalHeight += vBox.getSpacing();
            }
            totalHeight += getCompactPreferredHeight(child, childWidth);
            managedChildren++;
        }

        return applyExplicitHeightFloor(vBox, totalHeight);
    }

    private double getHBoxCompactPreferredHeight(HBox hBox, double width) {
        Insets insets = getInsets(hBox);
        double childWidth = getContentWidth(width, insets);
        double maxChildHeight = 0.0;

        for (Node child : hBox.getChildren()) {
            maxChildHeight = Math.max(maxChildHeight, getCompactPreferredHeight(child, childWidth));
        }

        return applyExplicitHeightFloor(
                hBox,
                insets.getTop() + maxChildHeight + insets.getBottom()
        );
    }

    private double getStackPaneCompactPreferredHeight(StackPane stackPane, double width) {
        Insets insets = getInsets(stackPane);
        double childWidth = getContentWidth(width, insets);
        double maxChildHeight = 0.0;

        for (Node child : stackPane.getChildren()) {
            maxChildHeight = Math.max(maxChildHeight, getCompactPreferredHeight(child, childWidth));
        }

        return applyExplicitHeightFloor(
                stackPane,
                insets.getTop() + maxChildHeight + insets.getBottom()
        );
    }

    private double applyExplicitHeightFloor(Region region, double calculatedHeight) {
        // Honor authored FXML constraints without calling computed prefHeight on a composite node.
        double height = calculatedHeight;
        if (region.getMinHeight() >= 0.0) {
            height = Math.max(height, region.getMinHeight());
        }
        if (region.getPrefHeight() >= 0.0) {
            height = Math.max(height, region.getPrefHeight());
        }
        return height;
    }

    private double getRegionPreferredHeight(Region region, double width) {
        double preferredHeight = region.prefHeight(width);
        if (isUsableWindowSize(preferredHeight)) {
            return preferredHeight;
        }
        return region.getLayoutBounds().getHeight();
    }

    private Insets getInsets(Region region) {
        Insets insets = region.getInsets();
        return insets == null ? Insets.EMPTY : insets;
    }

    private double getContentWidth(double width, Insets insets) {
        if (!isUsableWindowSize(width)) {
            return width;
        }
        return Math.max(0.0, width - insets.getLeft() - insets.getRight());
    }

    private double getCachedWindowDecorationWidth(Stage stage, javafx.scene.Scene scene) {
        if (cachedWindowDecorationWidth < 0.0) {
            // Stage and Scene dimensions can briefly represent different resize generations.
            // Capture decorations once from the settled initial window instead of remeasuring
            // while the native window is transitioning between standard and screen-sized.
            cachedWindowDecorationWidth = getPositiveWindowDecorationSize(stage.getWidth() - scene.getWidth());
        }
        return cachedWindowDecorationWidth;
    }

    private double getCachedWindowDecorationHeight(Stage stage, javafx.scene.Scene scene) {
        if (cachedWindowDecorationHeight < 0.0) {
            cachedWindowDecorationHeight = getPositiveWindowDecorationSize(stage.getHeight() - scene.getHeight());
        }
        return cachedWindowDecorationHeight;
    }

    private double getPositiveWindowDecorationSize(double size) {
        if (Double.isNaN(size) || Double.isInfinite(size) || size < 0.0) {
            return 0.0;
        }
        return size;
    }

    private boolean isUsableWindowSize(double size) {
        return !Double.isNaN(size) && !Double.isInfinite(size) && size > 0.0;
    }

    private void registerWindowSizingStage(Stage stage) {
        if (windowSizingStage == stage) {
            return;
        }

        windowSizingStage = stage;
        automaticWindowWidth = -1.0;
        automaticWindowHeight = -1.0;
        cachedWindowDecorationWidth = -1.0;
        cachedWindowDecorationHeight = -1.0;
        stage.widthProperty().addListener((obs, oldValue, newValue) -> markWindowSizeUserManaged());
        stage.heightProperty().addListener((obs, oldValue, newValue) -> markWindowSizeUserManaged());
    }

    private void markWindowSizeUserManaged() {
        if (!windowSizingInitialized || applyingProgrammaticWindowSize) {
            return;
        }
        userManagedWindowSize = true;
    }

    private void registerAccordionAutoResize(Accordion accordion, Runnable resizeTask) {
        if (accordion == null) {
            return;
        }

        accordion.expandedPaneProperty().addListener((obs, oldValue, newValue) ->
                scheduleAutomaticWindowResize(resizeTask)
        );
        accordion.getPanes().forEach(pane -> {
            pane.setAnimated(false);
            registerNestedAccordionAutoResize(pane.getContent(), resizeTask);
        });
    }

    private void scheduleAutomaticWindowResize(Runnable resizeTask) {
        if (automaticWindowResizePending) {
            return;
        }

        automaticWindowResizePending = true;
        pendingAutomaticWindowResizeTask = resizeTask;
        Platform.runLater(() -> Platform.runLater(() -> {
            automaticWindowResizePending = false;
            Runnable pendingTask = pendingAutomaticWindowResizeTask;
            pendingAutomaticWindowResizeTask = null;
            if (pendingTask != null) {
                pendingTask.run();
            }
        }));
    }

    private void registerNestedAccordionAutoResize(Node node, Runnable resizeTask) {
        if (node == null) {
            return;
        }

        if (node instanceof Accordion nestedAccordion) {
            registerAccordionAutoResize(nestedAccordion, resizeTask);
            return;
        }

        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child ->
                    registerNestedAccordionAutoResize(child, resizeTask)
            );
        }
    }
}
