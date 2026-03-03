package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
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
    private final PermissionService permissionService;

    public HomeController(CacheService cacheService, ApplicationProperties properties,
                          UserRecordRepository userRecordRepository, PermissionService permissionService) {
        this.cacheService = cacheService;
        this.properties = properties;
        this.userRecordRepository = userRecordRepository;
        this.permissionService = permissionService;
    }

    @GetMapping
    public String index(Model model) {
        return "redirect:/app/groups"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/app")
    public String home(Model model) {
        return "redirect:/app/groups"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/app/users/{group}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER) or @permissionEvaluator.groupCheck(#group, #authentication)")
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
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER)")
    public String groups(Authentication authentication, Model model) {
        List<LdapGroup> topLevelGroups = new ArrayList<>();

        for(TopLevelGroupProperties topProps: properties.getGroups()) {
            LdapGroup groupHierarchy = cacheService.getGroup(topProps.getGroupsBaseDn());
            //topLevelGroups.add(groupHierarchy);

            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equalsIgnoreCase(UserRoleEnum.ADMIN.toString())
                    || a.getAuthority().equalsIgnoreCase(UserRoleEnum.MANAGER.toString()));

            if(isAdmin) {
                topLevelGroups.add(groupHierarchy);
            }else {
                List<LdapDn> groupWhitelist = permissionService.getUserGroupPermissions(new LdapDn(authentication.getName()));

                LdapGroup whitelistedGroup = groupHierarchy.filterByWhitelist(groupWhitelist);
                if (whitelistedGroup != null) {
                    topLevelGroups.add(whitelistedGroup);
                }
            }
        }

        Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(authentication.getName());
        if(recordOptional.isPresent()) {
            List<String> favoriteGroupsList = recordOptional.get().getFavoriteGroupsList();
            Map<String, String> favoriteMap = favoriteGroupsList.stream()
                            .collect(Collectors.toMap(g -> g, g -> new LdapDn(g).getCn()));

            model.addAttribute("favoriteMap", favoriteMap);
        }

        model.addAttribute("groups", topLevelGroups);
        model.addAttribute("initiallyExpanded", properties.isGroupsInitiallyExpanded());
        return "groups"; // resolves to src/main/resources/templates/index.html
    }


}