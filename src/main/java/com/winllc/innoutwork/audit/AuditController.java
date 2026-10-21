package com.winllc.innoutwork.audit;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.license.LicensingConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Administration &rarr; Audit log ({@code /app/admin/audit}): search, inspect, export and verify the trail, see
 * how delivery to the SIEM outputs is going, and manage the pull API's tokens. Administrators only (the ADMIN
 * authority, as for the license screen). Not a licensed feature, and open in every license state.
 */
@Controller
@RequestMapping(LicensingConfiguration.AUDIT_SCREEN)
public class AuditController {

    static final int PAGE_SIZE = 50;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditQueryService query;
    private final AuditTokenService tokens;
    private final AuditOutputs outputs;
    private final AuditProperties properties;
    private final AuditLog audit;
    private final AccessDenials denials;

    public AuditController(AuditQueryService query, AuditTokenService tokens, AuditOutputs outputs,
                           AuditProperties properties, AuditLog audit, AccessDenials denials) {
        this.query = query;
        this.tokens = tokens;
        this.outputs = outputs;
        this.properties = properties;
        this.audit = audit;
        this.denials = denials;
    }

    /** One row of the detail view. */
    public record Field(String name, String value) {
    }

    @GetMapping
    public String list(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
                       @RequestParam Map<String, String> params,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        if (!isAdmin(authentication)) {
            return refuse(request, response);
        }
        AuditQueryService.Filter filter = filter(params);
        Page<AuditEventRecord> results = query.search(filter, page, PAGE_SIZE);
        model.addAttribute("events", results);
        model.addAttribute("params", params);
        model.addAttribute("query", queryString(params));
        model.addAttribute("categories", Arrays.stream(AuditEntry.Category.values()).map(AuditEntry.Category::id).toList());
        model.addAttribute("outcomes", Arrays.stream(AuditEntry.Outcome.values()).map(AuditEntry.Outcome::id).toList());
        model.addAttribute("severities", Arrays.stream(AuditEntry.Severity.values()).map(AuditEntry.Severity::id).toList());
        model.addAttribute("actions", query.knownActions());
        model.addAttribute("outputs", outputs.status());
        model.addAttribute("tokens", tokens.list());
        model.addAttribute("retentionDays", properties.getRetentionDays());
        model.addAttribute("zone", ZoneId.systemDefault().getId());
        return "audit";
    }

