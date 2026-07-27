package qupath.ext.shapex.core;

import io.jhdf.HdfFile;
import io.jhdf.api.WritableGroup;

import org.locationtech.jts.geom.Geometry;
import qupath.ext.shapex.ShapeXResources;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Serializes plate-assigned QuPath annotations using the anndata H5AD encoding.
 */
public final class AnndataAnnotationWriter {

    private AnndataAnnotationWriter() {}

    private static final String INDEX_COLUMN = "_index";
    private static final String GEOMETRY_WKT_COLUMN = "geometry_wkt";
    // Negative precision requests up to N fractional digits without zero-padding.
    private static final int ANNOTATION_DISPLAY_DECIMAL_PLACES = -4;

    // Used to namespace keys that occur in more than one source table.
    private enum ObservationSource {
        IMAGE("Image"),
        ANNOTATION("Annotation"),
        EXTENSION("ShapeX");

        private final String label;

        ObservationSource(String label) {
            this.label = label;
        }

        String prefixed(String key) {
            return label + ": " + key;
        }
    }

    private enum ObservationType {
        STRING,
        DOUBLE
    }

    private interface FeatureValueExtractor {
        double value(AnnotationRow row);
    }

    private interface DataFrameColumn {
        String name();
        int size();
    }

    private record StringColumn(String name, List<String> values) implements DataFrameColumn {
        @Override
        public int size() {
            return values.size();
        }
    }

    private record DoubleColumn(String name, double[] values) implements DataFrameColumn {
        @Override
        public int size() {
            return values.length;
        }
    }

    private record ObservationColumn(
            ObservationSource source,
            String originalKey,
            String storedName,
            ObservationType type
    ) {}

    private record ObservationTable(
            List<DataFrameColumn> dataColumns,
            List<ObservationColumn> schema
    ) {}

    private record AnnotationModel(
            ObservableMeasurementTableData model,
            Set<String> names,
            Map<String, Boolean> numericByName
    ) {}

    private record Feature(
            String id,
            String unit,
            String description,
            String source,
            FeatureValueExtractor extractor
    ) {}

    private record AnnotationRow(
            String slideName,
            ImageData<?> imageData,
            PathObject pathObject,
            String objectId,
            String annotationLabel,
            int plateNumber,
            String well,
            double areaPx2,
            double areaUm2,
            double lengthPx,
            double lengthUm,
            double centroidX,
            double centroidY,
            double boundsX,
            double boundsY,
            double boundsWidth,
            double boundsHeight,
            double solidity,
            int pointCount,
            String geometryWkt,
            Map<String, Double> measurements
    ) {}

    public static Path generateAnndata(
            Path outputDir,
            MultiPlateAssignmentModel plateSet,
            Map<String, ? extends ImageData<?>> imageDataBySlide,
            int rows,
            int cols,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses,
            Map<String, ? extends Set<String>> skippedAnnotationIdsBySlide
    ) throws IOException {
        if (outputDir == null) {
            throw new IOException(ShapeXResources.get("error.output.base.directory.not_set"));
        }

        Path anndataDir = outputDir.resolve(ShapeXResources.get("anndata.dir.name"));
        Files.createDirectories(anndataDir);

        List<AnnotationRow> annotationRows = collectAnnotationRows(
                plateSet,
                imageDataBySlide,
                includeAllAnnotations,
                mapImageToPlate,
                poolClasses,
                skippedAnnotationIdsBySlide
        );
        if (annotationRows.isEmpty()) {
            throw new IOException(ShapeXResources.get("error.anndata.no_annotations"));
        }

        ObservationTable observationTable = buildObservationTable(annotationRows);
        List<Feature> features = buildFeatures(annotationRows);
        Path store = anndataDir.resolve(ShapeXResources.get("anndata.file.base") + ".h5ad");
        replaceExistingPath(store);

        try (var hdfFile = HdfFile.write(store)) {
            hdfFile.putAttribute("encoding-type", "anndata");
            hdfFile.putAttribute("encoding-version", "0.1.0");

            writeObservationDataFrame(hdfFile, annotationRows, observationTable);
            writeVariableDataFrame(hdfFile, features);
            writeX(hdfFile, annotationRows, features);

            WritableGroup obsm = writeDictGroup(hdfFile, "obsm");
            writeDoubleMatrixDataset(
                    obsm,
                    "spatial",
                    buildSpatialMatrix(annotationRows),
                    annotationRows.size(),
                    2
            );
            writeDoubleMatrixDataset(
                    obsm,
                    "bbox",
                    buildBoundingBoxMatrix(annotationRows),
                    annotationRows.size(),
                    4
            );

            WritableGroup uns = writeDictGroup(hdfFile, "uns");
            writeShapeXMetadata(
                    uns.putGroup("shapex"),
                    annotationRows.size(),
                    features.size(),
                    rows,
                    cols,
                    includeAllAnnotations,
                    mapImageToPlate,
                    poolClasses,
                    observationTable.schema()
            );
        }

        return store;
    }

