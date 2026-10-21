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
 * spreadsheet shows it as text instead of running it. Numbers are written as numbers.
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
        char first = value.charAt(0);
        return switch (first) {
            case '=', '+', '-', '@', '\t', '\r', '\n', '＝', '＋', '－', '＠' -> "'" + value;
            default -> value;
        };
    }

    static String quote(String value) {
        boolean needs = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.startsWith(" ") || value.endsWith(" ");
        return needs ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
