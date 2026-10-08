package com.example.oauth2host;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** La pagina iniziale che un host deve avere, piu' un POST e un GET non-HTML per provare il cancello. */
@Controller
class Oauth2HostController {

    @GetMapping("/")
    String home() {
        return "home";
    }

    @PostMapping("/ping")
    @ResponseBody
    String ping() {
        return "pong";
    }

    @GetMapping("/data")
    @ResponseBody
    String data() {
        return "data";
    }
}
