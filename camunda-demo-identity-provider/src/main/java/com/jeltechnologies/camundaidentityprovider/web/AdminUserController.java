package com.jeltechnologies.camundaidentityprovider.web;

import java.util.UUID;

import com.jeltechnologies.camundaidentityprovider.user.UserRepository;

import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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

    @PostMapping("/admin/users")
    public String add(@RequestParam String name, @RequestParam String email,
            @RequestParam String password, @RequestParam(defaultValue = "false") boolean admin,
            RedirectAttributes redirectAttributes) {
        userRepository.insert(name, email, passwordEncoder.encode(password), admin);
        redirectAttributes.addFlashAttribute("message", "User \"" + name + "\" created.");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/reset-password")
    public String resetPassword(@PathVariable UUID id, @RequestParam String password,
            RedirectAttributes redirectAttributes) {
        userRepository.updatePassword(id, passwordEncoder.encode(password));
        redirectAttributes.addFlashAttribute("message", "Password updated.");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/delete")
    public String delete(@PathVariable UUID id, Authentication authentication,
            RedirectAttributes redirectAttributes) {
        userRepository.findById(id).ifPresent(user -> {
            if (user.email().equals(authentication.getName())) {
                redirectAttributes.addFlashAttribute("error", "You cannot remove your own account.");
                return;
            }
            userRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("message", "User \"" + user.name() + "\" removed.");
        });
        return "redirect:/admin/users";
    }
}