    private static List<AnnotationRow> collectAnnotationRows(
            MultiPlateAssignmentModel plateSet,
            Map<String, ? extends ImageData<?>> imageDataBySlide,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses,
            Map<String, ? extends Set<String>> skippedAnnotationIdsBySlide
    ) {
        if (plateSet == null || imageDataBySlide == null || imageDataBySlide.isEmpty()) {
            return List.of();
        }

        Map<String, PlateLocation> sampleLocations = plateSet.getEnabledSampleLocations();
        List<String> slideNames = new ArrayList<>(imageDataBySlide.keySet());
        slideNames.sort(String.CASE_INSENSITIVE_ORDER);

        List<AnnotationRow> rows = new ArrayList<>();
        for (String slideName : slideNames) {
            ImageData<?> imageData = imageDataBySlide.get(slideName);
            if (imageData == null || imageData.getHierarchy() == null) {
                continue;
            }

            Set<String> skippedIds = Set.of();
            if (skippedAnnotationIdsBySlide != null && skippedAnnotationIdsBySlide.containsKey(slideName)) {
                skippedIds = skippedAnnotationIdsBySlide.get(slideName);
            }
            PixelCalibration calibration = imageData.getServer().getPixelCalibration();

            List<PathObject> annotations = new ArrayList<>(imageData.getHierarchy().getAnnotationObjects());
            annotations.sort(Comparator
                    .comparing(ProjectAnnotationService::getTessellatorAnnotationLabel, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ProjectAnnotationService::getPathObjectId));

            for (PathObject obj : annotations) {
                if (obj == null || obj.getROI() == null || !obj.getROI().isArea()) {
                    continue;
                }

                String objectId = ProjectAnnotationService.getPathObjectId(obj);
                if (skippedIds.contains(objectId)) {
                    continue;
                }

                String sampleKey = AnnotationUtils.getSampleKey(
                        obj,
                        slideName,
                        includeAllAnnotations,
                        mapImageToPlate,
                        poolClasses
                );
                if (sampleKey == null || sampleKey.isBlank()) {
                    continue;
                }

                PlateLocation location = sampleLocations.get(sampleKey);
                if (location == null) {
                    continue;
                }
                if (plateSet.getExcludedWells(location.plateIndex()).contains(location.well())) {
                    continue;
                }

                rows.add(toAnnotationRow(
                        obj,
                        slideName,
                        imageData,
                        objectId,
                        location,
                        calibration
                ));
            }
        }

        // Defines the canonical row order shared by obs, X and obsm.
        rows.sort(Comparator
                .comparingInt(AnnotationRow::plateNumber)
                .thenComparing(AnnotationRow::well, PlateLayoutUtils.wellComparator())
                .thenComparing(AnnotationRow::slideName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AnnotationRow::annotationLabel, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AnnotationRow::objectId));
        return rows;
    }

