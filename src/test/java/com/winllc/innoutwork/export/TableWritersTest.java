package com.winllc.innoutwork.export;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV and XLSX: well formed, streamed, and never a formula from a cell's text. */
class TableWritersTest {

    private static final List<String> DANGEROUS = List.of("=HYPERLINK(\"http://evil.example\",\"x\")", "+1+1", "-2+3",
            "@SUM(A1)", "\tTAB", "\rCR", "＝1+1", "=cmd|' /C calc'!A0");

    @Test
    void csvGuardsFormulasQuotesAndKeepsNumbers() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TableWriter w = TableWriter.Format.CSV.open(out, "x")) {
            w.header(List.of("Name", "Value"));
            for (String d : DANGEROUS) {
                w.row(Arrays.asList(d, 5));
            }
            w.row(Arrays.asList("Doe, Jane \"JD\"", null));
            w.row(Arrays.asList("Zoë", -3));
        }
        String csv = out.toString(StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿Name,Value\r\n"), "BOM and CRLF");
        String[] lines = csv.substring(1).split("\r\n");
        for (int i = 0; i < DANGEROUS.size(); i++) {
            String cell = lines[i + 1].substring(0, lines[i + 1].lastIndexOf(','));
            String unquoted = cell.startsWith("\"") ? cell.substring(1, cell.length() - 1).replace("\"\"", "\"") : cell;
            assertTrue(unquoted.startsWith("'"), "guarded: " + cell);
        }
        assertTrue(csv.contains("\"Doe, Jane \"\"JD\"\"\",\r\n"), csv);
        assertTrue(csv.contains("Zoë,-3\r\n"), "numbers are not text, so not guarded");
        assertEquals("plain", CsvTableWriter.guard("plain"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {" =1+1", "  +1", "\u00A0=cmd", "\t=1", "\r@SUM(A1)",
            "\n-1", " \u00A0 \t=HYPERLINK(1)", "|cmd", " |cmd", "\uFF5Ccmd", "\u2007=1", "\uFEFF=1"})
    void leadingBlanksDoNotHideAFormula(String value) {
        assertTrue(CsvTableWriter.guard(value).startsWith("'"), "not guarded: [" + value + "]");
    }

    @Test
    void plainTextIsLeftAlone() {
        for (String value : List.of("Jane Doe", " leading space", "a=b", "1-2", "  ", "\u00A0")) {
            assertEquals(value, CsvTableWriter.guard(value));
        }
    }

    @Test
    void xlsxIsAWellFormedWorkbookWithInlineStringsAndNoFormulas() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TableWriter w = TableWriter.Format.XLSX.open(out, "Attendance/2026")) {
            w.header(List.of("Name", "Minutes"));
            for (String d : DANGEROUS) {
                w.row(Arrays.asList(d, 480));
            }
            w.row(Arrays.asList("<b>&amp;\u0001", null));
        }
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                parts.put(e.getName(), zip.readAllBytes());
            }
        }
        for (String part : List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml")) {
            assertTrue(parts.containsKey(part), part);
            parse(parts.get(part));
        }
        String sheetXml = new String(parts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertFalse(sheetXml.contains("<f>") || sheetXml.contains("<f "), "no formulas");
        Document sheet = parse(parts.get("xl/worksheets/sheet1.xml"));
        NodeList cells = sheet.getElementsByTagName("c");
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < cells.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) cells.item(i);
            if ("inlineStr".equals(c.getAttribute("t"))) {
                texts.add(c.getTextContent());
            } else {
                assertEquals("480", c.getTextContent());
            }
        }
        assertTrue(texts.containsAll(DANGEROUS.stream().map(d -> d.replace("\r", "\n")).toList())
                || texts.containsAll(DANGEROUS), texts.toString());
        assertTrue(texts.contains("<b>&amp;"), "markup kept as text, the control character dropped: " + texts);
        assertTrue(new String(parts.get("xl/workbook.xml"), StandardCharsets.UTF_8).contains("name=\"Attendance 2026\""));
        assertEquals("A", XlsxTableWriter.column(0));
        assertEquals("Z", XlsxTableWriter.column(25));
        assertEquals("AA", XlsxTableWriter.column(26));
        assertEquals("BA", XlsxTableWriter.column(52));
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
