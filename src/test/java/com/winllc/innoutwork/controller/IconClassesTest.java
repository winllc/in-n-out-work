package com.winllc.innoutwork.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code ti ti-*} icon a template uses must exist in the bundled Tabler Icons font. An unknown
 * class raises no error anywhere: the icon just renders as nothing, which is easy to miss.
 */
class IconClassesTest {

    private static final Pattern ICON_USE = Pattern.compile("\\bti ti-([a-z0-9-]+)");
    private static final Pattern ICON_DEFINITION = Pattern.compile("\\.ti-([a-z0-9-]+):before");

    @Test
    void everyIconUsedInATemplateExistsInTheIconFont() throws Exception {
        String css = new ClassPathResource("static/css/icons/tabler-icons/tabler-icons.css")
                .getContentAsString(StandardCharsets.UTF_8);
        Set<String> defined = new TreeSet<>();
        Matcher definitions = ICON_DEFINITION.matcher(css);
        while (definitions.find()) {
            defined.add(definitions.group(1));
        }
        assertFalse(defined.isEmpty(), "no icon definitions found in the icon stylesheet");

        TreeMap<String, String> missing = new TreeMap<>();
        Resource[] templates = new PathMatchingResourcePatternResolver().getResources("classpath:templates/**/*.html");
        assertTrue(templates.length > 0, "no templates found");
        for (Resource template : templates) {
            Matcher uses = ICON_USE.matcher(template.getContentAsString(StandardCharsets.UTF_8));
            while (uses.find()) {
                if (!defined.contains(uses.group(1))) {
                    missing.put("ti-" + uses.group(1), template.getFilename());
                }
            }
        }

        assertTrue(missing.isEmpty(), "icons not in the bundled Tabler Icons font (icon=template): " + missing);
    }
}
