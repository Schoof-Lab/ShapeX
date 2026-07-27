package qupath.ext.shapex.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Shared helpers for converting between plate coordinates and well labels.
 */
public final class PlateLayoutUtils {

    private static final int WELL_1536_ROWS = 32;
    private static final int WELL_1536_COLUMNS = 48;

    private PlateLayoutUtils() {}

    /* ============================================================
     * Row labels
     * ============================================================ */

    public static String rowLabel(int index) {
        StringBuilder sb = new StringBuilder();
        int value = index;

        do {
            int remainder = value % 26;
            sb.insert(0, (char) ('A' + remainder));
            value = value / 26 - 1;
        } while (value >= 0);

        return sb.toString();
    }

    public static int rowLabelToIndex(String rowLabel) {
        if (rowLabel == null || rowLabel.isBlank()) {
            return -1;
        }

        int value = 0;
        for (int i = 0; i < rowLabel.length(); i++) {
            char ch = Character.toUpperCase(rowLabel.charAt(i));
            if (ch < 'A' || ch > 'Z') {
                return -1;
            }
            value = value * 26 + (ch - 'A' + 1);
        }

        return value - 1;
    }

    /* ============================================================
     * Well labels
     * ============================================================ */

    public static String toWellLabel(int rowIndex, int columnIndexOneBased) {
        return rowLabel(rowIndex) + columnIndexOneBased;
    }

    /**
     * Converts ShapeX's internal well label to the cap ID expected by the LMD.
     * The LMD's 1536-well row sequence ends in AA, BB, CC, DD, EE, FF,
     * whereas ShapeX deliberately keeps its preview sequence as AA, AB, ... AF.
     */
    public static String toLmdWellLabel(String well, int rows, int columns) {
        if (!is1536WellPlate(rows, columns)) {
            return well;
        }

        WellPosition position = parseWellLabel(well);
        if (!isWithinPlate(position, rows, columns)) {
            return well;
        }

        int rowIndex = position.rowIndex();
        String deviceRow = rowIndex < 26
                ? Character.toString((char) ('A' + rowIndex))
                : Character.toString((char) ('A' + rowIndex - 26)).repeat(2);
        return deviceRow + position.columnIndex();
    }

    /**
     * Converts ShapeX's internal well label to the target-well notation expected
     * by the i.DOT for a 1536-well plate: Aa, Ab, Ac, Ad, Ba, Bb, ...
     */
    public static String toIDotWellLabel(String well, int rows, int columns) {
        if (!is1536WellPlate(rows, columns)) {
            return well;
        }

        WellPosition position = parseWellLabel(well);
        if (!isWithinPlate(position, rows, columns)) {
            return well;
        }

        int rowIndex = position.rowIndex();
        char majorRow = (char) ('A' + rowIndex / 4);
        char minorRow = (char) ('a' + rowIndex % 4);
        return new String(new char[] {majorRow, minorRow}) + position.columnIndex();
    }

    public static boolean is1536WellPlate(int rows, int columns) {
        return rows == WELL_1536_ROWS && columns == WELL_1536_COLUMNS;
    }

    public static List<String> allWells(int rows, int cols) {
        List<String> wells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String row = rowLabel(r);
            for (int c = 1; c <= cols; c++) {
                wells.add(row + c);
            }
        }
        return wells;
    }

    public static List<String> sortWells(Collection<String> wells) {
        List<String> sorted = new ArrayList<>();
        if (wells != null) {
            sorted.addAll(wells);
        }
        sorted.sort(wellComparator());
        return sorted;
    }

    public static Comparator<String> wellComparator() {
        return (left, right) -> {
            WellPosition leftPos = parseWellLabel(left);
            WellPosition rightPos = parseWellLabel(right);

            if (leftPos == null && rightPos == null) {
                return String.valueOf(left).compareToIgnoreCase(String.valueOf(right));
            }
            if (leftPos == null) {
                return 1;
            }
            if (rightPos == null) {
                return -1;
            }

            int byRow = Integer.compare(leftPos.rowIndex(), rightPos.rowIndex());
            if (byRow != 0) {
                return byRow;
            }

            return Integer.compare(leftPos.columnIndex(), rightPos.columnIndex());
        };
    }

    public static WellPosition parseWellLabel(String well) {
        if (well == null || well.isBlank()) {
            return null;
        }

        int split = 0;
        while (split < well.length() && Character.isLetter(well.charAt(split))) {
            split++;
        }

        if (split == 0 || split >= well.length()) {
            return null;
        }

        String rowText = well.substring(0, split).toUpperCase(Locale.ROOT);
        String colText = well.substring(split);

        int columnIndex;
        try {
            columnIndex = Integer.parseInt(colText);
        } catch (NumberFormatException e) {
            return null;
        }

        int rowIndex = rowLabelToIndex(rowText);
        if (rowIndex < 0 || columnIndex < 1) {
            return null;
        }

        return new WellPosition(rowIndex, columnIndex);
    }

    private static boolean isWithinPlate(WellPosition position, int rows, int columns) {
        return position != null
                && position.rowIndex() < rows
                && position.columnIndex() <= columns;
    }

    public record WellPosition(int rowIndex, int columnIndex) {}
}
