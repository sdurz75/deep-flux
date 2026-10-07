package org.dual.hexa.core.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.function.Function;
import org.dual.hexa.core.web.ILayoutContributor.NavEntry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Espone a {@code fragments/core/layout.html} e {@code header.html} i contributi di tutte le {@link ILayoutContributor} presenti (nessuna = liste
 * vuote): {@code headFragments}, {@code bodyEndFragments}, {@code manageMenuEntries}. Stessa idea di {@code PushModelAdvice}, ma aggregata, cosi' piu'
 * estensioni non si sovrascrivono a vicenda.
 */
@ControllerAdvice
public class LayoutSlotsAdvice {

    private final ObjectProvider<ILayoutContributor> contributors;

    public LayoutSlotsAdvice(ObjectProvider<ILayoutContributor> contributors) {
        this.contributors = contributors;
    }

    @ModelAttribute("headFragments")
    public List<String> headFragments(HttpServletRequest request) {
        return collect(contributor -> contributor.head(request));
    }

    @ModelAttribute("bodyEndFragments")
    public List<String> bodyEndFragments(HttpServletRequest request) {
        return collect(contributor -> contributor.bodyEnd(request));
    }

    @ModelAttribute("manageMenuEntries")
    public List<NavEntry> manageMenuEntries() {
        return collect(ILayoutContributor::manageMenu);
    }

    private <T> List<T> collect(Function<ILayoutContributor, List<T>> slot) {
        return contributors.orderedStream().flatMap(contributor -> slot.apply(contributor).stream()).toList();
    }
}
