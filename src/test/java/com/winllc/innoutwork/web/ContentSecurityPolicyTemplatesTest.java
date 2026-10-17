package com.winllc.innoutwork.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The templates and scripts keep to what the Content-Security-Policy allows, checked in the source so that a
 * page no rendering test reaches is covered too: every inline script carries the request's nonce, and nothing
 * uses an inline event handler or a javascript: URL, which the policy blocks however they got there.
 */
class ContentSecurityPolicyTemplatesTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path SCRIPTS = Path.of("src/main/resources/static/js");

    private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b[^>]*>");
    private static final Pattern INLINE_HANDLER = Pattern.compile("\\son[a-z]+\\s*=\\s*[\"']", Pattern.CASE_INSENSITIVE);

    private static List<Path> files(Path root, String suffix) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void everyInlineScriptCarriesTheNonce() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path template : files(TEMPLATES, ".html")) {
            Matcher tag = SCRIPT_TAG.matcher(Files.readString(template));
            while (tag.find()) {
                String script = tag.group();
                if (!script.contains("src=") && !script.contains("th:attr=\"nonce=${cspNonce}\"")) {
                    missing.add(TEMPLATES.relativize(template) + ": " + script);
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void noTemplateUsesAnInlineEventHandlerOrAJavascriptUrl() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path template : files(TEMPLATES, ".html")) {
            String html = Files.readString(template);
            Matcher handler = INLINE_HANDLER.matcher(html);
            while (handler.find()) {
                found.add(TEMPLATES.relativize(template) + ": " + handler.group().strip());
            }
            if (html.toLowerCase().contains("javascript:")) {
                found.add(TEMPLATES.relativize(template) + ": javascript: URL");
            }
        }
        assertEquals(List.of(), found);
    }

    /** Markup a script builds is held to the same rule: an onclick="..." in a string would not run either. */
    @Test
    void noScriptBuildsMarkupWithAnInlineHandler() throws IOException {
        for (Path script : files(SCRIPTS, ".js")) {
            String js = Files.readString(script);
            assertFalse(Pattern.compile("<[a-z][^>]*\\son[a-z]+=", Pattern.CASE_INSENSITIVE).matcher(js).find(),
                    script.toString());
        }
    }
}
