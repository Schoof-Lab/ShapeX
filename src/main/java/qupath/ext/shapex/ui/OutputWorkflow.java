package qupath.ext.shapex.ui;

import qupath.ext.shapex.ShapeXResources;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import qupath.ext.shapex.core.AnnotationUtils;
import qupath.ext.shapex.core.AnndataAnnotationWriter;
import qupath.ext.shapex.core.CalibrationResolver;
import qupath.ext.shapex.core.GeometryRoiResolver;
import qupath.ext.shapex.core.IDotCsvWriter;
import qupath.ext.shapex.core.IDotRow;
import qupath.ext.shapex.core.LMDXmlWriter;
import qupath.ext.shapex.core.MultiPlateAssignmentModel;
import qupath.ext.shapex.core.PlateLocation;
import qupath.ext.shapex.core.PlateOverviewWriter;
import qupath.ext.shapex.core.Processor;
import qupath.ext.shapex.core.ProjectAnnotationService;
import qupath.ext.shapex.core.ShapeXAnnotationMetadata;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.roi.interfaces.ROI;

import org.locationtech.jts.geom.Geometry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class OutputWorkflow {

    private final Node ownerNode;
    private final CheckBox xmlLmdCheckBox;
    private final CheckBox idotCheckBox;
    private final CheckBox overviewCheckBox;
    private final CheckBox anndataCheckBox;
    private final RadioButton numbersRadio;
    private final RadioButton openDocRadio;
    private final RadioButton csvRadio;
    private final RadioButton tsvRadio;
    private final RadioButton txtRadio;
    private final PlateDesignManager plateDesignManager;
    private final IdotTableManager idotTableManager;

    private static final int IDOT_PHASE_WEIGHT = 2;
    private static final int OVERVIEW_PHASE_WEIGHT = 2;
    private static final int ANNDATA_PHASE_WEIGHT = 2;
    private static final double AUTOMATIC_GEOMETRY_MAX_AREA_FRACTION = 0.01;

    private record XmlJob(
            int plateNumber,
            String slideName,
            String fileName,
            ImageData<BufferedImage> imageData,
            Map<String, String> mapping
    ) {}

    private enum GeometryResolutionMode {
        MERGE_COMPONENTS,
        KEEP_COMPONENTS,
        SKIP_ANNOTATIONS,
        MANUAL_DECIDE
    }

    private record GeometryHandlingChoice(
            GeometryResolutionMode mode,
            boolean addFixesToQuPath
    ) {}

    private record GeometryIssue(
            String slideName,
            ImageData<BufferedImage> imageData,
            PathObject object
    ) {
        String objectId() {
            return ProjectAnnotationService.getPathObjectId(object);
        }

        String displayLabel() {
            String annotationLabel = ProjectAnnotationService.getTessellatorAnnotationLabel(object);
            if (annotationLabel == null || annotationLabel.isBlank()) {
                annotationLabel = ShapeXResources.get("annotation.default_name");
            }
            return slideName + " - " + annotationLabel;
        }

        boolean hasDisconnectedComponents() {
            return object != null
                    && GeometryRoiResolver.countDisconnectedComponents(object.getROI()) > 1;
        }

        boolean hasHoles() {
            return object != null
                    && GeometryRoiResolver.hasInteriorHoles(object.getROI());
        }
    }

    private record AutomaticGeometryAdjustment(
            String slideName,
            ImageData<BufferedImage> imageData,
            String objectId,
            String annotationLabel,
            ROI originalRoi,
            ROI correctedRoi,
            Geometry originalGeometry,
            Geometry correctedGeometry,
            Geometry removedGeometry,
            Geometry filledGeometry,
            int removedComponentCount,
            int filledHoleCount
    ) {
        String displayLabel() {
            return slideName + " - " + annotationLabel;
        }

        GeometryFixReviewWindow.Item toReviewItem() {
            return new GeometryFixReviewWindow.Item(
                    displayLabel(),
                    imageData,
                    originalRoi,
                    originalGeometry,
                    correctedGeometry,
                    removedGeometry,
                    filledGeometry,
                    removedComponentCount,
                    filledHoleCount
            );
        }
    }

    private record ExplicitGeometryChange(
            String slideName,
            String objectId,
            GeometryResolutionMode mode,
            ROI resolvedRoi,
            List<PathObject> replacements
    ) {}

    private record AppliedGeometryResolution(List<ExplicitGeometryChange> changes) {
        static AppliedGeometryResolution none() {
            return new AppliedGeometryResolution(List.of());
        }

        boolean changed() {
            return !changes.isEmpty();
        }
    }

    private record ManualGeometryResolution(
            Map<String, Set<String>> skippedAnnotationIdsBySlide,
            AppliedGeometryResolution appliedResolution
    ) {}

    OutputWorkflow(
            Node ownerNode,
            CheckBox xmlLmdCheckBox,
            CheckBox idotCheckBox,
            CheckBox overviewCheckBox,
            CheckBox anndataCheckBox,
            RadioButton numbersRadio,
            RadioButton openDocRadio,
            RadioButton csvRadio,
            RadioButton tsvRadio,
            RadioButton txtRadio,
            PlateDesignManager plateDesignManager,
            IdotTableManager idotTableManager
    ) {
        this.ownerNode = ownerNode;
        this.xmlLmdCheckBox = xmlLmdCheckBox;
        this.idotCheckBox = idotCheckBox;
        this.overviewCheckBox = overviewCheckBox;
        this.anndataCheckBox = anndataCheckBox;
        this.numbersRadio = numbersRadio;
        this.openDocRadio = openDocRadio;
        this.csvRadio = csvRadio;
        this.tsvRadio = tsvRadio;
        this.txtRadio = txtRadio;
        this.plateDesignManager = plateDesignManager;
        this.idotTableManager = idotTableManager;
    }

    /* ============================================================
     * Run workflow
     * ============================================================ */

    void handleRun(Path baseOutputDirectory) {
        if (baseOutputDirectory == null) {
            ControllerDialogs.showError(ownerNode, ShapeXResources.get("error.output.folder.not.selected"));
            return;
        }

        MultiPlateAssignmentModel plateSet = plateDesignManager.getPlateSet();
        if (plateSet == null) {
            ControllerDialogs.showError(ownerNode, ShapeXResources.get("error.plate.assignment.not.initialized"));
            return;
        }

        if (!idotTableManager.validateUniqueNames()) {
            return;
        }

        boolean generateXml = xmlLmdCheckBox.isSelected();
        boolean generateIdot = idotCheckBox.isSelected();
        boolean generateOverview = overviewCheckBox.isSelected();
        boolean generateAnndata = anndataCheckBox.isSelected();

        if (!generateXml && !generateIdot && !generateOverview && !generateAnndata) {
            ControllerDialogs.showError(ownerNode, ShapeXResources.get("error.output.type.not.selected"));
            return;
        }

        try {
            var project = QuPathGUI.getInstance().getProject();
            if (project == null) {
                ControllerDialogs.showError(ownerNode, ShapeXResources.get("project.none.open"));
                return;
            }
            if (project.getImageList().isEmpty()) {
                ControllerDialogs.showError(ownerNode, ShapeXResources.get("project.images.none"));
                return;
            }

            Set<String> selectedSlides = plateDesignManager.getSelectedSlides();
            if (selectedSlides.isEmpty()) {
                ControllerDialogs.showError(ownerNode, ShapeXResources.get("error.slides.not.selected"));
                return;
            }
            if (!ensureBaseOutputDirectory(baseOutputDirectory)) {
                return;
            }

            boolean includeAllAnnotations = plateDesignManager.isIncludeAllAnnotationsEnabled();
            boolean mapImageToPlate = plateDesignManager.isImageToPlateMappingEnabled();
            boolean poolClasses = plateDesignManager.isPoolClassesEnabled();
            boolean requiresCalibration = generateXml;

            Set<String> missingSlides = new HashSet<>();
            Set<String> slidesNeedingSelection = new HashSet<>();

            if (requiresCalibration) {
                for (var entry : project.getImageList()) {
                    String slideName = entry.getImageName();
                    if (!selectedSlides.contains(slideName)) {
                        continue;
                    }

                    try {
                        ImageData<BufferedImage> imageData =
                                ProjectAnnotationService.getImageDataForEntry(ProjectAnnotationService.findProjectEntryByName(project, slideName));

                        var status = CalibrationResolver.checkCalibrationStatus(imageData.getHierarchy());
                        if (status == CalibrationResolver.CalibrationStatus.MISSING) {
                            missingSlides.add(slideName);
                        }
                        if (status == CalibrationResolver.CalibrationStatus.NEEDS_SELECTION) {
                            slidesNeedingSelection.add(slideName);
                        }
                    } catch (Exception e) {
                        missingSlides.add(slideName);
                    }
                }
            }

            if (!missingSlides.isEmpty()) {
                StringBuilder msg = new StringBuilder();
                msg.append(ShapeXResources.get("error.calibration.missing.slides.header"));
                missingSlides.forEach(slide ->
                        msg.append("• ").append(slide).append("\n"));
                msg.append(ShapeXResources.get("error.generation.aborted.suffix"));
                ControllerDialogs.showError(ownerNode, msg.toString());
                return;
            }

            Map<String, List<PathObject>> userSelections = new HashMap<>();
            if (requiresCalibration) {
                for (String slideName : slidesNeedingSelection) {
                    var entry = ProjectAnnotationService.findProjectEntryByName(project, slideName);
                    if (entry == null) {
                        continue;
                    }

                    ImageData<BufferedImage> imageData = ProjectAnnotationService.getImageDataForEntry(entry);
                    try {
                        List<PathObject> selected = CalibrationResolver.resolveUserSelection(
                                imageData.getHierarchy(),
                                slideName
                        );
                        userSelections.put(slideName, selected);
                    } catch (Exception e) {
                        ControllerDialogs.showError(
                                ownerNode,
                                ShapeXResources.get("error.calibration.selection.cancelled.with_abort")
                        );
                        return;
                    }
                }
            }

            Map<String, ImageData<BufferedImage>> preparedImages = new HashMap<>();
            for (var entry : project.getImageList()) {
                String slideName = entry.getImageName();
                if (!selectedSlides.contains(slideName)) {
                    continue;
                }

                var typedEntry = ProjectAnnotationService.findProjectEntryByName(project, slideName);
                if (typedEntry == null) {
                    continue;
                }

                ImageData<BufferedImage> imageData = ProjectAnnotationService.getImageDataForEntry(typedEntry);
                if (requiresCalibration) {
                    CalibrationResolver.resolveAndStandardize(
                            imageData.getHierarchy(),
                            userSelections.get(slideName)
                    );
                    typedEntry.saveImageData(imageData);
                }
                preparedImages.put(slideName, imageData);
            }

            Map<String, ImageData<BufferedImage>> exportImages = createExportImageCopies(preparedImages);
            Map<String, Set<String>> skippedGeometryAnnotationIdsBySlide = Map.of();
            List<AutomaticGeometryAdjustment> automaticGeometryAdjustments = List.of();
            AppliedGeometryResolution explicitGeometryResolution = AppliedGeometryResolution.none();
            boolean addExplicitGeometryFixesToQuPath = false;

            if (generateXml || generateOverview || generateAnndata) {
                automaticGeometryAdjustments = applyAutomaticGeometryCleanup(
                        exportImages,
                        preparedImages,
                        plateSet,
                        mapImageToPlate,
                        poolClasses
                );

                List<GeometryIssue> geometryIssues = findExportableGeometryIssues(
                        exportImages,
                        plateSet,
                        mapImageToPlate,
                        poolClasses
                );
                if (!geometryIssues.isEmpty()) {
                    GeometryHandlingChoice geometryHandlingChoice =
                            showGeometryHandlingDialog(geometryIssues);
                    if (geometryHandlingChoice == null) {
                        return;
                    }
                    GeometryResolutionMode geometryResolutionMode = geometryHandlingChoice.mode();
                    addExplicitGeometryFixesToQuPath =
                            geometryHandlingChoice.addFixesToQuPath();

                    if (geometryResolutionMode == GeometryResolutionMode.MANUAL_DECIDE) {
                        Optional<List<GeometryFixReviewWindow.ManualDecision>> decisions =
                                showGeometryManualReviewWindow(geometryIssues);
                        if (decisions.isEmpty()) {
                            return;
                        }
                        ManualGeometryResolution manualResolution =
                                applyManualGeometryResolution(geometryIssues, decisions.get());
                        skippedGeometryAnnotationIdsBySlide =
                                manualResolution.skippedAnnotationIdsBySlide();
                        explicitGeometryResolution = manualResolution.appliedResolution();
                    } else if (geometryResolutionMode == GeometryResolutionMode.SKIP_ANNOTATIONS) {
                        skippedGeometryAnnotationIdsBySlide = buildSkippedGeometryAnnotationIds(geometryIssues);
                    } else {
                        explicitGeometryResolution = applyGeometryResolution(
                                geometryIssues,
                                geometryResolutionMode
                        );
                    }
                }
            }

            List<XmlJob> xmlJobs = generateXml
                    ? buildXmlJobs(
                            project,
                            exportImages,
                            selectedSlides,
                            plateSet,
                            includeAllAnnotations,
                            mapImageToPlate,
                            poolClasses,
                            skippedGeometryAnnotationIdsBySlide
                    )
                    : List.of();

            boolean hasXmlOutput = generateXml && !xmlJobs.isEmpty();
            boolean hasIdotOutput = generateIdot;
            boolean hasOverviewOutput = generateOverview;
            boolean hasAnndataOutput = generateAnndata;

            if (!hasXmlOutput && !hasIdotOutput && !hasOverviewOutput && !hasAnndataOutput) {
                ControllerDialogs.showError(ownerNode, ShapeXResources.get("error.nothing.to.generate"));
                return;
            }

            Path out;
            try {
                out = Processor.createOutputDirectory(baseOutputDirectory);
            } catch (IOException | SecurityException e) {
                ControllerDialogs.showError(
                        ownerNode,
                        ShapeXResources.format(
                                "error.output.run_folder.create_failed",
                                baseOutputDirectory.toAbsolutePath().normalize(),
                                FileErrorMessages.concise(e)
                        )
                );
                return;
            }

            Path xmlDir = null;
            if (hasXmlOutput) {
                xmlDir = out.resolve(ShapeXResources.get("output.dir.xml"));
                Files.createDirectories(xmlDir);
            }

            int rows = plateDesignManager.getPlateRows();
            int cols = plateDesignManager.getPlateCols();
            List<IDotRow> idotRows = idotTableManager.getRows();
            PlateOverviewWriter.OverviewExportFormat overviewFormat = getSelectedOverviewFormat();
            PlateOverviewWriter.RunMetadata overviewRunMetadata = createOverviewRunMetadata(
                    project,
                    out,
                    hasXmlOutput,
                    hasOverviewOutput,
                    hasAnndataOutput,
                    hasIdotOutput,
                    includeAllAnnotations,
                    automaticGeometryAdjustments.size(),
                    explicitGeometryResolution.changes().size(),
                    skippedGeometryAnnotationIdsBySlide
            );

            applyShapeXMetadata(
                    exportImages,
                    plateSet,
                    includeAllAnnotations,
                    mapImageToPlate,
                    poolClasses
            );

            boolean generationOk = runGenerationWithProgress(
                    out,
                    xmlDir,
                    xmlJobs,
                    hasXmlOutput,
                    hasIdotOutput,
                    hasOverviewOutput,
                    hasAnndataOutput,
                    exportImages,
                    plateSet,
                    rows,
                    cols,
                    overviewFormat,
                    idotRows,
                    includeAllAnnotations,
                    mapImageToPlate,
                    poolClasses,
                    skippedGeometryAnnotationIdsBySlide,
                    overviewRunMetadata
            );

            if (!generationOk) {
                return;
            }

            boolean applyAutomaticFixes = automaticGeometryAdjustments.isEmpty()
                    || showGenerationCompletedDialog(automaticGeometryAdjustments, out);
            validateLiveCommitTargets(
                    preparedImages,
                    applyAutomaticFixes ? automaticGeometryAdjustments : List.of(),
                    addExplicitGeometryFixesToQuPath
                            ? explicitGeometryResolution
                            : AppliedGeometryResolution.none()
            );
            Set<String> changedGeometrySlides = new HashSet<>();
            Set<String> changedLiveSlides = new HashSet<>();
            plateDesignManager.setAnnotationWatchdogSuspended(true);
            try {
                if (applyAutomaticFixes && !automaticGeometryAdjustments.isEmpty()) {
                    changedGeometrySlides.addAll(commitAutomaticGeometryAdjustments(
                            preparedImages,
                            automaticGeometryAdjustments
                    ));
                }
                if (addExplicitGeometryFixesToQuPath) {
                    // Explicit resolutions are previewed from the export hierarchy, after
                    // automatic cleanup. Commit that exact geometry so QuPath, the preview,
                    // and the generated outputs cannot diverge.
                    changedGeometrySlides.addAll(commitExplicitGeometryResolution(
                            preparedImages,
                            explicitGeometryResolution
                    ));
                }
                changedLiveSlides.addAll(changedGeometrySlides);
                changedLiveSlides.addAll(applyShapeXMetadata(
                        preparedImages,
                        plateSet,
                        includeAllAnnotations,
                        mapImageToPlate,
                        poolClasses
                ));
            } finally {
                plateDesignManager.setAnnotationWatchdogSuspended(false);
            }
            persistChangedImages(project, preparedImages, changedLiveSlides);

            if (!changedGeometrySlides.isEmpty()) {
                plateDesignManager.refreshAssignmentsFromAnnotations();
            }
            if (automaticGeometryAdjustments.isEmpty()) {
                ControllerDialogs.showInfo(
                        ownerNode,
                        ShapeXResources.format("info.generation.completed", out)
                );
            }
        } catch (Exception e) {
            String full = e.toString();
            for (StackTraceElement el : e.getStackTrace()) {
                full += "\n   at " + el;
            }
            ControllerDialogs.showError(
                    ownerNode,
                    ShapeXResources.format("error.output.generation.failed", full)
            );
        }
    }

    private boolean ensureBaseOutputDirectory(Path baseOutputDirectory) {
        Path displayPath = baseOutputDirectory.toAbsolutePath().normalize();
        try {
            if (Files.exists(baseOutputDirectory)) {
                if (!Files.isDirectory(baseOutputDirectory)) {
                    ControllerDialogs.showError(
                            ownerNode,
                            ShapeXResources.format(
                                    "error.output.folder.not_directory",
                                    displayPath
                            )
                    );
                    return false;
                }
                if (!Files.isWritable(baseOutputDirectory)) {
                    ControllerDialogs.showError(
                            ownerNode,
                            ShapeXResources.format(
                                    "error.output.folder.not_writable",
                                    displayPath
                            )
                    );
                    return false;
                }
                return true;
            }

            boolean createDirectory = ControllerDialogs.showConfirmation(
                    ownerNode,
                    ShapeXResources.get("output.directory.missing.header"),
                    ShapeXResources.format(
                            "output.directory.missing.prompt",
                            displayPath
                    )
            );
            if (!createDirectory) {
                return false;
            }

            Files.createDirectories(baseOutputDirectory);
            if (!Files.isDirectory(baseOutputDirectory)) {
                throw new IOException(ShapeXResources.get("error.output.folder.creation.unverified"));
            }
            return true;
        } catch (IOException | SecurityException e) {
            ControllerDialogs.showError(
                    ownerNode,
                    ShapeXResources.format(
                            "error.output.folder.create_failed",
                            displayPath,
                            FileErrorMessages.concise(e)
                    )
            );
            return false;
        }
    }

    /* ============================================================
     * Background execution
     * ============================================================ */

    private boolean runGenerationWithProgress(
            Path out,
            Path xmlDir,
            List<XmlJob> xmlJobs,
            boolean hasXmlOutput,
            boolean hasIdotOutput,
            boolean hasOverviewOutput,
            boolean hasAnndataOutput,
            Map<String, ImageData<BufferedImage>> preparedImages,
            MultiPlateAssignmentModel plateSet,
            int rows,
            int cols,
            PlateOverviewWriter.OverviewExportFormat overviewFormat,
            List<IDotRow> idotRows,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses,
            Map<String, Set<String>> skippedGeometryAnnotationIdsBySlide,
            PlateOverviewWriter.RunMetadata overviewRunMetadata
    ) {
        javafx.concurrent.Task<Void> task = new javafx.concurrent.Task<>() {
            @Override
            protected Void call() throws Exception {
                List<String> phases = new ArrayList<>();
                if (hasXmlOutput) {
                    phases.add(ShapeXResources.get("output.phase.xml"));
                }
                if (hasOverviewOutput) {
                    phases.add(ShapeXResources.get("output.phase.overview"));
                }
                if (hasAnndataOutput) {
                    phases.add(ShapeXResources.get("output.phase.anndata"));
                }
                if (hasIdotOutput) {
                    phases.add(ShapeXResources.get("output.phase.idot"));
                }

                int totalPhases = phases.size();
                int phaseNumber = 0;

                List<String> xmlSlides = new ArrayList<>();
                Set<String> seenSlides = new HashSet<>();
                Map<String, Integer> xmlPlatesPerSlide = new HashMap<>();
                for (XmlJob job : xmlJobs) {
                    if (seenSlides.add(job.slideName())) {
                        xmlSlides.add(job.slideName());
                    }
                    xmlPlatesPerSlide.merge(job.slideName(), 1, Integer::sum);
                }

                double xmlWeightTotal = xmlSlides.size();
                double overviewWeight = hasOverviewOutput ? OVERVIEW_PHASE_WEIGHT : 0.0;
                double anndataWeight = hasAnndataOutput ? ANNDATA_PHASE_WEIGHT : 0.0;
                double idotWeight = hasIdotOutput ? IDOT_PHASE_WEIGHT : 0.0;

                if (hasIdotOutput && (hasXmlOutput || hasOverviewOutput || hasAnndataOutput)) {
                    double preIdotBase = xmlWeightTotal + overviewWeight + anndataWeight;
                    if (preIdotBase > 0) {
                        double scale = 99.0 / preIdotBase;
                        xmlWeightTotal *= scale;
                        overviewWeight *= scale;
                        anndataWeight *= scale;
                        idotWeight = 1.0;
                    }
                }

                double totalWeight = xmlWeightTotal + overviewWeight + anndataWeight + idotWeight;
                double doneWeight = 0.0;
                updateProgress(doneWeight, totalWeight);

                if (hasXmlOutput) {
                    phaseNumber++;
                    updateTitle(ShapeXResources.format("progress.step", phaseNumber, totalPhases, ShapeXResources.get("progress.generation.xml_files")));

                    int imageIndex = 0;
                    for (String slideName : xmlSlides) {
                        imageIndex++;
                        updateMessage(ShapeXResources.format("progress.generation.image_counter", imageIndex, xmlSlides.size()));

                        for (XmlJob job : xmlJobs) {
                            if (!job.slideName().equals(slideName)) {
                                continue;
                            }

                            Path plateFolder = xmlDir.resolve(ShapeXResources.format("plate.name.pretty", job.plateNumber()));
                            Files.createDirectories(plateFolder);

                            Path xmlFile = plateFolder.resolve(job.fileName());
                            LMDXmlWriter.writeXml(
                                    xmlFile,
                                    job.imageData(),
                                    job.slideName(),
                                    job.plateNumber(),
                                    xmlPlatesPerSlide.getOrDefault(job.slideName(), 1),
		                                    job.mapping(),
		                                    mapImageToPlate,
		                                    poolClasses,
		                                    skippedGeometryAnnotationIdsBySlide.getOrDefault(job.slideName(), Set.of()),
                                            rows,
                                            cols
		                            );
                        }

                        doneWeight += (xmlWeightTotal / xmlSlides.size());
                        updateProgress(doneWeight, totalWeight);
                    }
                }

                if (hasOverviewOutput) {
                    phaseNumber++;
                    updateTitle(ShapeXResources.format("progress.step", phaseNumber, totalPhases, ShapeXResources.get("progress.generation.overview_export")));
                    updateMessage(ShapeXResources.format("progress.generation.write_overview", overviewFormat.getDisplayName()));

                    PlateOverviewWriter.generateOverview(
                            out,
                            plateSet,
                            preparedImages,
                            rows,
                            cols,
		                            overviewFormat,
		                            mapImageToPlate,
		                            poolClasses,
		                            skippedGeometryAnnotationIdsBySlide,
                                    overviewRunMetadata
		                    );

                    doneWeight += overviewWeight;
                    updateProgress(doneWeight, totalWeight);
                }

                if (hasAnndataOutput) {
                    phaseNumber++;
                    updateTitle(ShapeXResources.format("progress.step", phaseNumber, totalPhases, ShapeXResources.get("progress.generation.anndata_export")));
                    updateMessage(ShapeXResources.get("progress.generation.write_anndata"));

                    AnndataAnnotationWriter.generateAnndata(
                            out,
                            plateSet,
                            preparedImages,
                            rows,
                            cols,
                            includeAllAnnotations,
                            mapImageToPlate,
                            poolClasses,
                            skippedGeometryAnnotationIdsBySlide
                    );

                    doneWeight += anndataWeight;
                    updateProgress(doneWeight, totalWeight);
                }

                if (hasIdotOutput) {
                    phaseNumber++;
                    updateTitle(ShapeXResources.format("progress.step", phaseNumber, totalPhases, ShapeXResources.get("progress.generation.idot_csv")));
                    updateMessage(ShapeXResources.get("progress.generation.write_idot"));

                    Path idotDir = out.resolve(ShapeXResources.get("output.dir.idot"));
                    Files.createDirectories(idotDir);
                    IDotCsvWriter.generateCsvFiles(idotDir, plateSet, idotRows);

                    doneWeight += idotWeight;
                    updateProgress(doneWeight, totalWeight);
                }

                updateProgress(totalWeight, totalWeight);
                updateTitle(ShapeXResources.get("progress.completed"));
                updateMessage(ShapeXResources.get("progress.done"));
                return null;
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
                new Label(ShapeXResources.get("progress.generation.dialog.title")),
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
        dialog.setOnCloseRequest(event -> {
            if (!task.isDone()) {
                event.consume();
            }
        });

        final boolean[] success = {false};
        final String[] failureMessage = {null};

        task.setOnSucceeded(event -> {
            success[0] = true;
            dialog.close();
        });
        task.setOnFailed(event -> {
            success[0] = false;
            Throwable ex = task.getException();
            failureMessage[0] = ex != null ? ex.getMessage() : ShapeXResources.get("error.unknown");
            dialog.close();
        });

        Thread worker = new Thread(task, "shapex-xml-generation");
        worker.setDaemon(true);
        worker.start();
        dialog.showAndWait();

        if (!success[0]) {
            ControllerDialogs.showError(
                    ownerNode,
                    ShapeXResources.format(
                            "error.generation.failed",
                            failureMessage[0] != null ? failureMessage[0] : ShapeXResources.get("error.unknown")
                    )
            );
        }

        return success[0];
    }

    private Set<String> applyShapeXMetadata(
            Map<String, ImageData<BufferedImage>> images,
            MultiPlateAssignmentModel plateSet,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        Set<String> changedSlides = new HashSet<>();
        for (var imageEntry : images.entrySet()) {
            String slideName = imageEntry.getKey();
            ImageData<BufferedImage> imageData = imageEntry.getValue();
            if (imageData == null) {
                continue;
            }

            int changedAnnotationCount = ShapeXAnnotationMetadata.apply(
                    imageData.getHierarchy(),
                    slideName,
                    plateSet,
                    includeAllAnnotations,
                    mapImageToPlate,
                    poolClasses
            );
            if (changedAnnotationCount > 0) {
                changedSlides.add(slideName);
            }
        }
        return changedSlides;
    }

    /* ============================================================
     * Helpers
     * ============================================================ */

    private boolean showGenerationCompletedDialog(
            List<AutomaticGeometryAdjustment> adjustments,
            Path outputDirectory
    ) {
        CheckBox applyFixes = new CheckBox(
                ShapeXResources.get("info.generation.apply_fixes_to_qupath")
        );
        applyFixes.setSelected(true);
        applyFixes.setTooltip(new Tooltip(
                ShapeXResources.get("info.generation.apply_fixes_to_qupath.tooltip")
        ));

        Alert dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle(ShapeXResources.get("title"));
        dialog.setHeaderText(null);
        var owner = ControllerDialogs.resolveOwner(ownerNode);
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ButtonType reviewButton = new ButtonType(
                ShapeXResources.get("info.generation.review_fixes"),
                ButtonBar.ButtonData.HELP
        );
        dialog.getButtonTypes().setAll(ButtonType.OK, reviewButton);
        dialog.getDialogPane().setContent(new VBox(
                12,
                wrapLabel(ShapeXResources.format("info.generation.completed", outputDirectory)),
                applyFixes
        ));

        List<GeometryFixReviewWindow.Item> reviewItems = adjustments.stream()
                .map(AutomaticGeometryAdjustment::toReviewItem)
                .toList();
        Node reviewNode = dialog.getDialogPane().lookupButton(reviewButton);
        reviewNode.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            GeometryFixReviewWindow.show(dialog.getDialogPane(), ownerNode, reviewItems);
        });

        dialog.showAndWait();
        return applyFixes.isSelected();
    }

    private PlateOverviewWriter.OverviewExportFormat getSelectedOverviewFormat() {
        if (numbersRadio != null && numbersRadio.isSelected()) {
            return PlateOverviewWriter.OverviewExportFormat.NUMBERS;
        }
        if (openDocRadio != null && openDocRadio.isSelected()) {
            return PlateOverviewWriter.OverviewExportFormat.OPEN_DOCUMENT;
        }
        if (csvRadio != null && csvRadio.isSelected()) {
            return PlateOverviewWriter.OverviewExportFormat.CSV;
        }
        if (tsvRadio != null && tsvRadio.isSelected()) {
            return PlateOverviewWriter.OverviewExportFormat.TSV;
        }
        if (txtRadio != null && txtRadio.isSelected()) {
            return PlateOverviewWriter.OverviewExportFormat.TXT;
        }
        return PlateOverviewWriter.OverviewExportFormat.EXCEL;
    }

    private PlateOverviewWriter.RunMetadata createOverviewRunMetadata(
            Project<?> project,
            Path runDirectory,
            boolean hasXmlOutput,
            boolean hasOverviewOutput,
            boolean hasAnndataOutput,
            boolean hasIdotOutput,
            boolean includeAllAnnotations,
            int automaticGeometryAdjustments,
            int explicitGeometryResolutions,
            Map<String, Set<String>> skippedGeometryAnnotationIdsBySlide
    ) {
        List<String> requestedOutputs = new ArrayList<>();
        if (hasXmlOutput) {
            requestedOutputs.add(ShapeXResources.get("overview.output.lmd"));
        }
        if (hasOverviewOutput) {
            requestedOutputs.add(ShapeXResources.get("overview.output.overview"));
        }
        if (hasAnndataOutput) {
            requestedOutputs.add(ShapeXResources.get("overview.output.anndata"));
        }
        if (hasIdotOutput) {
            requestedOutputs.add(ShapeXResources.get("overview.output.idot"));
        }

        int skippedGeometryAnnotations = skippedGeometryAnnotationIdsBySlide == null
                ? 0
                : skippedGeometryAnnotationIdsBySlide.values().stream()
                        .filter(java.util.Objects::nonNull)
                        .mapToInt(Set::size)
                        .sum();

        String quPathVersion = QuPathGUI.getVersion() == null
                ? null
                : QuPathGUI.getVersion().toString();

        return new PlateOverviewWriter.RunMetadata(
                OffsetDateTime.now().toString(),
                runDirectory == null ? null : runDirectory.toAbsolutePath().normalize().toString(),
                project == null ? null : project.getName(),
                project == null || project.getURI() == null ? null : project.getURI().toString(),
                project == null ? null : project.getVersion(),
                quPathVersion,
                QuPathGUI.getBuildString(),
                requestedOutputs,
                includeAllAnnotations,
                automaticGeometryAdjustments,
                explicitGeometryResolutions,
                skippedGeometryAnnotations
        );
    }

    private Map<String, ImageData<BufferedImage>> createExportImageCopies(
            Map<String, ImageData<BufferedImage>> sourceImages
    ) {
        Map<String, ImageData<BufferedImage>> copies = new HashMap<>();
        for (var imageEntry : sourceImages.entrySet()) {
            ImageData<BufferedImage> source = imageEntry.getValue();
            if (source == null) {
                continue;
            }

            PathObjectHierarchy hierarchyCopy = new PathObjectHierarchy();
            for (PathObject child : source.getHierarchy().getRootObject().getChildObjects()) {
                // Retain IDs so accepted fixes can be replayed onto live annotations after export.
                hierarchyCopy.addObject(PathObjectTools.transformObjectRecursive(
                        child,
                        null,
                        true,
                        false
                ));
            }

            ImageData<BufferedImage> imageCopy = new ImageData<>(
                    source.getServer(),
                    hierarchyCopy,
                    source.getImageType()
            );
            if (source.isBrightfield()) {
                imageCopy.setColorDeconvolutionStains(source.getColorDeconvolutionStains());
            }
            source.getProperties().forEach(imageCopy::setProperty);
            copies.put(imageEntry.getKey(), imageCopy);
        }
        return copies;
    }

    private List<XmlJob> buildXmlJobs(
            Project<?> project,
            Map<String, ImageData<BufferedImage>> preparedImages,
            Set<String> selectedSlides,
            MultiPlateAssignmentModel plateSet,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses,
            Map<String, Set<String>> skippedGeometryAnnotationIdsBySlide
    ) {
        List<XmlJob> xmlJobs = new ArrayList<>();
        Map<String, PlateLocation> sampleLocations = plateSet.getEnabledSampleLocations();
        int plateCount = plateSet.getPlateCount();

        for (var entry : project.getImageList()) {
            String slideName = entry.getImageName();
            if (!selectedSlides.contains(slideName)) {
                continue;
            }

            ImageData<BufferedImage> imageData = preparedImages.get(slideName);
            if (imageData == null) {
                continue;
            }

            Set<String> sampleKeysOnSlide = getAnnotationSampleKeysForOutput(
                    imageData.getHierarchy(),
                    slideName,
                    includeAllAnnotations,
                    mapImageToPlate,
                    poolClasses,
                    skippedGeometryAnnotationIdsBySlide.getOrDefault(slideName, Set.of())
            );

            for (int plateIndex = 0; plateIndex < plateCount; plateIndex++) {
                int plateNumber = plateIndex + 1;
                Set<String> excludedWells = plateSet.getExcludedWells(plateIndex);
                Map<String, String> imagePlateMapping = new HashMap<>();

                for (var sampleEntry : sampleLocations.entrySet()) {
                    String sample = sampleEntry.getKey();
                    PlateLocation location = sampleEntry.getValue();

                    if (location.plateIndex() != plateIndex) {
                        continue;
                    }
                    if (excludedWells.contains(location.well())) {
                        continue;
                    }
                    if (!sampleKeysOnSlide.contains(sample)) {
                        continue;
                    }

                    imagePlateMapping.put(sample, location.well());
                }

                if (imagePlateMapping.isEmpty()) {
                    continue;
                }

                String baseName = slideName.replaceFirst("[.][^.]+$", "");
                String fileName = baseName + "_Plate" + plateNumber + ".xml";
                xmlJobs.add(new XmlJob(
                        plateNumber,
                        slideName,
                        fileName,
                        imageData,
                        imagePlateMapping
                ));
            }
        }

        return xmlJobs;
    }

    private Set<String> getAnnotationSampleKeysForOutput(
            PathObjectHierarchy hierarchy,
            String slideName,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses,
            Set<String> skippedAnnotationIds
    ) {
        if (hierarchy == null) {
            return Set.of();
        }

        Set<String> sampleKeys = new HashSet<>();
        for (PathObject obj : hierarchy.getAnnotationObjects()) {
            if (skippedAnnotationIds != null
                    && skippedAnnotationIds.contains(ProjectAnnotationService.getPathObjectId(obj))) {
                continue;
            }

            String sampleKey = AnnotationUtils.getSampleKey(
                    obj,
                    slideName,
                    includeAllAnnotations,
                    mapImageToPlate,
                    poolClasses
            );
            if (sampleKey != null && !sampleKey.isBlank()) {
                sampleKeys.add(sampleKey);
            }
        }

        return sampleKeys;
    }

    private List<GeometryIssue> findExportableGeometryIssues(
            Map<String, ImageData<BufferedImage>> preparedImages,
            MultiPlateAssignmentModel plateSet,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        Set<String> enabledSamples = getEnabledSampleKeys(plateSet);

        List<GeometryIssue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var imageEntry : preparedImages.entrySet()) {
            String slideName = imageEntry.getKey();
            ImageData<BufferedImage> imageData = imageEntry.getValue();
            for (PathObject obj : imageData.getHierarchy().getAnnotationObjects()) {
                ROI roi = obj.getROI();
                if (roi == null || !roi.isArea() || !GeometryRoiResolver.isGeometryRoi(roi)) {
                    continue;
                }
                if (!GeometryRoiResolver.requiresSingleCutResolution(roi)) {
                    continue;
                }

                String sampleName = AnnotationUtils.getSampleKey(
                        obj,
                        slideName,
                        true,
                        mapImageToPlate,
                        poolClasses
                );
                if (sampleName == null || !enabledSamples.contains(sampleName)) {
                    continue;
                }

                GeometryIssue issue = new GeometryIssue(slideName, imageData, obj);
                if (seen.add(geometryIssueKey(issue))) {
                    issues.add(issue);
                }
            }
        }

        issues.sort(Comparator.comparing(
                GeometryIssue::displayLabel,
                String.CASE_INSENSITIVE_ORDER
        ));
        return List.copyOf(issues);
    }

    private Set<String> getEnabledSampleKeys(MultiPlateAssignmentModel plateSet) {
        Set<String> enabledSamples = new HashSet<>();
        for (var sampleEntry : plateSet.getEnabledSampleLocations().entrySet()) {
            PlateLocation location = sampleEntry.getValue();
            if (!plateSet.getExcludedWells(location.plateIndex()).contains(location.well())) {
                enabledSamples.add(sampleEntry.getKey());
            }
        }
        return enabledSamples;
    }

    private List<AutomaticGeometryAdjustment> applyAutomaticGeometryCleanup(
            Map<String, ImageData<BufferedImage>> exportImages,
            Map<String, ImageData<BufferedImage>> previewImages,
            MultiPlateAssignmentModel plateSet,
            boolean mapImageToPlate,
            boolean poolClasses
    ) {
        Set<String> enabledSamples = getEnabledSampleKeys(plateSet);
        List<AutomaticGeometryAdjustment> adjustments = new ArrayList<>();

        for (var imageEntry : exportImages.entrySet()) {
            String slideName = imageEntry.getKey();
            ImageData<BufferedImage> exportImage = imageEntry.getValue();
            for (PathObject obj : exportImage.getHierarchy().getAnnotationObjects()) {
                ROI originalRoi = obj.getROI();
                if (!(obj instanceof PathROIObject pathRoiObject)
                        || originalRoi == null
                        || !originalRoi.isArea()
                        || !GeometryRoiResolver.isGeometryRoi(originalRoi)) {
                    continue;
                }

                String sampleKey = AnnotationUtils.getSampleKey(
                        obj,
                        slideName,
                        true,
                        mapImageToPlate,
                        poolClasses
                );
                if (sampleKey == null || !enabledSamples.contains(sampleKey)) {
                    continue;
                }

                var result = GeometryRoiResolver.cleanupSmallComponentsAndHoles(
                        originalRoi,
                        AUTOMATIC_GEOMETRY_MAX_AREA_FRACTION
                );
                if (!result.changed() || result.roi() == null) {
                    continue;
                }

                String annotationLabel = ProjectAnnotationService.getTessellatorAnnotationLabel(obj);
                if (annotationLabel == null || annotationLabel.isBlank()) {
                    annotationLabel = ShapeXResources.get("annotation.default_name");
                }
                pathRoiObject.setROI(result.roi());
                exportImage.getHierarchy().updateObject(obj, false);
                adjustments.add(new AutomaticGeometryAdjustment(
                        slideName,
                        previewImages.getOrDefault(slideName, exportImage),
                        ProjectAnnotationService.getPathObjectId(obj),
                        annotationLabel,
                        originalRoi,
                        result.roi(),
                        result.originalGeometry(),
                        result.correctedGeometry(),
                        result.removedGeometry(),
                        result.filledGeometry(),
                        result.removedComponentCount(),
                        result.filledHoleCount()
                ));
            }
        }

        adjustments.sort(Comparator.comparing(
                AutomaticGeometryAdjustment::displayLabel,
                String.CASE_INSENSITIVE_ORDER
        ));
        return List.copyOf(adjustments);
    }

    private Map<String, Set<String>> buildSkippedGeometryAnnotationIds(
            List<GeometryIssue> geometryIssues
    ) {
        Map<String, Set<String>> skipped = new HashMap<>();
        for (GeometryIssue issue : geometryIssues) {
            String objectId = issue.objectId();
            if (objectId == null || objectId.isBlank()) {
                continue;
            }
            skipped.computeIfAbsent(issue.slideName(), ignored -> new HashSet<>())
                    .add(objectId);
        }
        return skipped;
    }

    private AppliedGeometryResolution applyGeometryResolution(
            List<GeometryIssue> geometryIssues,
            GeometryResolutionMode mode
    ) {
        List<ExplicitGeometryChange> changes = new ArrayList<>();
        for (GeometryIssue issue : geometryIssues) {
            switch (mode) {
                case MERGE_COMPONENTS -> {
                    if (!applyMergedGeometryResolution(issue)) {
                        throw new IllegalStateException(
                                "Could not merge geometry for " + issue.displayLabel()
                        );
                    }
                    changes.add(new ExplicitGeometryChange(
                            issue.slideName(),
                            issue.objectId(),
                            mode,
                            issue.object().getROI(),
                            List.of()
                    ));
                }
                case KEEP_COMPONENTS -> {
                    if (issue.hasHoles()) {
                        throw new IllegalStateException(
                                "Cannot keep a geometry hole as a separate component for "
                                        + issue.displayLabel()
                        );
                    }
                    List<PathObject> replacements = applySplitGeometryResolution(issue);
                    if (replacements.isEmpty()) {
                        throw new IllegalStateException(
                                "Could not split geometry for " + issue.displayLabel()
                        );
                    }
                    changes.add(new ExplicitGeometryChange(
                            issue.slideName(),
                            issue.objectId(),
                            mode,
                            null,
                            replacements
                    ));
                }
                case SKIP_ANNOTATIONS, MANUAL_DECIDE -> {
                    // These modes do not change the export hierarchy.
                }
            }
        }
        return changes.isEmpty()
                ? AppliedGeometryResolution.none()
                : new AppliedGeometryResolution(List.copyOf(changes));
    }

    private ManualGeometryResolution applyManualGeometryResolution(
            List<GeometryIssue> geometryIssues,
            List<GeometryFixReviewWindow.ManualDecision> decisions
    ) {
        Map<String, GeometryIssue> issuesByKey = new LinkedHashMap<>();
        for (GeometryIssue issue : geometryIssues) {
            String key = geometryIssueKey(issue);
            if (issuesByKey.putIfAbsent(key, issue) != null) {
                throw new IllegalStateException("Duplicate geometry review key: " + key);
            }
        }

        Set<String> decidedKeys = new HashSet<>();
        List<GeometryIssue> skippedIssues = new ArrayList<>();
        List<ExplicitGeometryChange> appliedChanges = new ArrayList<>();
        for (GeometryFixReviewWindow.ManualDecision decision : decisions) {
            if (decision == null
                    || decision.key() == null
                    || !decidedKeys.add(decision.key())) {
                throw new IllegalStateException("Invalid geometry review decision");
            }

            GeometryIssue issue = issuesByKey.get(decision.key());
            if (issue == null || decision.resolution() == null) {
                throw new IllegalStateException(
                        "Geometry review returned a decision for an unknown annotation"
                );
            }

            GeometryResolutionMode mode = switch (decision.resolution()) {
                case MERGE_COMPONENTS -> GeometryResolutionMode.MERGE_COMPONENTS;
                case KEEP_COMPONENTS -> GeometryResolutionMode.KEEP_COMPONENTS;
                case SKIP_ANNOTATION -> GeometryResolutionMode.SKIP_ANNOTATIONS;
            };
            if (mode == GeometryResolutionMode.SKIP_ANNOTATIONS) {
                skippedIssues.add(issue);
                continue;
            }

            appliedChanges.addAll(
                    applyGeometryResolution(List.of(issue), mode).changes()
            );
        }

        if (decidedKeys.size() != issuesByKey.size()
                || !decidedKeys.containsAll(issuesByKey.keySet())) {
            throw new IllegalStateException(
                    "Geometry review did not return a decision for every annotation"
            );
        }

        AppliedGeometryResolution appliedResolution = appliedChanges.isEmpty()
                ? AppliedGeometryResolution.none()
                : new AppliedGeometryResolution(List.copyOf(appliedChanges));
        return new ManualGeometryResolution(
                buildSkippedGeometryAnnotationIds(skippedIssues),
                appliedResolution
        );
    }

    private Optional<List<GeometryFixReviewWindow.ManualDecision>>
            showGeometryManualReviewWindow(List<GeometryIssue> geometryIssues) {
        List<GeometryFixReviewWindow.ManualItem> reviewItems = new ArrayList<>();
        for (GeometryIssue issue : geometryIssues) {
            ROI originalRoi = issue.object() == null ? null : issue.object().getROI();
            if (originalRoi == null) {
                continue;
            }

            ROI mergedRoi = GeometryRoiResolver.createMergedComponentRoi(originalRoi);
            Geometry originalGeometry = GeometryRoiResolver.prepareGeometryForPreview(
                    originalRoi.getGeometry()
            );
            Geometry mergedGeometry = mergedRoi == null
                    ? null
                    : GeometryRoiResolver.prepareGeometryForPreview(mergedRoi.getGeometry());
            List<Geometry> componentGeometries =
                    GeometryRoiResolver.splitIntoComponentRois(originalRoi).stream()
                            .map(ROI::getGeometry)
                            .map(GeometryRoiResolver::prepareGeometryForPreview)
                            .filter(geometry -> geometry != null && !geometry.isEmpty())
                            .toList();

            reviewItems.add(new GeometryFixReviewWindow.ManualItem(
                    geometryIssueKey(issue),
                    issue.displayLabel(),
                    issue.imageData(),
                    originalRoi,
                    originalGeometry,
                    mergedGeometry,
                    componentGeometries,
                    issue.hasDisconnectedComponents(),
                    issue.hasHoles()
            ));
        }

        if (reviewItems.size() != geometryIssues.size()) {
            throw new IllegalStateException(
                    "Could not prepare every geometry annotation for manual review"
            );
        }
        return GeometryFixReviewWindow.showManual(
                ownerNode,
                ownerNode,
                reviewItems
        );
    }

    private String geometryIssueKey(GeometryIssue issue) {
        String objectId = issue.objectId();
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalStateException(
                    "Geometry annotation has no stable ID: " + issue.displayLabel()
            );
        }
        return issue.slideName() + "|" + objectId;
    }

    private boolean applyMergedGeometryResolution(GeometryIssue issue) {
        PathObject obj = issue.object();
        if (!(obj instanceof PathROIObject pathRoiObject)) {
            return false;
        }

        ROI mergedRoi = GeometryRoiResolver.createMergedComponentRoi(obj.getROI());
        if (mergedRoi == null) {
            return false;
        }

        pathRoiObject.setROI(mergedRoi);
        issue.imageData().getHierarchy().updateObject(obj, false);
        return true;
    }

    private List<PathObject> applySplitGeometryResolution(GeometryIssue issue) {
        PathObject obj = issue.object();
        List<ROI> componentRois = GeometryRoiResolver.splitIntoComponentRois(obj.getROI());
        if (componentRois.isEmpty()) {
            return List.of();
        }

        List<PathObject> replacements = new ArrayList<>();
        for (ROI componentRoi : componentRois) {
            PathObject replacement = PathObjects.createAnnotationObject(componentRoi, obj.getPathClass());
            copyAnnotationProperties(obj, replacement);
            replacements.add(replacement);
        }

        PathObjectHierarchy hierarchy = issue.imageData().getHierarchy();
        hierarchy.removeObject(obj, true);
        hierarchy.insertPathObjects(replacements);
        return List.copyOf(replacements);
    }

    private void copyAnnotationProperties(PathObject source, PathObject target) {
        target.setName(source.getName());
        target.setColor(source.getColor());
        target.setLocked(source.isLocked());
        if (source.hasMetadata()) {
            target.getMetadata().putAll(source.getMetadata());
        }
    }

    private Set<String> commitAutomaticGeometryAdjustments(
            Map<String, ImageData<BufferedImage>> liveImages,
            List<AutomaticGeometryAdjustment> adjustments
    ) {
        Set<String> changedSlides = new HashSet<>();
        for (AutomaticGeometryAdjustment adjustment : adjustments) {
            ImageData<BufferedImage> liveImage = liveImages.get(adjustment.slideName());
            if (liveImage == null) {
                throw new IllegalStateException("Missing live image for " + adjustment.slideName());
            }
            if (adjustment.correctedRoi() == null) {
                throw new IllegalStateException("Missing corrected ROI for " + adjustment.displayLabel());
            }

            PathObject liveObject = findAnnotationById(liveImage.getHierarchy(), adjustment.objectId());
            if (!(liveObject instanceof PathROIObject pathRoiObject)) {
                throw new IllegalStateException("Could not find live annotation for " + adjustment.displayLabel());
            }

            pathRoiObject.setROI(adjustment.correctedRoi());
            liveImage.getHierarchy().updateObject(liveObject, false);
            changedSlides.add(adjustment.slideName());
        }
        return changedSlides;
    }

    private void validateLiveCommitTargets(
            Map<String, ImageData<BufferedImage>> liveImages,
            List<AutomaticGeometryAdjustment> automaticAdjustments,
            AppliedGeometryResolution explicitResolution
    ) {
        for (AutomaticGeometryAdjustment adjustment : automaticAdjustments) {
            ImageData<BufferedImage> liveImage = liveImages.get(adjustment.slideName());
            PathObject liveObject = liveImage == null
                    ? null
                    : findAnnotationById(liveImage.getHierarchy(), adjustment.objectId());
            if (adjustment.correctedRoi() == null || !(liveObject instanceof PathROIObject)) {
                throw new IllegalStateException("Could not find live annotation for " + adjustment.displayLabel());
            }
        }

        if (explicitResolution == null || !explicitResolution.changed()) {
            return;
        }
        for (ExplicitGeometryChange change : explicitResolution.changes()) {
            ImageData<BufferedImage> liveImage = liveImages.get(change.slideName());
            PathObject liveObject = liveImage == null
                    ? null
                    : findAnnotationById(liveImage.getHierarchy(), change.objectId());
            if (!(liveObject instanceof PathROIObject)) {
                throw new IllegalStateException(
                        "Could not find live annotation " + change.objectId() + " on " + change.slideName()
                );
            }
        }
    }

    private Set<String> commitExplicitGeometryResolution(
            Map<String, ImageData<BufferedImage>> liveImages,
            AppliedGeometryResolution resolution
    ) {
        if (resolution == null || !resolution.changed()) {
            return Set.of();
        }

        Set<String> changedSlides = new HashSet<>();
        for (ExplicitGeometryChange change : resolution.changes()) {
            ImageData<BufferedImage> liveImage = liveImages.get(change.slideName());
            if (liveImage == null) {
                throw new IllegalStateException("Missing live image for " + change.slideName());
            }

            PathObject liveObject = findAnnotationById(
                    liveImage.getHierarchy(),
                    change.objectId()
            );
            if (liveObject == null) {
                throw new IllegalStateException(
                        "Could not find live annotation " + change.objectId() + " on " + change.slideName()
                );
            }

            boolean changed = commitResolvedExportGeometry(liveImage, liveObject, change);
            if (changed) {
                changedSlides.add(change.slideName());
            }
        }
        return changedSlides;
    }

    private boolean commitResolvedExportGeometry(
            ImageData<BufferedImage> liveImage,
            PathObject liveObject,
            ExplicitGeometryChange change
    ) {
        return switch (change.mode()) {
            case MERGE_COMPONENTS -> {
                if (!(liveObject instanceof PathROIObject pathRoiObject) || change.resolvedRoi() == null) {
                    yield false;
                }
                pathRoiObject.setROI(change.resolvedRoi());
                liveImage.getHierarchy().updateObject(liveObject, false);
                yield true;
            }
            case KEEP_COMPONENTS -> replaceWithExportResolvedComponents(
                    liveImage.getHierarchy(),
                    liveObject,
                    change.replacements()
            );
            case SKIP_ANNOTATIONS, MANUAL_DECIDE -> false;
        };
    }

    private boolean replaceWithExportResolvedComponents(
            PathObjectHierarchy hierarchy,
            PathObject source,
            List<PathObject> exportReplacements
    ) {
        if (hierarchy == null || source == null || exportReplacements == null || exportReplacements.isEmpty()) {
            return false;
        }

        List<PathObject> replacements = exportReplacements.stream()
                .map(replacement -> PathObjectTools.transformObjectRecursive(
                        replacement,
                        null,
                        true,
                        false
                ))
                .toList();
        hierarchy.removeObject(source, true);
        hierarchy.insertPathObjects(replacements);
        return true;
    }

    private PathObject findAnnotationById(PathObjectHierarchy hierarchy, String objectId) {
        if (hierarchy == null || objectId == null || objectId.isBlank()) {
            return null;
        }
        for (PathObject annotation : hierarchy.getAnnotationObjects()) {
            if (objectId.equals(ProjectAnnotationService.getPathObjectId(annotation))) {
                return annotation;
            }
        }
        return null;
    }

    private void persistChangedImages(
            Project<?> project,
            Map<String, ImageData<BufferedImage>> liveImages,
            Set<String> changedSlides
    ) throws Exception {
        for (String slideName : changedSlides) {
            ImageData<BufferedImage> imageData = liveImages.get(slideName);
            var projectEntry = ProjectAnnotationService.findProjectEntryByName(project, slideName);
            if (imageData == null || projectEntry == null) {
                throw new IllegalStateException("Could not persist changes for " + slideName);
            }
            projectEntry.saveImageData(imageData);
        }
    }

    private GeometryHandlingChoice showGeometryHandlingDialog(
            List<GeometryIssue> geometryIssues
    ) {
        int geometryAnnotationCount = geometryIssues.size();
        boolean containsHoles = geometryIssues.stream().anyMatch(GeometryIssue::hasHoles);
        boolean containsDisconnectedComponents = geometryIssues.stream()
                .anyMatch(GeometryIssue::hasDisconnectedComponents);

        Dialog<GeometryHandlingChoice> dialog = new Dialog<>();
        dialog.setTitle(ShapeXResources.get("geometry.dialog.title"));
        dialog.setHeaderText(ShapeXResources.get("geometry.dialog.attention"));

        var owner = ControllerDialogs.resolveOwner(ownerNode);
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ButtonType exportButton = new ButtonType(
                ShapeXResources.get("geometry.dialog.continue"),
                ButtonBar.ButtonData.OK_DONE
        );
        dialog.getDialogPane().getButtonTypes().addAll(exportButton, ButtonType.CANCEL);

        ToggleGroup group = new ToggleGroup();
        String repairOptionKey = containsHoles
                ? containsDisconnectedComponents
                        ? "geometry.option.merge_components_and_fill_holes"
                        : "geometry.option.fill_holes"
                : "geometry.option.merge_components";
        RadioButton repairGeometry = createGeometryOption(
                ShapeXResources.get(repairOptionKey),
                GeometryResolutionMode.MERGE_COMPONENTS,
                group
        );
        repairGeometry.setSelected(true);

        RadioButton skipAnnotations = createGeometryOption(
                ShapeXResources.get("geometry.option.skip_annotations"),
                GeometryResolutionMode.SKIP_ANNOTATIONS,
                group
        );
        RadioButton manualDecide = createGeometryOption(
                ShapeXResources.get("geometry.option.manual_decide"),
                GeometryResolutionMode.MANUAL_DECIDE,
                group
        );

        Label issueCount = wrapLabel(geometryAnnotationCount == 1
                ? ShapeXResources.get("geometry.dialog.issue.single")
                : ShapeXResources.format(
                        "geometry.dialog.issue.multiple",
                        geometryAnnotationCount
                ));
        Label choicePrompt = wrapLabel(ShapeXResources.get("geometry.dialog.prompt"));

        VBox radioOptions = new VBox(
                8
        );
        radioOptions.getChildren().add(repairGeometry);
        if (!containsHoles) {
            radioOptions.getChildren().add(createGeometryOption(
                    ShapeXResources.get("geometry.option.keep_components"),
                    GeometryResolutionMode.KEEP_COMPONENTS,
                    group
            ));
        }
        radioOptions.getChildren().addAll(skipAnnotations, manualDecide);

        CheckBox addFixesToQuPath = new CheckBox(
                ShapeXResources.get("geometry.dialog.add_fixes_to_qupath")
        );
        addFixesToQuPath.setSelected(true);
        addFixesToQuPath.setTooltip(new Tooltip(
                ShapeXResources.get("geometry.dialog.add_fixes_to_qupath.tooltip")
        ));
        group.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
            Object selectedMode = newToggle == null ? null : newToggle.getUserData();
            addFixesToQuPath.setDisable(
                    selectedMode == GeometryResolutionMode.SKIP_ANNOTATIONS
            );
        });

        VBox options = new VBox(
                10,
                issueCount,
                choicePrompt,
                radioOptions,
                new Separator(),
                addFixesToQuPath
        );
        options.setPadding(new Insets(14, 18, 10, 18));

        dialog.getDialogPane().setContent(options);
        dialog.getDialogPane().setPrefWidth(520);
        dialog.setResultConverter(button -> {
            if (button != exportButton) {
                return null;
            }
            if (!(group.getSelectedToggle() instanceof RadioButton selected)) {
                return new GeometryHandlingChoice(
                        GeometryResolutionMode.MERGE_COMPONENTS,
                        addFixesToQuPath.isSelected()
                );
            }
            Object mode = selected.getUserData();
            GeometryResolutionMode resolutionMode =
                    mode instanceof GeometryResolutionMode geometryResolutionMode
                            ? geometryResolutionMode
                            : GeometryResolutionMode.MERGE_COMPONENTS;
            return new GeometryHandlingChoice(
                    resolutionMode,
                    resolutionMode != GeometryResolutionMode.SKIP_ANNOTATIONS
                            && addFixesToQuPath.isSelected()
            );
        });

        return dialog.showAndWait().orElse(null);
    }

    private RadioButton createGeometryOption(
            String text,
            GeometryResolutionMode mode,
            ToggleGroup group
    ) {
        RadioButton radioButton = new RadioButton(text);
        radioButton.setToggleGroup(group);
        radioButton.setUserData(mode);
        radioButton.setWrapText(true);
        radioButton.setMaxWidth(Double.MAX_VALUE);
        return radioButton;
    }

    private Label wrapLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }
}