    private static AnnotationRow toAnnotationRow(
            PathObject obj,
            String slideName,
            ImageData<?> imageData,
            String objectId,
            PlateLocation location,
            PixelCalibration calibration
    ) {
        ROI roi = obj.getROI();
        String annotationName = obj.getName() == null || obj.getName().isBlank()
                ? ShapeXResources.get("annotation.default_name")
                : obj.getName();
        String annotationLabel = ProjectAnnotationService.getTessellatorAnnotationLabel(obj);
        if (annotationLabel == null || annotationLabel.isBlank()) {
            annotationLabel = annotationName;
        }

        boolean hasMicronCalibration = calibration != null && calibration.hasPixelSizeMicrons();
        double areaUm2 = hasMicronCalibration ? roi.getScaledArea(calibration) : Double.NaN;
        double lengthUm = hasMicronCalibration ? roi.getScaledLength(calibration) : Double.NaN;
        Geometry geometry = roi.getGeometry();

        return new AnnotationRow(
                safeValue(slideName),
                imageData,
                obj,
                safeValue(objectId),
                safeValue(annotationLabel),
                location.plateIndex() + 1,
                safeValue(location.well()),
                roi.getArea(),
                areaUm2,
                roi.getLength(),
                lengthUm,
                roi.getCentroidX(),
                roi.getCentroidY(),
                roi.getBoundsX(),
                roi.getBoundsY(),
                roi.getBoundsWidth(),
                roi.getBoundsHeight(),
                roi.getSolidity(),
                roi.getNumPoints(),
                geometry == null ? "" : geometry.toText(),
                getMeasurements(obj)
        );
    }

    private static List<Feature> buildFeatures(List<AnnotationRow> rows) {
        List<Feature> features = new ArrayList<>();
        features.add(new Feature("area_px2", "px^2", "ROI area in square pixels", "shapex", AnnotationRow::areaPx2));
        features.add(new Feature("area_um2", "um^2", "ROI area in square micrometers when pixel calibration is available", "shapex", AnnotationRow::areaUm2));
        features.add(new Feature("length_px", "px", "ROI boundary length in pixels", "shapex", AnnotationRow::lengthPx));
        features.add(new Feature("length_um", "um", "ROI boundary length in micrometers when pixel calibration is available", "shapex", AnnotationRow::lengthUm));
        features.add(new Feature("centroid_x_px", "px", "ROI centroid x-coordinate in image pixels", "shapex", AnnotationRow::centroidX));
        features.add(new Feature("centroid_y_px", "px", "ROI centroid y-coordinate in image pixels", "shapex", AnnotationRow::centroidY));
        features.add(new Feature("bbox_x_px", "px", "Bounding-box x-coordinate in image pixels", "shapex", AnnotationRow::boundsX));
        features.add(new Feature("bbox_y_px", "px", "Bounding-box y-coordinate in image pixels", "shapex", AnnotationRow::boundsY));
        features.add(new Feature("bbox_width_px", "px", "Bounding-box width in pixels", "shapex", AnnotationRow::boundsWidth));
        features.add(new Feature("bbox_height_px", "px", "Bounding-box height in pixels", "shapex", AnnotationRow::boundsHeight));
        features.add(new Feature("solidity", "", "ROI solidity reported by QuPath", "shapex", AnnotationRow::solidity));
        features.add(new Feature("point_count", "count", "Number of points reported by the ROI", "shapex", row -> row.pointCount()));

        Set<String> measurementNames = new LinkedHashSet<>();
        rows.stream()
                .flatMap(row -> row.measurements().keySet().stream())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(measurementNames::add);

        for (String measurementName : measurementNames) {
            features.add(new Feature(
                    "measurement:" + measurementName,
                    "",
                    "QuPath measurement: " + measurementName,
                    "qupath_measurement",
                    row -> row.measurements().getOrDefault(measurementName, Double.NaN)
            ));
        }

        return features;
    }

