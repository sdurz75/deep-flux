package org.hexa.core.search.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.hexa.core.search.domain.DocumentFilter;
import org.hexa.core.kernel.Tags;
import org.hexa.core.search.domain.DocumentTypes;
import org.hexa.core.search.domain.IndexStats;
import org.hexa.core.search.domain.IndexedDocument;
import org.hexa.core.search.domain.ScoredDocument;
import org.hexa.core.search.port.in.IArchiveIndex;
import org.hexa.core.search.port.in.IArchiveNotes;
import org.hexa.core.search.port.in.IArchiveSearch;
import org.hexa.core.kernel.Paged;
import org.hexa.core.kernel.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.hexa.core.web.HtmxEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Interfaccia manuale al vector store: interrogarlo (ricerca semantica con punteggi), sfogliarlo/ispezionarlo e modificarlo.
 * Si modificano SOLO le note manuali ({@code type=note}, che la riconciliazione dell'indice non tocca); i documenti derivati
 * (generation, chat, conversation) sono in sola lettura perche' la loro fonte di verita' e' il DB: si possono solo ri-embeddare.
 * Passa solo dalle porte di {@code search}: {@link IArchiveSearch} (ricerca, listato), {@link IArchiveNotes} (note), {@link IArchiveIndex} (riconciliazione).
 */
@Controller
@RequestMapping("/search")
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class SemanticSearchController {

    static final int PAGE_SIZE = 20;
    /** Filtro "media" della form (solo generazioni e immagini importate): valori ammessi, il resto e' ignorato come "tutti". */
    static final List<String> MEDIA = List.of("all", "image", "video");

    /** "Tutti" e i tipi dell'indice, nell'ordine delle sorgenti (note per ultime). */
    private List<String> types() {
        List<String> types = new java.util.ArrayList<>();
        types.add("all");
        types.addAll(search.types());
        return types;
    }

    /** Un documento con, se viene da una ricerca, il suo punteggio di similarita' (0..1). */
    public record Hit(IndexedDocument doc, Double score) {
    }

    private final IArchiveSearch search;
    private final IArchiveNotes notes;
    private final IArchiveIndex index;
    private final Messages messages;
    private final HtmxEvents htmx;
    private final int defaultThresholdPercent;

    private final String hostFragment;

    public SemanticSearchController(IArchiveSearch search, IArchiveNotes notes, IArchiveIndex index, Messages messages,
                                    HtmxEvents htmx, @Value("${app.search.similarity-threshold-percent:80}") int defaultThresholdPercent,
                                    @Value("${app.search.host-fragment:}") String hostFragment) {
        this.hostFragment = hostFragment;
        this.search = search;
        this.notes = notes;
        this.index = index;
        this.messages = messages;
        this.htmx = htmx;
        this.defaultThresholdPercent = defaultThresholdPercent;
    }

    // --- pagina e ricerca -----------------------------------------------------------------------------------------

    /** La pagina: il form e, gia' renderizzata, la prima pagina della lista (con gli stessi parametri del form). */
    @GetMapping
    public String page(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String type,
                       @RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                       @RequestParam(required = false) Integer threshold, @RequestParam(defaultValue = "all") String media,
                       @RequestParam(defaultValue = "false") boolean favourites, @RequestParam(defaultValue = "") String tag, Model model) {
        model.addAttribute("stats", stats());
        model.addAttribute("tag", Tags.normalize(tag));
        model.addAttribute("knownTags", search.tags());
        model.addAttribute("types", types());
        model.addAttribute("mediaOptions", MEDIA);
        model.addAttribute("media", media);
        model.addAttribute("favourites", favourites);
        model.addAttribute("q", q.strip());
        model.addAttribute("type", type);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("threshold", threshold == null ? defaultThresholdPercent : threshold);
        populateResults(q, type, from, to, threshold, media, favourites, tag, 1, model);
        model.addAttribute("noteError", null);
        model.addAttribute("noteText", "");
        model.addAttribute("noteTitle", "");
        return "core/search";
    }

    /**
     * L'unica lista della pagina. Con {@code q}: classifica per significato (punteggio), per somiglianza decrescente, sopra la soglia
     * {@code threshold} (percentuale 0..100). Senza {@code q}: i documenti, piu' recenti prima. In entrambi i casi filtrati per
     * {@code type} (vuoto/{@code all} = tutti) e per periodo di creazione {@code from}/{@code to} (date ISO, estremi inclusi), e paginati (scroll infinito: pagina successiva con {@code more=true}).
     */
    @GetMapping("/results")
    public String results(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String type,
                          @RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                          @RequestParam(required = false) Integer threshold, @RequestParam(defaultValue = "all") String media,
                          @RequestParam(defaultValue = "false") boolean favourites, @RequestParam(defaultValue = "") String tag,
                          @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "false") boolean more,
                          Model model) {
        populateResults(q, type, from, to, threshold, media, favourites, tag, more ? page : 1, model);
        // Scroll infinito: la sentinella in fondo alla lista chiede la pagina successiva ({@code more=true}) e ottiene solo
        // le righe (e la nuova sentinella); ogni altra richiesta (form, note salvata) riparte dalla prima.
        return more
                ? "fragments/core/search :: rows(hits=${hits}, baseQuery=${baseQuery}, nextPage=${nextPage})"
                : "fragments/core/search :: results(hits=${hits}, total=${total}, query=${query}, error=${error}, baseQuery=${baseQuery}, "
                        + "nextPage=${nextPage})";
    }

    // --- note manuali -----------------------------------------------------------------------------------------------

    @PostMapping("/notes")
    public String createNote(@RequestParam(defaultValue = "") String text, @RequestParam(defaultValue = "") String title,
                             HttpServletResponse response, Model model) {
        String error = validate(text);
        if (error != null) {
            retargetForm(response);
            return noteFormView(null, title, text, error, model);
        }
        notes.create(title, text);
        // Chiude il dialog note (search.html) e fa ricaricare la lista col form corrente (search-form ascolta note-saved); con un
        // errore di validazione, sopra, l'evento NON parte e il dialog resta aperto.
        htmx.addHxTrigger(response, "note-saved", "");
        model.addAttribute("stats", stats());
        return "fragments/core/search :: deleted(stats=${stats})";
    }

    /** Contenuto del dialog per una nota nuova (form vuoto, caricato a ogni apertura). */
    @GetMapping("/notes/new")
    public String newNoteForm(Model model) {
        return noteFormView(null, "", "", null, model);
    }

    /** Contenuto del dialog per la modifica: form precompilato. */
    @GetMapping("/notes/{id}/edit")
    public String editNote(@PathVariable String id, Model model) {
        IndexedDocument doc = requireNote(id);
        return noteFormView(id, doc.metadata().get("title") == null ? "" : String.valueOf(doc.metadata().get("title")),
                doc.content(), null, model);
    }

    @PostMapping("/notes/{id}")
    public String updateNote(@PathVariable String id, @RequestParam(defaultValue = "") String text,
                             @RequestParam(defaultValue = "") String title, HttpServletResponse response, Model model) {
        requireNote(id);
        String error = validate(text);
        if (error != null) {
            retargetForm(response);
            return noteFormView(id, title, text, error, model);
        }
        notes.update(id, title, text);
        htmx.addHxTrigger(response, "note-saved", "");
        model.addAttribute("hit", new Hit(search.find(id).orElseThrow(), null));
        return "fragments/core/search :: row(hit=${hit})";
    }

    @DeleteMapping("/notes/{id}")
    public String deleteNote(@PathVariable String id, Model model) {
        requireNote(id);
        notes.delete(id);
        model.addAttribute("stats", stats());
        return "fragments/core/search :: deleted(stats=${stats})";
    }

    // --- amministrazione --------------------------------------------------------------------------------------------

    /** Ricalcola l'embedding di un documento (qualunque tipo): dopo un cambio di modello o per riprovare un fallimento. */
    @PostMapping("/docs/{id}/reembed")
    public String reembed(@PathVariable String id, Model model) {
        if (!index.reembed(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("hit", new Hit(search.find(id).orElseThrow(), null));
        return "fragments/core/search :: row(hit=${hit})";
    }

    /** Avvia la riconciliazione con i dati (in background) e ritorna le statistiche con "in corso". */
    @PostMapping("/reindex")
    public String reindex(Model model) {
        index.reindexAsync();
        model.addAttribute("stats", stats());
        model.addAttribute("oob", false);
        return "fragments/core/search :: stats(stats=${stats}, oob=${oob})";
    }

    // --- interno ----------------------------------------------------------------------------------------------------

    private IndexStats stats() {
        return search.stats();
    }

    private void populateResults(String q, String type, String from, String to, Integer threshold, String media, boolean favourites,
                                 String rawTag, int page, Model model) {
        String tag = Tags.normalize(rawTag);
        String query = q.strip();
        int minPercent = threshold == null ? defaultThresholdPercent : threshold;
        model.addAttribute("query", query);
        model.addAttribute("error", null);
        model.addAttribute("hits", List.of());
        model.addAttribute("total", 0L);
        model.addAttribute("baseQuery", baseQuery(query, type, from, to, minPercent, media, favourites, tag));
        model.addAttribute("nextPage", null);

        String error = null;
        Instant start = null;
        Instant end = null;
        try {
            start = from.isBlank() ? null : LocalDate.parse(from.strip()).atStartOfDay(ZoneId.systemDefault()).toInstant();
            end = to.isBlank() ? null : LocalDate.parse(to.strip()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1);
        } catch (DateTimeParseException e) {
            error = messages.get("search.error.date");
        }
        if (error == null && start != null && end != null && start.isAfter(end)) {
            error = messages.get("search.error.dateRange");
        }
        if (error == null && (minPercent < 0 || minPercent > 100)) {
            error = messages.get("search.error.threshold");
        }
        if (error != null) {
            model.addAttribute("error", error);
            return;
        }

        // Media/preferiti sono metadata delle sole generazioni e immagini importate: gli altri tipi non li hanno e non combaciano, quindi col tipo "tutti" non serve restringere.
        String kind = "image".equals(media) ? "IMAGE" : "video".equals(media) ? "VIDEO" : null;
        String wantedType = type.isBlank() || "all".equals(type) ? null : type;
        DocumentFilter filter = new DocumentFilter(wantedType, start, end, kind, favourites, tag);
        List<Hit> hits;
        long total;
        int current;
        int totalPages;
        if (query.isEmpty()) {
            Paged<IndexedDocument> listing = search.list(filter, page - 1, PAGE_SIZE);
            hits = listing.content().stream().map(d -> new Hit(d, null)).toList();
            total = listing.totalElements();
            current = listing.pageIndex() + 1;
            totalPages = Math.max(1, listing.totalPages());
        } else {
            // Tutta la classifica sopra la soglia: la si pagina qui e si risolvono solo i documenti della pagina.
            List<ScoredDocument> ranked = search.searchAll(query, filter, minPercent / 100.0);
            total = ranked.size();
            totalPages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
            current = Math.min(Math.max(1, page), totalPages);
            hits = ranked.stream().skip((long) (current - 1) * PAGE_SIZE).limit(PAGE_SIZE)
                    .map(d -> search.find(d.document().id()).map(stored -> new Hit(stored, d.score())))
                    .flatMap(java.util.Optional::stream).toList();
        }
        model.addAttribute("hits", hits);
        model.addAttribute("total", total);
        model.addAttribute("nextPage", current < totalPages ? current + 1 : null);
    }

    /** Query string (gia' codificata) dei filtri correnti: la paginazione ci accoda {@code page=N} e non li perde. */
    private static String baseQuery(String q, String type, String from, String to, int threshold, String media, boolean favourites,
                                        String tag) {
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance().queryParam("q", "{q}").queryParam("type", "{type}")
                .queryParam("from", "{from}").queryParam("to", "{to}").queryParam("threshold", "{threshold}")
                .queryParam("media", "{media}").queryParam("favourites", "{favourites}").queryParam("tag", "{tag}");
        String query = builder.encode().build(Map.of("q", q, "type", type, "from", from, "to", to, "threshold", threshold,
                "media", media, "favourites", favourites, "tag", tag)).getRawQuery();
        return query == null ? "" : query;
    }

    /** Errore di validazione: il form (non la lista/riga target) si rimpiazza da se', il dialog resta aperto. */
    private static void retargetForm(HttpServletResponse response) {
        response.setHeader("HX-Retarget", "#note-form");
        response.setHeader("HX-Reswap", "outerHTML");
    }

    private static String noteFormView(String noteId, String title, String text, String error, Model model) {
        model.addAttribute("noteId", noteId);
        model.addAttribute("noteTitle", title);
        model.addAttribute("noteText", text);
        model.addAttribute("noteError", error);
        return "fragments/core/search :: noteForm(noteId=${noteId}, noteTitle=${noteTitle}, noteText=${noteText}, noteError=${noteError})";
    }

    private String validate(String text) {
        if (text.isBlank()) {
            return messages.get("search.error.empty");
        }
        if (text.strip().length() > DocumentTypes.MAX_CHARS) {
            return messages.get("search.error.tooLong", DocumentTypes.MAX_CHARS);
        }
        return null;
    }

    /** Solo le note sono modificabili: un id diverso e' un errore del client (mai un derivato). */
    private IndexedDocument requireNote(String id) {
        if (!DocumentTypes.isNote(id)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return search.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Il template dell'host con gli slot della pagina (filterMedia, filterFavourites, rowBadges, rowLinks, rowMedia); vuoto = nessun host. */
    @org.springframework.web.bind.annotation.ModelAttribute("searchHost")
    String searchHost() {
        return hostFragment;
    }
}
