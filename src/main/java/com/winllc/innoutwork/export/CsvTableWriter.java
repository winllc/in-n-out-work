package com.winllc.innoutwork.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RFC 4180 CSV, UTF-8 with a byte-order mark (so spreadsheet programs read accents right), CRLF line ends. Every
 * text cell is quoted when it needs to be, and guarded against formula injection: a cell that starts with
 * {@code = + - @}, a tab or a carriage return (or their full-width forms) is prefixed with an apostrophe, so a
 * spreadsheet shows it as text instead of running it; so is one that starts so after leading blanks (spaces,
 * no-break spaces, tabs, line breaks), and one starting with {@code |}. Numbers are written as numbers.
 */
public class CsvTableWriter implements TableWriter {

    private final Writer out;

    public CsvTableWriter(OutputStream stream) throws IOException {
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 64 * 1024);
        out.write('﻿');
    }

    @Override
    public void header(List<String> names) throws IOException {
        row(names);
    }

    @Override
    public void row(List<?> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            Object cell = cells.get(i);
            if (cell instanceof Number number) {
                out.write(number.toString());
            } else if (cell != null) {
                out.write(quote(guard(cell.toString())));
            }
        }
        out.write("\r\n");
    }

    @Override
    public void close() throws IOException {
        out.flush();
    }

    /** {@code value} made safe to open in a spreadsheet: see the class comment. */
    public static String guard(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // Spreadsheets skip leading blanks (spaces, no-break spaces, tabs, line breaks) before reading a formula.
        int i = 0;
        while (i < value.length() && blank(value.charAt(i))) {
            i++;
        }
        boolean formula = false;
        if (i < value.length()) {
            formula = switch (value.charAt(i)) {
                case '=', '+', '-', '@', '|', '＝', '＋', '－', '＠', '｜' -> true;
                default -> false;
            };
        }
        // A cell starting with a tab or carriage return is guarded whatever follows, as before.
        boolean control = value.charAt(0) == '\t' || value.charAt(0) == '\r';
        return formula || control ? "'" + value : value;
    }

    private static boolean blank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == ' ' || c == ' '
                || c == ' ' || c == '﻿';
    }

    static String quote(String value) {
        boolean needs = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.startsWith(" ") || value.endsWith(" ");
        return needs ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