    private static ObservationTable buildObservationTable(
            List<AnnotationRow> rows
    ) throws IOException {
        Map<ImageData<?>, List<AnnotationRow>> rowsByImage = new LinkedHashMap<>();
        for (AnnotationRow row : rows) {
            rowsByImage.computeIfAbsent(row.imageData(), ignored -> new ArrayList<>()).add(row);
        }

        Map<ImageData<?>, Map<String, String>> imageValuesByImage = new LinkedHashMap<>();
        Map<ImageData<?>, AnnotationModel> annotationModelsByImage = new LinkedHashMap<>();
        Map<QuPathImageDetailsExtractor.Slot, LinkedHashSet<String>> imageKeysBySlot =
                new EnumMap<>(QuPathImageDetailsExtractor.Slot.class);
        LinkedHashSet<String> annotationKeys = new LinkedHashSet<>();
        Map<String, Boolean> annotationNumericByKey = new LinkedHashMap<>();

        for (var imageEntry : rowsByImage.entrySet()) {
            ImageData<?> imageData = imageEntry.getKey();
            List<AnnotationRow> imageRows = imageEntry.getValue();
            String imageName = imageRows.getFirst().slideName();

            Map<String, String> imageValues = new LinkedHashMap<>();
            for (QuPathImageDetailsExtractor.Entry entry :
                    QuPathImageDetailsExtractor.extract(imageData, imageName)) {
                imageValues.put(entry.key(), entry.value());
                imageKeysBySlot
                        .computeIfAbsent(entry.slot(), ignored -> new LinkedHashSet<>())
                        .add(entry.key());
            }
            imageValuesByImage.put(imageData, imageValues);

            ObservableMeasurementTableData model = new ObservableMeasurementTableData();
            model.setImageData(
                    imageData,
                    imageRows.stream().map(AnnotationRow::pathObject).toList()
            );

            List<String> modelNames = model.getAllNames();
            Set<String> exposedNames = new LinkedHashSet<>(modelNames);
            Map<String, Boolean> localNumericByName = new LinkedHashMap<>();
            for (String name : modelNames) {
                boolean numeric = model.isNumericMeasurement(name);
                localNumericByName.put(name, numeric);
                annotationKeys.add(name);
                // A mixed-type key must be encoded as a string column for the combined DataFrame.
                annotationNumericByKey.merge(name, numeric, (left, right) -> left && right);
            }
            annotationModelsByImage.put(
                    imageData,
                    new AnnotationModel(model, exposedNames, localNumericByName)
            );
        }

        LinkedHashSet<String> imageKeys = new LinkedHashSet<>();
        for (QuPathImageDetailsExtractor.Slot slot : QuPathImageDetailsExtractor.Slot.values()) {
            imageKeys.addAll(imageKeysBySlot.getOrDefault(slot, new LinkedHashSet<>()));
        }

        // Colliding keys are namespaced rather than silently overwriting one source.
        Set<String> crossTabCollisions = new LinkedHashSet<>(imageKeys);
        crossTabCollisions.retainAll(annotationKeys);

        List<ObservationColumn> schema = new ArrayList<>();
        Set<String> usedStoredNames = new LinkedHashSet<>();
        usedStoredNames.add(GEOMETRY_WKT_COLUMN);
        for (String key : imageKeys) {
            schema.add(createObservationColumn(
                    ObservationSource.IMAGE,
                    key,
                    ObservationType.STRING,
                    crossTabCollisions,
                    usedStoredNames
            ));
        }
        for (String key : annotationKeys) {
            ObservationType type = Boolean.TRUE.equals(annotationNumericByKey.get(key))
                    ? ObservationType.DOUBLE
                    : ObservationType.STRING;
            schema.add(createObservationColumn(
                    ObservationSource.ANNOTATION,
                    key,
                    type,
                    crossTabCollisions,
                    usedStoredNames
            ));
        }
        schema.add(new ObservationColumn(
                ObservationSource.EXTENSION,
                GEOMETRY_WKT_COLUMN,
                GEOMETRY_WKT_COLUMN,
                ObservationType.STRING
        ));

        List<DataFrameColumn> dataColumns = new ArrayList<>(schema.size());
        for (ObservationColumn column : schema) {
            if (column.source() == ObservationSource.IMAGE) {
                dataColumns.add(new StringColumn(
                        column.storedName(),
                        rows.stream()
                                .map(row -> imageValuesByImage
                                        .getOrDefault(row.imageData(), Map.of())
                                        .getOrDefault(column.originalKey(), ""))
                                .toList()
                ));
                continue;
            }
            if (column.source() == ObservationSource.EXTENSION) {
                dataColumns.add(new StringColumn(
                        column.storedName(),
                        rows.stream().map(AnnotationRow::geometryWkt).toList()
                ));
                continue;
            }

            if (column.type() == ObservationType.DOUBLE) {
                double[] values = new double[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    AnnotationRow row = rows.get(i);
                    AnnotationModel annotationModel = annotationModelsByImage.get(row.imageData());
                    values[i] = annotationModel != null
                            && annotationModel.names().contains(column.originalKey())
                            && Boolean.TRUE.equals(annotationModel.numericByName().get(column.originalKey()))
                            ? annotationModel.model().getNumericValue(row.pathObject(), column.originalKey())
                            : Double.NaN;
                }
                dataColumns.add(new DoubleColumn(column.storedName(), values));
            } else {
                List<String> values = new ArrayList<>(rows.size());
                for (AnnotationRow row : rows) {
                    AnnotationModel annotationModel = annotationModelsByImage.get(row.imageData());
                    if (annotationModel == null
                            || !annotationModel.names().contains(column.originalKey())) {
                        values.add("");
                    } else {
                        values.add(safeValue(annotationModel.model().getStringValue(
                                row.pathObject(),
                                column.originalKey(),
                                ANNOTATION_DISPLAY_DECIMAL_PLACES
                        )));
                    }
                }
                dataColumns.add(new StringColumn(column.storedName(), values));
            }
        }

        return new ObservationTable(List.copyOf(dataColumns), List.copyOf(schema));
    }

