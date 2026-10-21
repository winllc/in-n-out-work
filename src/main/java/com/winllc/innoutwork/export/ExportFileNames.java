package com.winllc.innoutwork.export;

/** File names for downloads and attachments. */
public final class ExportFileNames {

    private ExportFileNames() {
    }

    /** Letters, digits and dashes only, lower case, at most 40 characters; "export" for nothing left. */
    public static String slug(String text) {
        String s = text == null ? "" : text.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        s = s.length() > 40 ? s.substring(0, 40) : s;
        return s.isEmpty() ? "export" : s.toLowerCase(java.util.Locale.ROOT);
    }
}
