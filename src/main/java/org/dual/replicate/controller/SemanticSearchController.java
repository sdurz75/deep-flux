package org.dual.replicate.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.dual.replicate.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.search.vector.ArchiveIndexService;
import org.dual.replicate.service.SystemEventService;
import org.dual.replicate.search.vector.H2VectorStore;
import org.dual.replicate.search.vector.H2VectorStore.Listing;
import org.dual.replicate.search.vector.H2VectorStore.StoredDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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

/**
 * Interfaccia manuale al vector store: interrogarlo (ricerca semantica con punteggi), sfogliarlo/ispezionarlo e modificarlo.
 * Si modificano SOLO le note manuali ({@code type=note}, che {@link ArchiveIndexService} non tocca); i documenti derivati
 * (generation, chat, conversation) sono in sola lettura perche' la loro fonte di verita' e' il DB: si possono solo ri-embeddare.
 * La ricerca passa dall'interfaccia Spring AI {@link VectorStore}; le operazioni di amministrazione dalla classe concreta.
 */
@Controller
@RequestMapping("/search")
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class SemanticSearchController {

    static final int PAGE_SIZE = 20;
    static final int MAX_TOP_K = 50;
    static final String TYPE_NOTE = "note";
    static final List<String> LIST_TYPES = List.of("all", ArchiveIndexService.TYPE_GENERATION, ArchiveIndexService.TYPE_CHAT,
            ArchiveIndexService.TYPE_CONVERSATION, TYPE_NOTE);

    /** Un documento con, se viene da una ricerca, il suo punteggio di similarita' (0..1). */
    public record Hit(StoredDocument doc, Double score) {
    }

    /** Numeri della testata (fragment {@code stats}). */
    public record Stats(long total, Map<String, Long> counts, String modelId, int dimensions, boolean running) {
    }

    private final VectorStore vectorStore;
    private final H2VectorStore store;
    private final ArchiveIndexService indexService;
    private final Messages messages;
    private final SystemEventService systemEvents;
    private final int defaultTopK;
    private final int defaultThresholdPercent;

    public SemanticSearchController(VectorStore vectorStore, H2VectorStore store, ArchiveIndexService indexService,
                                    Messages messages, SystemEventService systemEvents, @Value("${app.search.top-k:5}") int defaultTopK,
                                    @Value("${app.search.similarity-threshold-percent:0}") int defaultThresholdPercent) {
        this.vectorStore = vectorStore;
        this.store = store;
        this.indexService = indexService;
        this.messages = messages;
        this.systemEvents = systemEvents;
        this.defaultTopK = defaultTopK;
        this.defaultThresholdPercent = defaultThresholdPercent;
    }

    // --- pagina e ricerca -----------------------------------------------------------------------------------------

    @GetMapping
    public String page(Model model) {
        model.addAttribute("stats", stats());
        model.addAttribute("topK", defaultTopK);
        model.addAttribute("threshold", defaultThresholdPercent);
        model.addAttribute("types", LIST_TYPES);
        populateList("all", 1, model);
        model.addAttribute("noteError", null);
        model.addAttribute("noteText", "");
        model.addAttribute("noteTitle", "");
        return "search";
    }

    /** Ricerca semantica. {@code type} vuoto = tutti i tipi; {@code threshold} = somiglianza minima in percentuale (0..100). */
    @GetMapping("/results")
    public String results(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String type,
                          @RequestParam(required = false) Integer topK,
                          @RequestParam(required = false) Integer threshold, Model model) {
        int k = topK == null ? defaultTopK : topK;
        int minPercent = threshold == null ? defaultThresholdPercent : threshold;
        model.addAttribute("query", q.strip());
        model.addAttribute("error", null);
        model.addAttribute("hits", List.of());
        if (k < 1 || k > MAX_TOP_K) {
            model.addAttribute("error", messages.get("search.error.topK", MAX_TOP_K));
        } else if (minPercent < 0 || minPercent > 100) {
            model.addAttribute("error", messages.get("search.error.threshold"));
        } else if (!q.isBlank()) {
            SearchRequest.Builder request = SearchRequest.builder().query(q.strip()).topK(k).similarityThreshold(minPercent / 100.0);
            if (!type.isBlank() && !"all".equals(type)) {
                request.filterExpression(new org.springframework.ai.vectorstore.filter.Filter.Expression(
                        org.springframework.ai.vectorstore.filter.Filter.ExpressionType.EQ,
                        new org.springframework.ai.vectorstore.filter.Filter.Key("type"),
                        new org.springframework.ai.vectorstore.filter.Filter.Value(type)));
            }
            List<Document> found = vectorStore.similaritySearch(request.build());
            model.addAttribute("hits", found.stream()
                    .map(d -> store.find(d.getId()).map(stored -> new Hit(stored, d.getScore())))
                    .flatMap(java.util.Optional::stream).toList());
        }
        return "fragments/search :: results(hits=${hits}, query=${query}, error=${error})";
    }

    /** Sfoglia i documenti; il tipo e' nel path cosi' la paginazione generica non perde il filtro. */
    @GetMapping("/list/{type}")
    public String list(@PathVariable String type, @RequestParam(defaultValue = "1") int page, Model model) {
        populateList(requireListType(type), page, model);
        return listView();
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
        store.add(List.of(note("note:" + UUID.randomUUID(), System.currentTimeMillis(), text.strip(), title.strip())));
        // Chiude il dialog note (search.html): con un errore di validazione, sopra, l'evento NON parte e il dialog resta aperto.
        systemEvents.addHxTrigger(response, "note-saved", "");
        model.addAttribute("stats", stats());
        populateList(TYPE_NOTE, 1, model);
        return createResultView();
    }

    /** Contenuto del dialog per una nota nuova (form vuoto, caricato a ogni apertura). */
    @GetMapping("/notes/new")
    public String newNoteForm(Model model) {
        return noteFormView(null, "", "", null, model);
    }

    /** Contenuto del dialog per la modifica: form precompilato. */
    @GetMapping("/notes/{id}/edit")
    public String editNote(@PathVariable String id, Model model) {
        StoredDocument doc = requireNote(id);
        return noteFormView(id, doc.metadata().get("title") == null ? "" : String.valueOf(doc.metadata().get("title")),
                doc.content(), null, model);
    }

    @PostMapping("/notes/{id}")
    public String updateNote(@PathVariable String id, @RequestParam(defaultValue = "") String text,
                             @RequestParam(defaultValue = "") String title, HttpServletResponse response, Model model) {
        StoredDocument existing = requireNote(id);
        String error = validate(text);
        if (error != null) {
            retargetForm(response);
            return noteFormView(id, title, text, error, model);
        }
        store.add(List.of(note(existing.id(), existing.refId(), text.strip(), title.strip())));
        systemEvents.addHxTrigger(response, "note-saved", "");
        model.addAttribute("hit", new Hit(store.find(id).orElseThrow(), null));
        return "fragments/search :: row(hit=${hit})";
    }

    @DeleteMapping("/notes/{id}")
    public String deleteNote(@PathVariable String id, Model model) {
        requireNote(id);
        store.delete(List.of(id));
        model.addAttribute("stats", stats());
        return "fragments/search :: deleted(stats=${stats})";
    }

    // --- amministrazione --------------------------------------------------------------------------------------------

    /** Ricalcola l'embedding di un documento (qualunque tipo): dopo un cambio di modello o per riprovare un fallimento. */
    @PostMapping("/docs/{id}/reembed")
    public String reembed(@PathVariable String id, Model model) {
        if (!store.reembed(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("hit", new Hit(store.find(id).orElseThrow(), null));
        return "fragments/search :: row(hit=${hit})";
    }

    /** Avvia la riconciliazione con i dati (in background) e ritorna le statistiche con "in corso". */
    @PostMapping("/reindex")
    public String reindex(Model model) {
        indexService.reindexAsync();
        model.addAttribute("stats", stats());
        model.addAttribute("oob", false);
        return "fragments/search :: stats(stats=${stats}, oob=${oob})";
    }

    // --- interno ----------------------------------------------------------------------------------------------------

    private Stats stats() {
        Map<String, Long> counts = store.countsByType();
        return new Stats(counts.values().stream().mapToLong(Long::longValue).sum(), counts, store.embeddingModelId(),
                store.dimensions(), indexService.isRunning());
    }

    private void populateList(String type, int page, Model model) {
        Listing listing = store.list("all".equals(type) ? null : type, page, PAGE_SIZE);
        model.addAttribute("listing", listing.documents().stream().map(d -> new Hit(d, null)).toList());
        model.addAttribute("listType", type);
        model.addAttribute("currentPage", listing.page());
        model.addAttribute("totalPages", listing.totalPages());
        model.addAttribute("hasPrevious", listing.hasPrevious());
        model.addAttribute("hasNext", listing.hasNext());
        model.addAttribute("pageNumbers", PaginationSupport.window(listing.page(), listing.totalPages()));
        model.addAttribute("types", LIST_TYPES);
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
        return "fragments/search :: noteForm(noteId=${noteId}, noteTitle=${noteTitle}, noteText=${noteText}, noteError=${noteError})";
    }

    private static String createResultView() {
        return "fragments/search :: createResult(listing=${listing}, listType=${listType}, currentPage=${currentPage}, "
                + "totalPages=${totalPages}, hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers}, "
                + "types=${types}, stats=${stats})";
    }

    private static String listView() {
        return "fragments/search :: list(listing=${listing}, listType=${listType}, currentPage=${currentPage}, "
                + "totalPages=${totalPages}, hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers}, "
                + "types=${types})";
    }

    private String validate(String text) {
        if (text.isBlank()) {
            return messages.get("search.error.empty");
        }
        if (text.strip().length() > ArchiveIndexService.MAX_CHARS) {
            return messages.get("search.error.tooLong", ArchiveIndexService.MAX_CHARS);
        }
        return null;
    }

    private static Document note(String id, long refId, String text, String title) {
        Document.Builder builder = Document.builder().id(id).text(text).metadata("type", TYPE_NOTE).metadata("refId", refId);
        if (!title.isBlank()) {
            builder.metadata("title", title);
        }
        return builder.build();
    }

    /** Solo le note sono modificabili: un id diverso e' un errore del client (mai un derivato). */
    private StoredDocument requireNote(String id) {
        if (!id.startsWith("note:")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String requireListType(String type) {
        if (!LIST_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return type;
    }
}