    private static ObservationColumn createObservationColumn(
            ObservationSource source,
            String originalKey,
            ObservationType type,
            Set<String> crossTabCollisions,
            Set<String> usedStoredNames
    ) throws IOException {
        if (originalKey != null && originalKey.indexOf('\u0000') >= 0) {
            throw new IOException(
                    "Cannot export the QuPath key because it contains an unsupported NUL character."
            );
        }
        String requestedName = crossTabCollisions.contains(originalKey)
                ? source.prefixed(originalKey)
                : originalKey;
        String storedName = makeHdfSafeColumnName(requestedName);

        if (!usedStoredNames.add(storedName)) {
            String baseName = storedName;
            int suffix = 2;
            while (!usedStoredNames.add(storedName = baseName + " (" + suffix++ + ")")) {
                // Keep incrementing until the HDF5 dataset name is unique.
            }
        }

        if (storedName.isEmpty()) {
            throw new IOException("Unable to create an anndata column name for QuPath key '" + originalKey + "'.");
        }
        return new ObservationColumn(source, safeValue(originalKey), storedName, type);
    }

    private static String makeHdfSafeColumnName(String name) {
        String value = safeValue(name);
        if (value.isEmpty()) {
            return "%EMPTY";
        }
        if (INDEX_COLUMN.equals(value)) {
            return "%5Findex";
        }
        if (".".equals(value)) {
            return "%2E";
        }
        if ("..".equals(value)) {
            return "%2E%2E";
        }
        return value.replace("/", "%2F");
    }

    private static void writeObservationDataFrame(
            WritableGroup parent,
            List<AnnotationRow> rows,
            ObservationTable observationTable
    ) throws IOException {
        List<String> index = buildObservationIndex(rows.size());
        writeDataFrame(parent.putGroup("obs"), index, observationTable.dataColumns());
    }

