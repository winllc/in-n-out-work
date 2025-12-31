package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;
import java.util.stream.Stream;

@Controller
@RequestMapping("/app/profile")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final UserRecordRepository recordRepository;
    private final UserRecordService userRecordService;

    public ProfileController(UserRecordRepository recordRepository, UserRecordService userRecordService) {
        this.recordRepository = recordRepository;
        this.userRecordService = userRecordService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER)")
    public String profile(Authentication authentication, Model model) {
        ProfileForm form = new ProfileForm();

        Optional<UserRecord> optionalRecord = recordRepository.findByDnIgnoreCase(authentication.getName());
        if(optionalRecord.isPresent()) {
            UserRecord userRecord = optionalRecord.get();
            form.setNotes(userRecord.getNotes());
            if(userRecord.getStatus() != null) {
                form.setStatus(userRecord.getStatus().name());
            }

            model.addAttribute("user", userRecord);
        }

        model.addAttribute("statuses", Stream.of(UserStatusEnum.values()).toList());
        model.addAttribute("form", form);
        return "profile"; // resolves to src/main/resources/templates/index.html
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER)")
    public String profileSubmit(Authentication authentication,
                                RedirectAttributes redirectAttributes,
                                Model model, @ModelAttribute ProfileForm form) {
        log.debug("%s updating profile".formatted(authentication.getName()));

        UserRecord updated = userRecordService.updateProfile(authentication, form);


        //model.addAttribute("form", ProfileForm.builder().notes(updated.getNotes()).build());
        redirectAttributes.addFlashAttribute("message", "Successfully updated profile");

        return "redirect:/app/profile";
    }
}
