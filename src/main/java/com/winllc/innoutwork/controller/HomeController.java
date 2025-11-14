package com.winllc.innoutwork.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;

@Controller
@RequestMapping("/")
public class HomeController {

    private final CacheService cacheService;
    private final ApplicationProperties properties;

    public HomeController(CacheService cacheService, ApplicationProperties properties) {
        this.cacheService = cacheService;
        this.properties = properties;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("name", "World");
        return "redirect:/app/groups"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/app")
    public String home(Model model) {
        model.addAttribute("name", "World");
        return "redirect:/app/groups"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/app/users/{group}")
    public String users(Model model, @PathVariable String group) {
        model.addAttribute("group", group);
        return "users"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/app/groups")
    public String groups(Model model) throws JsonProcessingException {
        LdapGroup groupHierarchy = cacheService.getGroup(properties.getGroupsBaseDn());

        ObjectMapper objectMapper = new ObjectMapper();

        model.addAttribute("groups", objectMapper.writeValueAsString(Collections.singletonList(groupHierarchy)));
        return "groups"; // resolves to src/main/resources/templates/index.html
    }


}