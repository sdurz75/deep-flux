package org.dual.hexa.core.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Dice a fragments/core/layout.html se servire il CSS Tailwind compilato
 * (static/css/tailwind.css, prodotto dal profilo Maven "tailwind") oppure
 * ricadere sul Play CDN. Decide la sola presenza dell'asset nel classpath:
 * nessuna property da tenere sincronizzata col profilo.
 */
@Component("tailwindAssets")
public class TailwindAssets {

    private final boolean compiled = new ClassPathResource("static/css/tailwind.css").exists();

    public boolean isCompiled() {
        return compiled;
    }
}
