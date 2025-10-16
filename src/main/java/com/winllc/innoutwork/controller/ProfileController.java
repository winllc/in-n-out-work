package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
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

    @Autowired
    private UserRecordRepository recordRepository;

    @GetMapping
    public String profile(Authentication authentication, Model model) {
        ProfileForm form = new ProfileForm();

        Optional<UserRecord> optionalRecord = recordRepository.findByDnIgnoreCase(authentication.getName());
        if(optionalRecord.isPresent()) {
            form.setNotes(optionalRecord.get().getNotes());
        }

        model.addAttribute("form", form);
        return "profile"; // resolves to src/main/resources/templates/index.html
    }

    @PostMapping
    public String profileSubmit(Authentication authentication,
                                Model model, @ModelAttribute ProfileForm form) {


        UserRecord userRecord = new UserRecord();

        Optional<UserRecord> optionalRecord = recordRepository.findByDnIgnoreCase(authentication.getName());
        if(optionalRecord.isPresent()) {
            userRecord = optionalRecord.get();
            userRecord.setNotes(form.getNotes());
        }else{
            userRecord.setDn(authentication.getName());
            userRecord.setNotes(form.getNotes());
        }

        UserRecord updated = recordRepository.save(userRecord);

        model.addAttribute("form", ProfileForm.builder().notes(updated.getNotes()).build());

        return "profile"; // resolves to src/main/resources/templates/index.html
    }
}