    private static List<String> buildObservationIndex(int rowCount) {
        // Keep the DataFrame index readable; persistent identity remains in object_id.
        List<String> index = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            index.add(String.format(Locale.ROOT, "ROI_%03d", i + 1));
        }
        return index;
    }

    private static void writeVariableDataFrame(
            WritableGroup parent,
            List<Feature> features
    ) throws IOException {
        List<String> index = features.stream().map(Feature::id).toList();
        List<DataFrameColumn> columns = List.of(
                new StringColumn("unit", features.stream().map(Feature::unit).toList()),
                new StringColumn("description", features.stream().map(Feature::description).toList()),
                new StringColumn("source", features.stream().map(Feature::source).toList())
        );

        writeDataFrame(parent.putGroup("var"), index, columns);
    }

    private static void writeDataFrame(
            WritableGroup group,
            List<String> index,
            List<DataFrameColumn> columns
    ) throws IOException {
        List<String> columnOrder = columns.stream().map(DataFrameColumn::name).toList();
        group.putAttribute("_index", INDEX_COLUMN);
        group.putAttribute("column-order", columnOrder.toArray(String[]::new));
        group.putAttribute("encoding-type", "dataframe");
        group.putAttribute("encoding-version", "0.2.0");

        writeStringDataset(group, INDEX_COLUMN, index);
        for (DataFrameColumn column : columns) {
            if (column.size() != index.size()) {
                throw new IOException("anndata column '" + column.name() + "' has the wrong length.");
            }
            if (column instanceof StringColumn stringColumn) {
                if (stringColumn.values().stream()
                        .anyMatch(value -> value != null && value.indexOf('\u0000') >= 0)) {
                    throw new IOException(
                            "Cannot export anndata column '" + column.name()
                                    + "' because a value contains an unsupported NUL character."
                    );
                }
                writeStringDataset(group, column.name(), stringColumn.values());
            } else if (column instanceof DoubleColumn doubleColumn) {
                writeDoubleDataset(group, column.name(), doubleColumn.values());
            } else {
                throw new IOException("Unsupported anndata column type: " + column.getClass().getName());
            }
        }
    }

    private static void writeX(
            WritableGroup parent,
            List<AnnotationRow> rows,
            List<Feature> features
    ) throws IOException {
        // Build row-major values before converting to the rectangular array expected by jHDF.
        double[] values = new double[rows.size() * features.size()];
        int offset = 0;
        for (AnnotationRow row : rows) {
            for (Feature feature : features) {
                values[offset++] = feature.extractor().value(row);
            }
        }
        writeDoubleMatrixDataset(parent, "X", values, rows.size(), features.size());
    }

    private static double[] buildSpatialMatrix(List<AnnotationRow> rows) {
        double[] values = new double[rows.size() * 2];
        for (int i = 0; i < rows.size(); i++) {
            AnnotationRow row = rows.get(i);
            values[i * 2] = row.centroidX();
            values[i * 2 + 1] = row.centroidY();
        }
        return values;
    }

    private static double[] buildBoundingBoxMatrix(List<AnnotationRow> rows) {
        double[] values = new double[rows.size() * 4];
        for (int i = 0; i < rows.size(); i++) {
            AnnotationRow row = rows.get(i);
            values[i * 4] = row.boundsX();
            values[i * 4 + 1] = row.boundsY();
            values[i * 4 + 2] = row.boundsWidth();
            values[i * 4 + 3] = row.boundsHeight();
        }
        return values;
    }

    private static void writeShapeXMetadata(
            WritableGroup group,
            int annotationCount,
            int featureCount,
            int plateRows,
            int plateColumns,
            boolean includeAllAnnotations,
            boolean mapImageToPlate,
            boolean poolClasses,
            List<ObservationColumn> observationSchema
    ) throws IOException {
        group.putAttribute("encoding-type", "dict");
        group.putAttribute("encoding-version", "0.1.0");
        writeStringScalar(group, "created_by", "ShapeX");
        writeStringScalar(group, "created_at", ZonedDateTime.now().toString());
        writeStringScalar(group, "format", "anndata H5AD");
        writeStringScalar(group, "coordinate_unit", "pixel");
        writeStringScalar(group, "area_unit", "pixel and micrometer when calibrated");
        writeStringScalar(group, "shapex_version", ShapeXResources.get("extension.shapex.version"));
        writeLongScalar(group, "n_annotations", annotationCount);
        writeLongScalar(group, "n_features", featureCount);
        writeLongScalar(group, "plate_rows", plateRows);
        writeLongScalar(group, "plate_columns", plateColumns);
        writeLongScalar(group, "include_all_annotations", includeAllAnnotations ? 1 : 0);
        writeLongScalar(group, "map_image_to_plate", mapImageToPlate ? 1 : 0);
        writeLongScalar(group, "pool_classes", poolClasses ? 1 : 0);
        writeLongScalar(group, "n_obs_columns", observationSchema.size());
        writeStringDataset(
                group,
                "obs_column_names",
                observationSchema.stream().map(ObservationColumn::storedName).toList()
        );
        writeStringDataset(
                group,
                "obs_column_original_keys",
                observationSchema.stream().map(ObservationColumn::originalKey).toList()
        );
        writeStringDataset(
                group,
                "obs_column_sources",
                observationSchema.stream().map(column -> column.source().label).toList()
        );
    }

    private static Map<String, Double> getMeasurements(PathObject obj) {
        Map<String, Double> measurements = new HashMap<>();
        if (obj == null || !obj.hasMeasurements()) {
            return measurements;
        }

        var measurementList = obj.getMeasurementList();
        for (String name : measurementList.getNames()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            measurements.put(name, measurementList.get(name));
        }
        return measurements;
    }

    private static String safeValue(String value) {
        return value == null ? "" : value;
    }

    private static WritableGroup writeDictGroup(WritableGroup parent, String name) {
        WritableGroup group = parent.putGroup(name);
        group.putAttribute("encoding-type", "dict");
        group.putAttribute("encoding-version", "0.1.0");
        return group;
    }

    private static void writeStringDataset(WritableGroup group, String name, List<String> values) {
        var dataset = group.putDataset(
                name,
                values.stream()
                        .map(AnndataAnnotationWriter::safeValue)
                        .toArray(String[]::new)
        );
        dataset.putAttribute("encoding-type", "string-array");
        dataset.putAttribute("encoding-version", "0.2.0");
    }

    private static void writeDoubleDataset(WritableGroup group, String name, double[] values) {
        var dataset = group.putDataset(name, values);
        dataset.putAttribute("encoding-type", "array");
        dataset.putAttribute("encoding-version", "0.2.0");
    }

    private static void writeDoubleMatrixDataset(
            WritableGroup group,
            String name,
            double[] values,
            int rowCount,
            int columnCount
    ) throws IOException {
        if (values.length != rowCount * columnCount) {
            throw new IOException("anndata matrix has the wrong number of values.");
        }

        var dataset = group.putDataset(name, toDoubleMatrix(values, rowCount, columnCount));
        dataset.putAttribute("encoding-type", "array");
        dataset.putAttribute("encoding-version", "0.2.0");
    }

    private static double[][] toDoubleMatrix(double[] values, int rowCount, int columnCount) {
        double[][] matrix = new double[rowCount][columnCount];
        for (int row = 0; row < rowCount; row++) {
            System.arraycopy(values, row * columnCount, matrix[row], 0, columnCount);
        }
        return matrix;
    }

    private static void writeStringScalar(WritableGroup group, String name, String value) {
        var dataset = group.putDataset(name, safeValue(value));
        dataset.putAttribute("encoding-type", "string");
        dataset.putAttribute("encoding-version", "0.2.0");
    }

    private static void writeLongScalar(WritableGroup group, String name, long value) {
        var dataset = group.putDataset(name, value);
        dataset.putAttribute("encoding-type", "numeric-scalar");
        dataset.putAttribute("encoding-version", "0.2.0");
    }

    private static void replaceExistingPath(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        // Delete children first in case the target is directory-backed.
        try (var walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path existing : paths) {
                Files.delete(existing);
            }
        }
    }
}
