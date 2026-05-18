package de.kreisalarm.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TablePrinter {
    private static final int MAX_COL = 45;

    public static void print (JsonNode array, String[] columns) {
        if (!array.isArray () || array.size () == 0) {
            System.out.println ("(keine Einträge)");
            return;
        }

        int[] widths = new int[columns.length];
        for (int i = 0; i < columns.length; i++)
            widths[i] = columns[i].length ();

        List<String[]> rows = new ArrayList<> ();
        for (JsonNode node : array) {
            String[] row = new String[columns.length];
            for (int i = 0; i < columns.length; i++) {
                String val = node.path (columns[i]).asText ("");
                if (val.length () > MAX_COL) val = val.substring (0, MAX_COL - 3) + "...";
                row[i] = val;
                widths[i] = Math.max (widths[i], val.length ());
            }
            rows.add (row);
        }

        printRow (columns, widths);
        printSeparator (widths);
        for (String[] row : rows)
            printRow (row, widths);

        System.out.printf ("%n%d Einträge%n", rows.size ());
    }

    public static void printObject (JsonNode node) {
        if (node == null || node.isNull () || node.isMissingNode ()) {
            System.out.println ("(nicht gefunden)");
            return;
        }

        int keyWidth = 0;
        List<Map.Entry<String, String>> entries = new ArrayList<> ();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields ();
        while (fields.hasNext ()) {
            Map.Entry<String, JsonNode> e = fields.next ();
            if (e.getValue ().isNull () || e.getValue ().isObject () || e.getValue ().isArray ()) continue;
            String val = e.getValue ().asText ();
            if (val.isEmpty ()) continue;
            entries.add (Map.entry (e.getKey (), val));
            keyWidth = Math.max (keyWidth, e.getKey ().length ());
        }

        String fmt = "%-" + keyWidth + "s  %s%n";
        for (Map.Entry<String, String> e : entries)
            System.out.printf (fmt, e.getKey (), e.getValue ());
    }

    private static void printRow (String[] values, int[] widths) {
        StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append ("  ");
            sb.append (String.format ("%-" + widths[i] + "s", values[i] == null ? "" : values[i]));
        }
        System.out.println (sb.toString ().stripTrailing ());
    }

    private static void printSeparator (int[] widths) {
        StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) sb.append ("  ");
            sb.append ("-".repeat (widths[i]));
        }
        System.out.println (sb.toString ());
    }
}
