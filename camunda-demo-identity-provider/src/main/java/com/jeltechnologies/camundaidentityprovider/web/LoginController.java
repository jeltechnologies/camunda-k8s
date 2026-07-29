package com.jeltechnologies.camundaidentityprovider.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@CookieValue(name = "last_email", required = false, defaultValue = "") String lastEmail,
            Model model) {
        model.addAttribute("lastEmail", lastEmail);
        return "login";
    }
}
