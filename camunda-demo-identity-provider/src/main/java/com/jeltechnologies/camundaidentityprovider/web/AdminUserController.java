package com.jeltechnologies.camundaidentityprovider.web;

import java.util.UUID;

import com.jeltechnologies.camundaidentityprovider.user.User;
import com.jeltechnologies.camundaidentityprovider.user.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminUserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/admin/users")
    public String list(Model model) {
        model.addAttribute("users", userRepository.findAll());
        return "admin/users";
    }

    @GetMapping("/admin/users/new")
    public String newUserForm() {
        return "admin/add-user";
    }

    @PostMapping("/admin/users")
    public String add(@RequestParam String name, @RequestParam String email,
            @RequestParam String password, @RequestParam(defaultValue = "false") boolean admin,
            RedirectAttributes redirectAttributes) {
        try {
            userRepository.insert(name, email, passwordEncoder.encode(password), admin);
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("error", "A user with email \"" + email + "\" already exists.");
            return "redirect:/admin/users/new";
        }
        redirectAttributes.addFlashAttribute("message", "User \"" + name + "\" created.");
        return "redirect:/admin/users";
    }

    @GetMapping("/admin/users/{id}/edit")
    public String editUserForm(@PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        return userRepository.findById(id)
                .map(user -> {
                    model.addAttribute("user", user);
                    return "admin/edit-user";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "User not found.");
                    return "redirect:/admin/users";
                });
    }

    @PostMapping("/admin/users/{id}/edit")
    public String edit(@PathVariable UUID id, @RequestParam String name, @RequestParam String email,
            @RequestParam(required = false) String newPassword, RedirectAttributes redirectAttributes) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "User not found.");
            return "redirect:/admin/users";
        }
        if (user.defaultAdmin() && !email.equals(user.email())) {
            redirectAttributes.addFlashAttribute("error",
                    "The default admin user's email cannot be changed - it's the login identity Web Modeler and other components key on.");
            return "redirect:/admin/users/" + id + "/edit";
        }
        if (StringUtils.hasText(newPassword)) {
            userRepository.updatePassword(id, passwordEncoder.encode(newPassword));
        }
        if (user.defaultAdmin()) {
            userRepository.updateName(id, name);
        } else {
            try {
                userRepository.updateNameAndEmail(id, name, email);
            } catch (DataIntegrityViolationException e) {
                redirectAttributes.addFlashAttribute("error", "A user with email \"" + email + "\" already exists.");
                return "redirect:/admin/users/" + id + "/edit";
            }
        }
        redirectAttributes.addFlashAttribute("message", "User \"" + name + "\" updated.");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/delete")
    public String delete(@PathVariable UUID id, Authentication authentication,
            RedirectAttributes redirectAttributes) {
        userRepository.findById(id).ifPresentOrElse(user -> {
            if (user.defaultAdmin()) {
                redirectAttributes.addFlashAttribute("error", "The default admin user cannot be removed.");
                return;
            }
            if (user.email().equals(authentication.getName())) {
                redirectAttributes.addFlashAttribute("error", "You cannot remove your own account.");
                return;
            }
            userRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("message", "User \"" + user.name() + "\" removed.");
        }, () -> redirectAttributes.addFlashAttribute("error", "User not found."));
        return "redirect:/admin/users";
    }
}
