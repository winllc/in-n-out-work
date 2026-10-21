package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.api.ApiScope;
import com.winllc.innoutwork.api.ApiTokenService;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.model.WebhookEndpoint;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.webhook.WebhookEventType;
import com.winllc.innoutwork.webhook.WebhookService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administration &rarr; Integrations ({@code /app/admin/integrations}): public API tokens and webhook endpoints,
 * with each endpoint's recent deliveries. Administrators only. A new token or signing secret is shown once, in the
 * response to the post that made it, never through a redirect's flash attributes (they would put it in the browser
 * session, which is stored in the database).
 */
@Controller
@RequestMapping(IntegrationsController.PATH)
@PreAuthorize("hasAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN)")
public class IntegrationsController {

    public static final String PATH = "/app/admin/integrations";
    static final int LOG_SIZE = 20;

    private final ApiTokenService tokens;
    private final WebhookService webhooks;
    private final ApplicationProperties properties;

    public IntegrationsController(ApiTokenService tokens, WebhookService webhooks, ApplicationProperties properties) {
        this.tokens = tokens;
        this.webhooks = webhooks;
        this.properties = properties;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("tokens", tokens.list());
        model.addAttribute("scopes", ApiScope.values());
        model.addAttribute("apiSettings", tokens.settings());
        model.addAttribute("webhooksConfigured", webhooks.isConfigured());
        model.addAttribute("eventTypes", WebhookEventType.subscribable());
        List<WebhookEndpoint> endpoints = webhooks.list();
        model.addAttribute("endpoints", endpoints);
        Map<Long, Object> logs = new LinkedHashMap<>();
        endpoints.forEach(e -> logs.put(e.getId(), webhooks.recentDeliveries(e.getId(), LOG_SIZE)));
        model.addAttribute("deliveries", logs);
        model.addAttribute("openApiUrl", trimSlash(properties.getApplicationBaseUrl()) + "/api/v1/openapi");
        model.addAttribute("allowedCidrs", properties.getWebhooks().getAllowedCidrs());
        return "integrations";
    }

    @PostMapping("/tokens")
    public String createToken(Authentication authentication, @RequestParam(defaultValue = "") String name,
                              @RequestParam(name = "scope", required = false) List<String> scopes,
                              @RequestParam(defaultValue = "90") int days,
                              @RequestParam(defaultValue = "0") int ratePerMinute, Model model) {
        Set<ApiScope> chosen = EnumSet.noneOf(ApiScope.class);
        if (scopes != null) {
            scopes.forEach(s -> ApiScope.of(s).ifPresent(chosen::add));
        }
        try {
            ApiTokenService.Created created = tokens.create(name, chosen, days, ratePerMinute, authentication);
            model.addAttribute("newToken", created.token());
            model.addAttribute("newTokenName", created.record().getName());
        } catch (InvalidRequestException e) {
            model.addAttribute("error", e.getMessage());
        }
        return page(model);
    }

    @PostMapping("/tokens/{id}/revoke")
    public String revokeToken(Authentication authentication, @PathVariable long id, RedirectAttributes flash) {
        if (tokens.revoke(id, authentication).isPresent()) {
            flash.addFlashAttribute("notice", "Token revoked. It stops working at once.");
        } else {
            flash.addFlashAttribute("error", "There is no active token with that id.");
        }
        return "redirect:" + PATH + "#tokens";
    }

    @PostMapping("/webhooks")
    public String createWebhook(Authentication authentication, @RequestParam(defaultValue = "") String name,
                                @RequestParam(defaultValue = "") String url,
                                @RequestParam(name = "event", required = false) List<String> events, Model model) {
        List<WebhookEventType> chosen = new ArrayList<>();
        if (events != null) {
            events.forEach(e -> WebhookEventType.of(e).filter(WebhookEventType.subscribable()::contains)
                    .ifPresent(chosen::add));
        }
        try {
            WebhookService.Created created = webhooks.create(name, url, chosen, authentication);
            model.addAttribute("newSecret", created.secret());
            model.addAttribute("newSecretName", created.endpoint().getName());
        } catch (InvalidRequestException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("webhookName", name);
            model.addAttribute("webhookUrl", url);
        }
        return page(model);
    }

    @PostMapping("/webhooks/{id}/disable")
    public String disable(Authentication authentication, @PathVariable long id, RedirectAttributes flash) {
        flash.addFlashAttribute("notice", webhooks.setEnabled(id, false, authentication).isPresent()
                ? "Webhook disabled: nothing more is sent to it." : "That webhook is already disabled.");
        return "redirect:" + PATH + "#webhooks";
    }

    @PostMapping("/webhooks/{id}/enable")
    public String enable(Authentication authentication, @PathVariable long id, RedirectAttributes flash) {
        flash.addFlashAttribute("notice", webhooks.setEnabled(id, true, authentication).isPresent()
                ? "Webhook enabled." : "That webhook is already enabled.");
        return "redirect:" + PATH + "#webhooks";
    }

    @PostMapping("/webhooks/{id}/delete")
    public String delete(Authentication authentication, @PathVariable long id, RedirectAttributes flash) {
        flash.addFlashAttribute("notice", webhooks.delete(id, authentication)
                ? "Webhook deleted, with its delivery log." : "There is no such webhook.");
        return "redirect:" + PATH + "#webhooks";
    }

    @PostMapping("/webhooks/{id}/ping")
    public String ping(Authentication authentication, @PathVariable long id, RedirectAttributes flash) {
        try {
            flash.addFlashAttribute("notice", webhooks.ping(id, authentication)
                    ? "A test delivery is queued; it appears in the log within seconds."
                    : "That webhook is disabled or deleted.");
        } catch (InvalidRequestException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:" + PATH + "#webhook-" + id;
    }

    static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
