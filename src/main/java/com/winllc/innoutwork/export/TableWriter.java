package com.winllc.innoutwork.export;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * A table written out row by row as it is produced, so a large export never sits in memory: CSV
 * ({@link CsvTableWriter}) or XLSX ({@link XlsxTableWriter}). Cells are strings, numbers (Long, Integer,
 * Double) or null for an empty cell.
 */
public interface TableWriter extends Closeable {

    void header(List<String> names) throws IOException;

    void row(List<?> cells) throws IOException;

    /** Finishes the file; the stream underneath is flushed, not closed. */
    @Override
    void close() throws IOException;

    /** The formats a table can be downloaded in. */
    enum Format {
        CSV("csv", "text/csv; charset=UTF-8"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        private final String extension;
        private final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public String contentType() {
            return contentType;
        }

        /** {@code csv} or {@code xlsx}, any case; null for anything else. */
        public static Format of(String name) {
            if (name == null) {
                return null;
            }
            for (Format f : values()) {
                if (f.extension.equalsIgnoreCase(name.trim())) {
                    return f;
                }
            }
            return null;
        }

        public TableWriter open(java.io.OutputStream out, String sheetName) throws IOException {
            return this == CSV ? new CsvTableWriter(out) : new XlsxTableWriter(out, sheetName);
        }
    }
}
