package org.dual.replicate.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.dual.replicate.core.kernel.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.search.vector.ArchiveIndexService;
import org.dual.replicate.service.SystemEventService;
import org.dual.replicate.search.vector.VectorDocumentRepository;
import org.dual.replicate.search.vector.VectorDocumentRepository.Listing;
import org.dual.replicate.search.vector.VectorDocumentRepository.StoredDocument;
import org.dual.replicate.search.vector.VectorIndexer;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
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
 * Si modificano SOLO le note manuali ({@code type=note}, che {@link ArchiveIndexService} non tocca); i documenti derivati
 * (generation, chat, conversation) sono in sola lettura perche' la loro fonte di verita' e' il DB: si possono solo ri-embeddare.
 * La ricerca passa dall'interfaccia Spring AI {@link VectorStore}; la scrittura da {@link VectorIndexer}, la lettura/il listato da {@link VectorDocumentRepository}.
 */
@Controller
@RequestMapping("/search")
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class SemanticSearchController {

    static final int PAGE_SIZE = 20;
    static final String TYPE_NOTE = ArchiveIndexService.TYPE_NOTE;
    static final List<String> TYPES = List.of("all", ArchiveIndexService.TYPE_GENERATION, ArchiveIndexService.TYPE_CHAT,
            ArchiveIndexService.TYPE_CONVERSATION, TYPE_NOTE);

    /** Un documento con, se viene da una ricerca, il suo punteggio di similarita' (0..1). */
    public record Hit(StoredDocument doc, Double score) {
    }

    /** Numeri della testata (fragment {@code stats}). */
    public record Stats(long total, Map<String, Long> counts, String modelId, int dimensions, boolean running) {
    }

    private final VectorStore vectorStore;
    private final VectorIndexer indexer;
    private final VectorDocumentRepository documents;
    private final ArchiveIndexService indexService;
    private final Messages messages;
    private final SystemEventService systemEvents;
    private final int defaultThresholdPercent;

    public SemanticSearchController(VectorStore vectorStore, VectorIndexer indexer, VectorDocumentRepository documents,
                                    ArchiveIndexService indexService, Messages messages, SystemEventService systemEvents,
                                    @Value("${app.search.similarity-threshold-percent:0}") int defaultThresholdPercent) {
        this.vectorStore = vectorStore;
        this.indexer = indexer;
        this.documents = documents;
        this.indexService = indexService;
        this.messages = messages;
        this.systemEvents = systemEvents;
        this.defaultThresholdPercent = defaultThresholdPercent;
    }

    // --- pagina e ricerca -----------------------------------------------------------------------------------------

    /** La pagina: il form e, gia' renderizzata, la prima pagina della lista (con gli stessi parametri del form). */
    @GetMapping
    public String page(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String type,
                       @RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                       @RequestParam(required = false) Integer threshold, Model model) {
        model.addAttribute("stats", stats());
        model.addAttribute("types", TYPES);
        model.addAttribute("q", q.strip());
        model.addAttribute("type", type);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("threshold", threshold == null ? defaultThresholdPercent : threshold);
        populateResults(q, type, from, to, threshold, 1, model);
        model.addAttribute("noteError", null);
        model.addAttribute("noteText", "");
        model.addAttribute("noteTitle", "");
        return "search";
    }

    /**
     * L'unica lista della pagina. Con {@code q}: classifica per significato (punteggio), per somiglianza decrescente, sopra la soglia
     * {@code threshold} (percentuale 0..100). Senza {@code q}: i documenti, piu' recenti prima. In entrambi i casi filtrati per
     * {@code type} (vuoto/{@code all} = tutti) e per periodo di creazione {@code from}/{@code to} (date ISO, estremi inclusi), e paginati.
     */
    @GetMapping("/results")
    public String results(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String type,
                          @RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                          @RequestParam(required = false) Integer threshold, @RequestParam(defaultValue = "1") int page,
                          Model model) {
        populateResults(q, type, from, to, threshold, page, model);
        return "fragments/search :: results(hits=${hits}, total=${total}, query=${query}, error=${error}, baseQuery=${baseQuery}, "
                + "currentPage=${currentPage}, totalPages=${totalPages}, hasPrevious=${hasPrevious}, hasNext=${hasNext}, "
                + "pageNumbers=${pageNumbers})";
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
        long now = System.currentTimeMillis();
        indexer.upsertIfChanged(List.of(note("note:" + UUID.randomUUID(), now, now, text.strip(), title.strip())));
        // Chiude il dialog note (search.html) e fa ricaricare la lista col form corrente (search-form ascolta note-saved); con un
        // errore di validazione, sopra, l'evento NON parte e il dialog resta aperto.
        systemEvents.addHxTrigger(response, "note-saved", "");
        model.addAttribute("stats", stats());
        return "fragments/search :: deleted(stats=${stats})";
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
        // La data di creazione non cambia con la modifica (le note piu' vecchie non l'hanno ancora: refId e' lo stesso istante).
        indexer.upsertIfChanged(List.of(note(existing.id(), existing.refId(), existing.createdAt().toEpochMilli(), text.strip(), title.strip())));
        systemEvents.addHxTrigger(response, "note-saved", "");
        model.addAttribute("hit", new Hit(documents.find(id).orElseThrow(), null));
        return "fragments/search :: row(hit=${hit})";
    }

    @DeleteMapping("/notes/{id}")
    public String deleteNote(@PathVariable String id, Model model) {
        requireNote(id);
        indexer.delete(List.of(id));
        model.addAttribute("stats", stats());
        return "fragments/search :: deleted(stats=${stats})";
    }

    // --- amministrazione --------------------------------------------------------------------------------------------

    /** Ricalcola l'embedding di un documento (qualunque tipo): dopo un cambio di modello o per riprovare un fallimento. */
    @PostMapping("/docs/{id}/reembed")
    public String reembed(@PathVariable String id, Model model) {
        if (!indexer.reembed(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("hit", new Hit(documents.find(id).orElseThrow(), null));
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
        Map<String, Long> counts = documents.countsByType();
        return new Stats(counts.values().stream().mapToLong(Long::longValue).sum(), counts, indexer.embeddingModelId(),
                VectorIndexer.DIMENSIONS, indexService.isRunning());
    }

    private void populateResults(String q, String type, String from, String to, Integer threshold, int page, Model model) {
        String query = q.strip();
        int minPercent = threshold == null ? defaultThresholdPercent : threshold;
        model.addAttribute("query", query);
        model.addAttribute("error", null);
        model.addAttribute("hits", List.of());
        model.addAttribute("total", 0L);
        model.addAttribute("baseQuery", baseQuery(query, type, from, to, minPercent));
        model.addAttribute("currentPage", 1);
        model.addAttribute("totalPages", 1);
        model.addAttribute("hasPrevious", false);
        model.addAttribute("hasNext", false);
        model.addAttribute("pageNumbers", List.of());

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

        Filter.Expression filter = filter(type, start, end);
        List<Hit> hits;
        long total;
        int current;
        int totalPages;
        if (query.isEmpty()) {
            Listing listing = documents.list(filter, page, PAGE_SIZE);
            hits = listing.documents().stream().map(d -> new Hit(d, null)).toList();
            total = listing.total();
            current = listing.page();
            totalPages = listing.totalPages();
        } else {
            // Tutta la classifica sopra la soglia (topK = dimensione dell'indice): la si pagina qui e si risolvono solo i documenti della pagina.
            SearchRequest.Builder request = SearchRequest.builder().query(query).topK((int) Math.max(1, documents.count()))
                    .similarityThreshold(minPercent / 100.0);
            if (filter != null) {
                request.filterExpression(filter);
            }
            List<Document> ranked = vectorStore.similaritySearch(request.build());
            total = ranked.size();
            totalPages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
            current = Math.min(Math.max(1, page), totalPages);
            hits = ranked.stream().skip((long) (current - 1) * PAGE_SIZE).limit(PAGE_SIZE)
                    .map(d -> documents.find(d.getId()).map(stored -> new Hit(stored, d.getScore())))
                    .flatMap(java.util.Optional::stream).toList();
        }
        model.addAttribute("hits", hits);
        model.addAttribute("total", total);
        model.addAttribute("currentPage", current);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("hasPrevious", current > 1);
        model.addAttribute("hasNext", current < totalPages);
        model.addAttribute("pageNumbers", PaginationSupport.window(current, totalPages));
    }

    /** {@code type} (se non "all"/vuoto) AND {@code createdAt} nel periodo; {@code null} se nessun vincolo. */
    private static Filter.Expression filter(String type, Instant start, Instant end) {
        Filter.Expression filter = null;
        if (!type.isBlank() && !"all".equals(type)) {
            filter = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("type"), new Filter.Value(type));
        }
        if (start != null) {
            filter = and(filter, new Filter.Expression(Filter.ExpressionType.GTE, new Filter.Key("createdAt"), new Filter.Value(start.toEpochMilli())));
        }
        if (end != null) {
            filter = and(filter, new Filter.Expression(Filter.ExpressionType.LTE, new Filter.Key("createdAt"), new Filter.Value(end.toEpochMilli())));
        }
        return filter;
    }

    private static Filter.Expression and(Filter.Expression left, Filter.Expression right) {
        return left == null ? right : new Filter.Expression(Filter.ExpressionType.AND, left, right);
    }

    /** Query string (gia' codificata) dei filtri correnti: la paginazione ci accoda {@code page=N} e non li perde. */
    private static String baseQuery(String q, String type, String from, String to, int threshold) {
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance().queryParam("q", "{q}").queryParam("type", "{type}")
                .queryParam("from", "{from}").queryParam("to", "{to}").queryParam("threshold", "{threshold}");
        String query = builder.encode().build(Map.of("q", q, "type", type, "from", from, "to", to, "threshold", threshold))
                .getRawQuery();
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
        return "fragments/search :: noteForm(noteId=${noteId}, noteTitle=${noteTitle}, noteText=${noteText}, noteError=${noteError})";
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

    private static Document note(String id, long refId, long createdAt, String text, String title) {
        Document.Builder builder = Document.builder().id(id).text(text).metadata("type", TYPE_NOTE).metadata("refId", refId)
                .metadata("createdAt", createdAt);
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
        return documents.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
