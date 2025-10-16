package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
public class GroupService {

    @Autowired
    private LdapService ldapService;

    @GetMapping
    public Map<String, Object> getGroups() {
        List<LdapGroup> groups = ldapService.getGroups();

        Map<String, Object> response = new HashMap<>();
        response.put("data", groups);

        return response;
    }
}
