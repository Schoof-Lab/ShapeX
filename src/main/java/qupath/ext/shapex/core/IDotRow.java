package qupath.ext.shapex.core;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class IDotRow {

    private final StringProperty name = new SimpleStringProperty();
    private final DoubleProperty volume = new SimpleDoubleProperty();
    private final StringProperty liquidName = new SimpleStringProperty();

    public IDotRow(String name, double volume, String liquidName) {
        this.name.set(name);
        this.volume.set(volume);
        this.liquidName.set(liquidName);
    }

    public StringProperty nameProperty() { return name; }
    public DoubleProperty volumeProperty() { return volume; }
    public StringProperty liquidNameProperty() { return liquidName; }

    public String getName() { return name.get(); }
    public double getVolume() { return volume.get(); }
    public String getLiquidName() { return liquidName.get(); }

    public void setName(String value) { name.set(value); }
    public void setVolume(double value) { volume.set(value); }
    public void setLiquidName(String value) { liquidName.set(value); }
}
