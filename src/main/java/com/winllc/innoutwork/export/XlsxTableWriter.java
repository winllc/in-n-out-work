package com.winllc.innoutwork.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A one-sheet Office Open XML workbook (.xlsx), written as it goes: the sheet's rows stream straight into the zip,
 * so memory does not grow with the table. Text cells are inline strings ({@code t="inlineStr"}), which a
 * spreadsheet never evaluates, so no cell can carry a formula; numbers are numeric cells. The header row is bold and
 * frozen. No library: the few parts a minimal workbook needs are written here.
 */
public class XlsxTableWriter implements TableWriter {

    /** Excel's limits. */
    static final int MAX_ROWS = 1_048_576;
    static final int MAX_CELL = 32_767;

    private final ZipOutputStream zip;
    private final Writer sheet;
    private int rowNumber;

    public XlsxTableWriter(OutputStream stream, String sheetName) throws IOException {
        this.zip = new ZipOutputStream(stream, StandardCharsets.UTF_8);
        String name = sheetName == null || sheetName.isBlank() ? "Report" : sheetName.replaceAll("[\\\\/?*\\[\\]:]", " ");
        if (name.length() > 31) {
            name = name.substring(0, 31);
        }
        part("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>\
                </Types>""");
        part("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                </Relationships>""");
        part("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets></workbook>""".formatted(escape(name)));
        part("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\
                </Relationships>""");
        part("xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>\
                <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>\
                <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
                <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
                <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>\
                </styleSheet>""");
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
        this.sheet = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        sheet.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\""
                + " state=\"frozen\"/></sheetView></sheetViews><sheetData>");
    }

    private void part(String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Override
    public void header(List<String> names) throws IOException {
        write(names, true);
    }

    @Override
    public void row(List<?> cells) throws IOException {
        write(cells, false);
    }

    private void write(List<?> cells, boolean bold) throws IOException {
        if (rowNumber >= MAX_ROWS) {
            throw new IOException("More rows than a spreadsheet holds (" + MAX_ROWS + "): export a shorter period");
        }
        rowNumber++;
        StringBuilder b = new StringBuilder("<row r=\"").append(rowNumber).append("\">");
        for (int i = 0; i < cells.size(); i++) {
            Object cell = cells.get(i);
            if (cell == null) {
                continue;
            }
            String ref = column(i) + rowNumber;
            if (cell instanceof Number number && !bold) {
                b.append("<c r=\"").append(ref).append("\"><v>").append(number).append("</v></c>");
            } else {
                String text = cell.toString();
                if (text.length() > MAX_CELL) {
                    text = text.substring(0, MAX_CELL);
                }
                b.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(bold ? " s=\"1\"" : "")
                        .append("><is><t xml:space=\"preserve\">").append(escape(text)).append("</t></is></c>");
            }
        }
        b.append("</row>");
        sheet.write(b.toString());
    }

    @Override
    public void close() throws IOException {
        sheet.write("</sheetData></worksheet>");
        sheet.flush();
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }

    /** A, B, ... Z, AA, AB, ... */
    static String column(int index) {
        StringBuilder b = new StringBuilder();
        int i = index;
        do {
            b.insert(0, (char) ('A' + i % 26));
            i = i / 26 - 1;
        } while (i >= 0);
        return b.toString();
    }

    /** XML text: markup escaped, and characters XML 1.0 cannot hold dropped. */
    static String escape(String text) {
        StringBuilder b = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF)) {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
