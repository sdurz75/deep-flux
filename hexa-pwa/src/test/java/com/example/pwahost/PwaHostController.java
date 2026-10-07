package com.example.pwahost;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** La pagina iniziale che un host deve avere (il core non ha una route {@code /}). */
@Controller
class PwaHostController {

    @GetMapping("/")
    String home() {
        return "home";
    }
}
