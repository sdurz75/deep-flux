package org.dual.hexa.app.generation.adapter.in.web;

import java.util.Locale;

import org.dual.hexa.app.generation.domain.GenerationFormType;

/**
 * Quale fragment Thymeleaf renderizza i campi di un form-type: e' una scelta della UI (l'esagono converte i campi, non sa di
 * template). Per convenzione {@code FLUX_KREA_DEV} -> {@code fragments/app/generation-params-flux-krea-dev.html :: fields};
 * un form-type nuovo richiede il suo fragment (lo verifica {@code GenerationFormFragmentsTest}).
 */
public final class GenerationFormFragments {

    private static final String PREFIX = "fragments/app/generation-params-";

    private GenerationFormFragments() {
    }

    /** Il selettore {@code template :: fragment} da restituire come vista. */
    public static String fragmentOf(GenerationFormType formType) {
        return PREFIX + templateOf(formType) + " :: fields";
    }

    /** Il percorso della risorsa Thymeleaf (sotto {@code templates/}). */
    static String resourceOf(GenerationFormType formType) {
        return "templates/" + PREFIX + templateOf(formType) + ".html";
    }

    private static String templateOf(GenerationFormType formType) {
        return formType.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
