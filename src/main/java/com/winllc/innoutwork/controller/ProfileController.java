package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.UserRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Optional;

@Controller
@RequestMapping("/app/profile")
public class ProfileController {

    private final UserRecordRepository recordRepository;
    private final UserRecordService userRecordService;

    public ProfileController(UserRecordRepository recordRepository, UserRecordService userRecordService) {
        this.recordRepository = recordRepository;
        this.userRecordService = userRecordService;
    }

    @GetMapping
    public String profile(Authentication authentication, Model model) {
        ProfileForm form = new ProfileForm();

        Optional<UserRecord> optionalRecord = recordRepository.findByDnIgnoreCase(authentication.getName());
        if(optionalRecord.isPresent()) {
            form.setNotes(optionalRecord.get().getNotes());

            model.addAttribute("user", optionalRecord.get());
        }

        model.addAttribute("form", form);
        return "profile"; // resolves to src/main/resources/templates/index.html
    }

    @PostMapping
    public String profileSubmit(Authentication authentication,
                                Model model, @ModelAttribute ProfileForm form) {

        UserRecord updated = userRecordService.updateNotes(authentication, form.getNotes());

        model.addAttribute("form", ProfileForm.builder().notes(updated.getNotes()).build());

        return "profile"; // resolves to src/main/resources/templates/index.html
    }
}
