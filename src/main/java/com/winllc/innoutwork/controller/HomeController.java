package com.winllc.innoutwork.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/")
public class HomeController {

    private final CacheService cacheService;
    private final ApplicationProperties properties;
    private final UserRecordRepository userRecordRepository;

    public HomeController(CacheService cacheService, ApplicationProperties properties,
                          UserRecordRepository userRecordRepository) {
        this.cacheService = cacheService;
        this.properties = properties;
        this.userRecordRepository = userRecordRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public String index(Model model) {
        model.addAttribute("name", "World");
        return "redirect:/app/groups"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/app")
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public String home(Model model) {
        model.addAttribute("name", "World");
        return "redirect:/app/groups"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/app/users/{group}")
    @PreAuthorize("hasAuthority('SUPER_USER') or @permissionEvaluator.checkPermission(#group, #authentication)")
    public String users(Authentication authentication, Model model, @PathVariable String group) {
        LdapGroup ldapGroup = cacheService.getGroup(group);
        model.addAttribute("group", ldapGroup.getName());
        model.addAttribute("groupDn", ldapGroup.getDn());

        Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(authentication.getName());
        if(recordOptional.isPresent()) {
            UserRecord userRecord = recordOptional.get();
            model.addAttribute("isFavorite", userRecord.containsGroupDn(group));
        }else{
            model.addAttribute("isFavorite", false);
        }

        return "users"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/app/groups")
    @PreAuthorize("hasAuthority('SUPER_USER')")
    public String groups(Authentication authentication, Model model) throws JsonProcessingException {
        LdapGroup groupHierarchy = cacheService.getGroup(properties.getGroupsBaseDn());

        Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(authentication.getName());
        if(recordOptional.isPresent()) {
            List<String> favoriteGroupsList = recordOptional.get().getFavoriteGroupsList();
            Map<String, String> favoriteMap = favoriteGroupsList.stream()
                            .collect(Collectors.toMap(g -> g, g -> new LdapDn(g).getCn()));

            model.addAttribute("favoriteMap", favoriteMap);
        }

        model.addAttribute("groups", Collections.singletonList(groupHierarchy));
        return "groups"; // resolves to src/main/resources/templates/index.html
    }


}