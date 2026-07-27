package qupath.ext.shapex.ui;

import qupath.ext.shapex.ShapeXResources;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.Region;

import qupath.ext.shapex.core.IDotRow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class IdotTableManager {

    private final TableView<IDotRow> idotTable;
    private final TableColumn<IDotRow, String> idotNameColumn;
    private final TableColumn<IDotRow, Double> idotVolumeColumn;
    private final TableColumn<IDotRow, String> idotLiquidColumn;
    private final Button idotAddRowButton;
    private final Button idotRemoveRowButton;
    private final CheckBox idotEnabledCheckBox;

    IdotTableManager(
            TableView<IDotRow> idotTable,
            TableColumn<IDotRow, String> idotNameColumn,
            TableColumn<IDotRow, Double> idotVolumeColumn,
            TableColumn<IDotRow, String> idotLiquidColumn,
            Button idotAddRowButton,
            Button idotRemoveRowButton,
            CheckBox idotEnabledCheckBox
    ) {
        this.idotTable = idotTable;
        this.idotNameColumn = idotNameColumn;
        this.idotVolumeColumn = idotVolumeColumn;
        this.idotLiquidColumn = idotLiquidColumn;
        this.idotAddRowButton = idotAddRowButton;
        this.idotRemoveRowButton = idotRemoveRowButton;
        this.idotEnabledCheckBox = idotEnabledCheckBox;
    }

    /* ============================================================
     * Initialization
     * ============================================================ */

    void initialize() {
        configureTableColumns();
        populateDefaultRows();
        configureButtons();
        configureSizing();
        bindEnabledState();
    }

    private void configureTableColumns() {
        idotNameColumn.setCellValueFactory(data -> data.getValue().nameProperty());
        idotVolumeColumn.setCellValueFactory(data -> data.getValue().volumeProperty().asObject());
        idotLiquidColumn.setCellValueFactory(data -> data.getValue().liquidNameProperty());

        idotTable.setEditable(true);
        idotNameColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        idotLiquidColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        idotVolumeColumn.setCellFactory(
                TextFieldTableCell.forTableColumn(new javafx.util.converter.DoubleStringConverter())
        );
    }

    private void populateDefaultRows() {
        idotTable.getItems().setAll(
                new IDotRow(ShapeXResources.get("idot.row.lysis"), 1.0, ShapeXResources.get("idot.row.default_liquid")),
                new IDotRow(ShapeXResources.get("idot.row.digestion"), 1.0, ShapeXResources.get("idot.row.default_liquid")),
                new IDotRow(ShapeXResources.get("idot.row.quenching"), 1.0, ShapeXResources.get("idot.row.default_liquid"))
        );
    }

    private void configureButtons() {
        idotAddRowButton.setOnAction(e ->
                idotTable.getItems().add(
                        new IDotRow(ShapeXResources.get("idot.row.new"), 1.0, ShapeXResources.get("idot.row.default_liquid"))
                )
        );

        idotRemoveRowButton.setOnAction(e -> {
            int selected = idotTable.getSelectionModel().getSelectedIndex();
            if (selected >= 0) {
                idotTable.getItems().remove(selected);
            }
        });
    }

    private void configureSizing() {
        double rowHeight = 28.0;
        double headerHeight = 30.0;
        double padding = 2.0;

        idotTable.setFixedCellSize(rowHeight);
        idotTable.prefHeightProperty().bind(
                Bindings.size(idotTable.getItems())
                        .multiply(rowHeight)
                        .add(headerHeight + padding)
        );

        idotTable.setMinHeight(Region.USE_PREF_SIZE);
        idotTable.setMaxHeight(Region.USE_PREF_SIZE);
    }

    private void bindEnabledState() {
        idotTable.disableProperty().bind(idotEnabledCheckBox.selectedProperty().not());
        idotAddRowButton.disableProperty().bind(idotEnabledCheckBox.selectedProperty().not());
        idotRemoveRowButton.disableProperty().bind(idotEnabledCheckBox.selectedProperty().not());
    }

    /* ============================================================
     * Accessors/validation
     * ============================================================ */

    List<IDotRow> getRows() {
        return new ArrayList<>(idotTable.getItems());
    }

    boolean validateUniqueNames() {
        if (!idotEnabledCheckBox.isSelected()) {
            return true;
        }

        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();

        for (IDotRow row : idotTable.getItems()) {
            if (row == null) {
                continue;
            }

            String name = row.getName();
            if (name == null || name.isBlank()) {
                continue;
            }

            String normalized = name.trim();
            if (!seen.add(normalized)) {
                duplicates.add(normalized);
            }
        }

        if (duplicates.isEmpty()) {
            return true;
        }

        StringBuilder message = new StringBuilder();
        message.append(ShapeXResources.get("error.idot.duplicate_names.header"));
        duplicates.forEach(duplicate ->
                message.append("• ").append(duplicate).append("\n"));
        message.append(ShapeXResources.get("error.idot.duplicate_names.footer"));

        ControllerDialogs.showError(idotTable, message.toString());
        return false;
    }
}