    @GetMapping("/events/{seq}")
    public String detail(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
                         @PathVariable long seq, Model model) {
        if (!isAdmin(authentication)) {
            return refuse(request, response);
        }
        AuditEventRecord event = query.find(seq).orElse(null);
        if (event == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("missingSeq", seq);
            return "audit-event";
        }
        model.addAttribute("event", event);
        JsonNode node = JSON.readTree(event.getLine());
        List<Field> fields = new ArrayList<>();
        flatten("", node, fields);
        model.addAttribute("fields", fields);
        model.addAttribute("pretty", JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        return "audit-event";
    }

    @GetMapping("/export")
    public void export(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
                       @RequestParam Map<String, String> params,
                       @RequestParam(defaultValue = "csv") String format) throws IOException {
        if (!isAdmin(authentication)) {
            denials.denied(request, "permission_denied", "The audit log is for administrators.", null);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        boolean csv = !"ndjson".equalsIgnoreCase(format);
        AuditQueryService.Filter filter = filter(params);
        long upTo = query.search(AuditQueryService.Filter.none(), 0, 1).getContent().stream()
                .mapToLong(AuditEventRecord::getSeq).findFirst().orElse(0);
        long matching = query.search(filter, 0, 1).getTotalElements();
        long count = Math.min(matching, AuditQueryService.MAX_EXPORT);
        // Recorded before the download starts: an export that cannot be audited is not given out.
        audit.record(AuditEntry.of(AuditEntry.Category.AUDIT, "audit.exported")
                .detail("format", csv ? "csv" : "ndjson")
                .detail("filter", filter.describe())
                .detail("count", count)
                .detail("truncated", matching > count));

        String name = "readyroom-audit-" + LocalDate.now() + (csv ? ".csv" : ".ndjson");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(csv ? "text/csv" : "application/x-ndjson");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
        PrintWriter out = response.getWriter();
        if (csv) {
            out.print(String.join(",", AuditCsv.HEADER) + "\r\n");
            query.export(filter, upTo, e -> out.print(AuditCsv.row(e) + "\r\n"));
        } else {
            query.export(filter, upTo, e -> out.print(e.getLine() + "\n"));
        }
        out.flush();
    }

    @PostMapping("/verify")
    public String verify(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
                         RedirectAttributes flash) {
        if (!isAdmin(authentication)) {
            return refuse(request, response);
        }
        AuditQueryService.Verification result = query.verify();
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.AUDIT, "audit.verified")
                .detail("result", result.intact() ? "intact" : "broken")
                .detail("checked", result.checked())
                .detail("anchorSeq", result.anchorSeq())
                .detail("anchorIsGenesis", result.anchorIsGenesis())
                .detail("brokenSeq", result.brokenSeq())
                .detail("lastSeq", result.lastSeq());
        if (!result.intact()) {
            entry.failure(result.problem()).severity(AuditEntry.Severity.ALERT);
        }
        audit.record(entry);
        flash.addFlashAttribute(result.intact() ? "notice" : "error", result.summary());
        return "redirect:" + LicensingConfiguration.AUDIT_SCREEN;
    }

    /**
     * Creates a token and shows it once, in this response itself: not through a redirect's flash attribute, which
     * would put the secret in the browser session, and sessions are kept in the database. Spring Security marks
     * the response no-store; reloading it asks to post again (and would make another token), never shows this one.
     */
    @PostMapping("/tokens")
    public String createToken(Authentication authentication, HttpServletRequest request,
                              HttpServletResponse response, @RequestParam(defaultValue = "") String name,
                              Model model) {
        if (!isAdmin(authentication)) {
            return refuse(request, response);
        }
        AuditTokenService.Created created = tokens.create(name, authentication.getName());
        model.addAttribute("newToken", created.token());
        model.addAttribute("newTokenName", created.record().getName());
        return list(authentication, request, response, Map.of(), 0, model);
    }

    @PostMapping("/tokens/{id}/revoke")
    public String revokeToken(Authentication authentication, HttpServletRequest request,
                              HttpServletResponse response, @PathVariable long id, RedirectAttributes flash) {
        if (!isAdmin(authentication)) {
            return refuse(request, response);
        }
        if (tokens.revoke(id, authentication.getName()).isPresent()) {
            flash.addFlashAttribute("notice", "Token revoked. It stops working at once.");
        } else {
            flash.addFlashAttribute("error", "There is no active token with that id.");
        }
        return "redirect:" + LicensingConfiguration.AUDIT_SCREEN + "#tokens";
    }

    private String refuse(HttpServletRequest request, HttpServletResponse response) {
        denials.denied(request, "permission_denied", "The audit log is for administrators.", null);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return "unauthorized";
    }

    static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> UserRoleEnum.ADMIN.name().equals(a.getAuthority()));
    }

    /** The filter form's fields. Times are the application's time zone, as the form shows them. */
    static AuditQueryService.Filter filter(Map<String, String> p) {
        return new AuditQueryService.Filter(time(p.get("from")), time(p.get("to")), p.get("category"),
                p.get("action"), p.get("outcome"), p.get("severity"), p.get("actor"), p.get("target"),
                p.get("ip"), p.get("q"));
    }

    private static Instant time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            // a datetime-local field: no zone
        }
        try {
            return LocalDateTime.parse(value.trim()).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** The current filter as a query string (without the page), for paging and export links. */
    static String queryString(Map<String, String> params) {
        StringBuilder b = new StringBuilder();
        for (String key : List.of("from", "to", "category", "action", "outcome", "severity", "actor", "target", "ip", "q")) {
            String v = params.get(key);
            if (v != null && !v.isBlank()) {
                if (!b.isEmpty()) {
                    b.append('&');
                }
                b.append(key).append('=').append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
        return b.toString();
    }

    private static void flatten(String prefix, JsonNode node, List<Field> out) {
        if (node.isObject() && !prefix.isEmpty() && node.isEmpty()) {
            out.add(new Field(prefix.substring(0, prefix.length() - 1), "{}"));
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                flatten(prefix + e.getKey() + ".", e.getValue(), out);
            }
            return;
        }
        String name = prefix.substring(0, prefix.length() - 1);
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(n -> items.add(n.isString() ? n.asString() : n.toString()));
            out.add(new Field(name, String.join(", ", items)));
        } else if (node.isNull()) {
            out.add(new Field(name, null));
        } else {
            out.add(new Field(name, node.isString() ? node.asString() : node.toString()));
        }
    }
}
