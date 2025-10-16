package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.ProfileForm;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/app")
public class HomeController {

    @GetMapping
    public String home(Model model) {
        model.addAttribute("name", "World");
        return "index"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/users/{group}")
    public String users(Model model, @PathVariable String group) {
        model.addAttribute("group", group);
        return "users"; // resolves to src/main/resources/templates/index.html
    }

    @GetMapping("/groups")
    public String groups(Model model) {
        //model.addAttribute("group", group);
        return "groups"; // resolves to src/main/resources/templates/index.html
    }


}