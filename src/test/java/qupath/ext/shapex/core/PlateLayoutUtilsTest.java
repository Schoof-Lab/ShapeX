package qupath.ext.shapex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlateLayoutUtilsTest {

    @Test
    void previewRowLabelsRetainTheShapeXSequence() {
        assertEquals("Z", PlateLayoutUtils.rowLabel(25));
        assertEquals("AA", PlateLayoutUtils.rowLabel(26));
        assertEquals("AB", PlateLayoutUtils.rowLabel(27));
        assertEquals("AF", PlateLayoutUtils.rowLabel(31));
    }

    @Test
    void lmdUsesRepeatedLettersForTheLastSix1536Rows() {
        assertEquals("Z48", PlateLayoutUtils.toLmdWellLabel("Z48", 32, 48));
        assertEquals("AA48", PlateLayoutUtils.toLmdWellLabel("AA48", 32, 48));
        assertEquals("BB48", PlateLayoutUtils.toLmdWellLabel("AB48", 32, 48));
        assertEquals("CC48", PlateLayoutUtils.toLmdWellLabel("AC48", 32, 48));
        assertEquals("FF48", PlateLayoutUtils.toLmdWellLabel("AF48", 32, 48));
    }

    @Test
    void idotUsesMajorAndMinorLettersFor1536Rows() {
        assertEquals("Aa1", PlateLayoutUtils.toIDotWellLabel("A1", 32, 48));
        assertEquals("Ad1", PlateLayoutUtils.toIDotWellLabel("D1", 32, 48));
        assertEquals("Ba17", PlateLayoutUtils.toIDotWellLabel("E17", 32, 48));
        assertEquals("Hd48", PlateLayoutUtils.toIDotWellLabel("AF48", 32, 48));
    }

    @Test
    void deviceMappingsAreGatedOnTheExact1536Dimensions() {
        assertEquals("AB48", PlateLayoutUtils.toLmdWellLabel("AB48", 31, 48));
        assertEquals("AB48", PlateLayoutUtils.toLmdWellLabel("AB48", 32, 47));
        assertEquals("AB48", PlateLayoutUtils.toLmdWellLabel("AB48", 32, 49));
        assertEquals("A1", PlateLayoutUtils.toIDotWellLabel("A1", 8, 12));
        assertEquals("A1", PlateLayoutUtils.toIDotWellLabel("A1", 16, 24));
        assertEquals("A1", PlateLayoutUtils.toIDotWellLabel("A1", 32, 47));
    }

    @Test
    void malformedOrOutOfBoundsLabelsAreNotRewritten() {
        assertEquals("not-a-well", PlateLayoutUtils.toLmdWellLabel("not-a-well", 32, 48));
        assertEquals("AG1", PlateLayoutUtils.toLmdWellLabel("AG1", 32, 48));
        assertEquals("A49", PlateLayoutUtils.toIDotWellLabel("A49", 32, 48));
    }
}
