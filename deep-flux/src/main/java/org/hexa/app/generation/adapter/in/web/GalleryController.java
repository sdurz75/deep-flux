package org.hexa.app.generation.adapter.in.web;

import java.util.ArrayList;
import java.util.List;

import org.hexa.core.web.PaginationSupport;
import org.hexa.app.generation.domain.GalleryItem;
import org.hexa.app.generation.domain.GenerationFile;
import org.hexa.app.generation.port.in.IGenerations;
import org.hexa.core.kernel.Tags;
import org.hexa.core.kernel.Paged;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Galleria delle immagini generate: SOLO generazioni SUCCEEDED,
 * scroll infinito (sentinella htmx in fondo alla griglia, vedi fragments/app/gallery.html :: cards) e
 * cancellazione in blocco dalla griglia stessa (checkbox per card, vedi
 * fragments/app/gallery.html/gallery-card.html). Il dettaglio di una singola
 * generazione (prompt/parametri, cancellazione singola e per-immagine)
 * vive invece su GET /generations/{id} (vedi GenerationController e
 * CLAUDE.md): le card di questa griglia ci linkano, non c'e' piu' una
 * pagina di dettaglio separata qui.
 */
@Controller
@RequestMapping("/gallery")
public class GalleryController {

    private static final int PAGE_SIZE = 12;
    private static final String TAB_ALL = "all";
    private static final String TAB_FAVOURITES = "favourites";
    private static final String TAB_IMPORTED = "imported";

    private final IGenerations generationService;

    public GalleryController(IGenerations generationService) {
        this.generationService = generationService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = TAB_ALL) String tab,
                        @RequestParam(defaultValue = "") String tag,
                        @RequestParam(defaultValue = "false") boolean more,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {
        int pageIndex = Math.max(0, page - 1);
        String activeTab = TAB_FAVOURITES.equals(tab) ? TAB_FAVOURITES : (TAB_IMPORTED.equals(tab) ? TAB_IMPORTED : TAB_ALL);
        String activeTag = Tags.normalize(tag);
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        // Scroll infinito: la sentinella in fondo alla griglia chiede la pagina successiva ({@code more=true}) e ottiene solo
        // le card (e la nuova sentinella). Ogni altra richiesta (pagina intera, tab, filtro, refresh SSE) riparte dalla prima.
        boolean append = more && isHtmxRequest;
        Paged<GalleryItem> result = fetch(activeTab, activeTag, append ? pageIndex : 0);

        model.addAttribute("items", result.content());
        model.addAttribute("tab", activeTab);
        model.addAttribute("tag", activeTag);
        model.addAttribute("nextPage", result.hasNext() ? result.pageIndex() + 2 : null);

        // Nota: come vista di risposta diretta (non dentro un th:replace inline)
        // Thymeleaf richiede parametri nominati, non posizionali.
        if (append) {
            return "fragments/app/gallery :: cards(items=${items}, selectable=${tab != 'favourites'}, selectionByFile=false, "
                    + "conversationId=null, nextPage=${nextPage}, tab=${tab}, tag=${tag})";
        }
        return isHtmxRequest
                ? "fragments/app/gallery :: content(items=${items}, tab=${tab}, nextPage=${nextPage}, tag=${tag})"
                : "app/gallery";
    }

    /**
     * Tab "Tutte": una card per generazione (primo file); "Preferiti": una card per file con la star; "Importate": le immagini arrivate
     * dall'esterno (una card per immagine).
     */
    private Paged<GalleryItem> fetch(String tab, String tag, int pageIndex) {
        return switch (tab) {
            case TAB_FAVOURITES -> generationService.favouritesPage(tag, pageIndex, PAGE_SIZE);
            case TAB_IMPORTED -> generationService.importedPage(tag, pageIndex, PAGE_SIZE);
            default -> generationService.galleryPage(tag, pageIndex, PAGE_SIZE);
        };
    }

    /**
     * Selettore dell'archivio nel dialog "Scegli dall'archivio" (fragments/app/archive-picker.html): le immagini riuscite (mai i video),
     * tutte o solo le importate. {@code kind} e' il tipo di pagina di /generations/new da cui si e' aperto (image, vuoto = video):
     * la scelta porta a quella stessa pagina con la sorgente.
     */
    @GetMapping("/picker")
    public String picker(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = TAB_ALL) String tab,
                         @RequestParam(required = false) String kind, Model model) {
        boolean importedOnly = TAB_IMPORTED.equals(tab);
        Paged<GalleryItem> result = generationService.imagePickerPage(importedOnly, Math.max(0, page - 1), PAGE_SIZE);
        int currentPage = result.pageIndex() + 1;
        model.addAttribute("items", result.content());
        model.addAttribute("tab", importedOnly ? TAB_IMPORTED : TAB_ALL);
        model.addAttribute("kind", kind);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", result.totalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageNumbers", PaginationSupport.window(currentPage, result.totalPages()));
        return "fragments/app/gallery-picker :: picker(items=${items}, tab=${tab}, kind=${kind}, currentPage=${currentPage}, "
                + "totalPages=${totalPages}, hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers})";
    }


    /**
     * Cancellazione in blocco dalla griglia (checkbox multiple, bottone
     * "Elimina selezionate" in fragments/app/gallery.html :: grid, disabilitato
     * lato client finche' la selezione e' vuota — vedi fragments/core/button.html
     * :: dangerSelectable). La pagina corrente (globale o contestuale di
     * /deep-chat) resta valida dopo la cancellazione: nessun redirect,
     * risposta vuota. Il refresh — sia della tab che ha cliccato sia di
     * qualunque altra tab con una galleria aperta — arriva dall'evento SSE
     * pubblicato da IGenerations#deleteAll (GenerationsDeletedEvent →
     * "gallery-update", vedi GalleryPushNotifier), non da uno swap
     * diretto di questa risposta.
     * <p>
     * {@code required = false} sul parametro e' puramente difensivo: il
     * bottone che chiama questo endpoint e' disabilitato lato client
     * quando non c'e' selezione, quindi in pratica non dovrebbe mai
     * arrivare senza id.
     * <p>
     * {@code @ResponseBody}: senza, un metodo void su un {@code @Controller}
     * verrebbe risolto da Spring con RequestToViewNameTranslator, che
     * proverebbe a renderizzare un template "gallery/delete-selected"
     * inesistente (verificato dal vivo, 500 in test).
     */
    @PostMapping("/delete-selected")
    @ResponseBody
    public void deleteSelected(@RequestParam(required = false) List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            generationService.deleteAll(ids);
        }
    }

    /**
     * Cancellazione in blocco PER FILE (selezione della galleria contestuale di /deep-chat, una card per file): ogni voce di
     * {@code files} e' "<idGenerazione>:<filename>" (i filename sono hex + estensione, mai ':'). Voci malformate ignorate; come per
     * {@link #deleteSelected} nessuna risposta (il refresh arriva dall'evento SSE "gallery-update").
     */
    @PostMapping("/delete-selected-files")
    @ResponseBody
    public void deleteSelectedFiles(@RequestParam(required = false) List<String> files) {
        if (files == null) {
            return;
        }
        List<GenerationFile> parsed = new ArrayList<>();
        for (String entry : files) {
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            try {
                parsed.add(new GenerationFile(Long.valueOf(entry.substring(0, separator)), entry.substring(separator + 1)));
            } catch (NumberFormatException e) {
                // Voce malformata: ignorata, come un id sconosciuto.
            }
        }
        generationService.deleteImages(parsed);
    }
}
