package com.winllc.innoutwork.export;

/** File names for downloads and attachments. */
public final class ExportFileNames {

    private ExportFileNames() {
    }

    /** Letters, digits and dashes only, lower case, at most 40 characters; "export" for nothing left. */
    public static String slug(String text) {
        String s = text == null ? "" : text.replaceAll("[^A-Za-z0-9]+", "-");
        // Leading and trailing dashes trimmed by hand: a regex anchored at the end would be quadratic.
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        s = s.substring(start, end);
        s = s.length() > 40 ? s.substring(0, 40) : s;
        return s.isEmpty() ? "export" : s.toLowerCase(java.util.Locale.ROOT);
    }
}
