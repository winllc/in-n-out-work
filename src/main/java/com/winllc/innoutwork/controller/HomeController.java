package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.HomeService;
import com.winllc.innoutwork.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/")
public class HomeController {

    private final CacheService cacheService;
    private final ApplicationProperties properties;
    private final UserRecordRepository userRecordRepository;
    private final PermissionService permissionService;
    private final HomeService homeService;

    public HomeController(CacheService cacheService, ApplicationProperties properties,
                          UserRecordRepository userRecordRepository, PermissionService permissionService,
                          HomeService homeService) {
        this.cacheService = cacheService;
        this.properties = properties;
        this.userRecordRepository = userRecordRepository;
        this.permissionService = permissionService;
        this.homeService = homeService;
    }

    @GetMapping
    public String index(Model model) {
        return "redirect:/app/home";
    }

    @GetMapping("/app")
    public String app(Model model) {
        return "redirect:/app/home";
    }

    /** The signed-in user's own day and attendance, plus their team's when people report to them. */
    @GetMapping("/app/home")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER)")
    public String home(Authentication authentication, HttpSession session, Model model) {
        model.addAttribute("home", homeService.forUser(authentication.getName(), session));
        return "home";
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

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase(UserRoleEnum.ADMIN.toString())
                || a.getAuthority().equalsIgnoreCase(UserRoleEnum.MANAGER.toString()));
        // The same for every top-level group, so read once.
        List<LdapDn> groupWhitelist = isAdmin ? List.of()
                : permissionService.getUserGroupPermissions(new LdapDn(authentication.getName()));

        for(TopLevelGroupProperties topProps: properties.getGroups()) {
            LdapGroup groupHierarchy = cacheService.getGroup(topProps.getGroupsBaseDn());
            //topLevelGroups.add(groupHierarchy);

            if(isAdmin) {
                topLevelGroups.add(groupHierarchy);
            }else {

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