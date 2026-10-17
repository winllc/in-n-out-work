package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.EnsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The notice-and-consent banner as configured: whether it is on, its title, and its text as paragraphs. The text
 * comes from ENS_CONSENT_BANNER_FILE when set (read once, at startup: an unreadable file stops startup rather
 * than show no banner), else ENS_CONSENT_BANNER_TEXT, else the standard DoD text.
 */
public class ConsentBanner {

    private static final Logger log = LoggerFactory.getLogger(ConsentBanner.class);

    /** The session attribute recording when this browser session acknowledged the banner. */
    public static final String ACKNOWLEDGED = ConsentBanner.class.getName() + ".acknowledged";

    private final boolean enabled;
    private final String title;
    private final List<String> paragraphs;

    public ConsentBanner(EnsProperties.ConsentBanner settings) {
        this.enabled = settings.isEnabled();
        this.title = settings.getTitle();
        String text = settings.getText();
        if (settings.getFile() != null && !settings.getFile().isBlank()) {
            Path file = Path.of(settings.getFile());
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("ENS_CONSENT_BANNER_FILE " + file + " cannot be read: " + e.getMessage(), e);
            }
            if (text.isBlank()) {
                throw new IllegalStateException("ENS_CONSENT_BANNER_FILE " + file + " is empty");
            }
        }
        this.paragraphs = paragraphs(text);
        if (enabled) {
            log.info("Notice-and-consent banner enabled: no sign-in until a browser session acknowledges \"{}\"", title);
        }
    }

    /** Paragraphs separated by blank lines; the lines within one are joined with spaces. */
    static List<String> paragraphs(String text) {
        return Arrays.stream(text.replace("\r\n", "\n").split("\n\\s*\n"))
                .map(p -> String.join(" ", p.strip().split("\\s*\n\\s*")))
                .filter(p -> !p.isBlank())
                .toList();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getParagraphs() {
        return paragraphs;
    }
}
