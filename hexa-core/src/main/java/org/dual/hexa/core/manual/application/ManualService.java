package org.dual.hexa.core.manual.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.dual.hexa.core.manual.domain.ManualDocument;
import org.dual.hexa.core.manual.domain.ManualEntry;
import org.dual.hexa.core.manual.domain.ManualException;
import org.dual.hexa.core.manual.domain.ManualHeading;
import org.dual.hexa.core.manual.domain.ManualHit;
import org.dual.hexa.core.manual.domain.ManualPage;
import org.dual.hexa.core.manual.domain.ManualSection;
import org.dual.hexa.core.manual.domain.RenderedMarkdown;
import org.dual.hexa.core.manual.port.in.IManual;
import org.dual.hexa.core.manual.port.out.IManualSource;
import org.dual.hexa.core.manual.port.out.IMarkdownRenderer;
import org.springframework.stereotype.Service;

/**
 * Il manuale sopra le sue due porte out: i testi ({@code IManualSource}) e il convertitore ({@code IMarkdownRenderer}). I testi stanno nel jar e non
 * cambiano a processo avviato, quindi l'INDICE (titoli, riassunti, sezioni) si costruisce una volta per lingua; l'HTML di una pagina invece si
 * rigenera a ogni richiesta, perche' i suoi link dipendono dal prefisso (reverse proxy su subpath) e la conversione costa meno di un millisecondo.
 */
@Service
public class ManualService implements IManual {

    /** Il peso di un termine trovato nel titolo della sezione, nel titolo della pagina e nel corpo (per occorrenza, fino a {@link #MAX_BODY_HITS}). */
    private static final int SECTION_TITLE_WEIGHT = 6;
    private static final int PAGE_TITLE_WEIGHT = 3;
    private static final int MAX_BODY_HITS = 5;
    private static final int MIN_TERM_LENGTH = 3;

    /** Parole troppo comuni per distinguere una sezione (italiano e inglese): il bot scrive la domanda dell'utente quasi per intero. */
    private static final Set<String> STOPWORDS = Set.of("come", "che", "con", "per", "una", "uno", "del", "della", "dei", "delle", "dal", "dalla",
            "nel", "nella", "nei", "sul", "sulla", "sono", "cosa", "dove", "posso", "faccio", "fare", "quando", "quale", "quali", "non", "piu",
            "the", "and", "how", "for", "what", "where", "can", "does", "with", "from", "that", "this", "are", "you");

    private final IManualSource source;
    private final IMarkdownRenderer renderer;
    private final Map<String, Index> indexes = new ConcurrentHashMap<>();

    public ManualService(IManualSource source, IMarkdownRenderer renderer) {
        this.source = source;
        this.renderer = renderer;
    }

    /** Una pagina pronta per essere cercata: il testo normalizzato (senza accenti, minuscolo) delle sezioni si calcola una volta. */
    private record IndexedSection(ManualSection section, String normalizedTitle, String normalizedBody) {
    }

    private record IndexedPage(ManualEntry entry, String markdown, String normalizedTitle, List<IndexedSection> sections) {
    }

    private record Index(List<IndexedPage> pages, Map<String, IndexedPage> bySlug) {
    }

    @Override
    public List<ManualEntry> contents(String language) {
        return index(language).pages().stream().map(IndexedPage::entry).toList();
    }

    @Override
    public Optional<ManualPage> page(String slug, String language, String linkPrefix) {
        // Lo slug si cerca SOLO nella mappa dell'indice: dalla richiesta non si costruisce mai un percorso.
        IndexedPage page = index(language).bySlug().get(slug);
        if (page == null) {
            return Optional.empty();
        }
        RenderedMarkdown rendered = renderer.render(page.markdown(), linkPrefix == null ? "" : linkPrefix);
        return Optional.of(new ManualPage(page.entry(), rendered.html(), rendered.headings()));
    }

    @Override
    public List<ManualSection> sections(String slug, String language) {
        IndexedPage page = index(language).bySlug().get(slug);
        return page == null ? List.of() : page.sections().stream().map(IndexedSection::section).toList();
    }

    @Override
    public List<ManualHit> search(String query, String group, String language, int max) {
        List<String> terms = terms(query);
        if (terms.isEmpty() || max <= 0) {
            return List.of();
        }
        List<ManualHit> hits = new ArrayList<>();
        for (IndexedPage page : index(language).pages()) {
            if (group != null && !group.equals(page.entry().group())) {
                continue;
            }
            for (IndexedSection section : page.sections()) {
                int score = score(terms, page, section);
                if (score > 0) {
                    hits.add(new ManualHit(section.section(), page.entry().title(), score));
                }
            }
        }
        // sort() e' stabile: a parita' di punteggio resta l'ordine del manuale.
        hits.sort(Comparator.comparingInt(ManualHit::score).reversed());
        return hits.size() > max ? List.copyOf(hits.subList(0, max)) : List.copyOf(hits);
    }

    private static int score(List<String> terms, IndexedPage page, IndexedSection section) {
        int score = 0;
        for (String term : terms) {
            if (section.normalizedTitle().contains(term)) {
                score += SECTION_TITLE_WEIGHT;
            }
            if (page.normalizedTitle().contains(term)) {
                score += PAGE_TITLE_WEIGHT;
            }
            score += Math.min(occurrences(section.normalizedBody(), term), MAX_BODY_HITS);
        }
        return score;
    }

    private static int occurrences(String text, String term) {
        int count = 0;
        for (int at = text.indexOf(term); at >= 0 && count < MAX_BODY_HITS; at = text.indexOf(term, at + term.length())) {
            count++;
        }
        return count;
    }

    /**
     * I termini di una ricerca: minuscoli, senza accenti, solo parole di almeno tre lettere e non comuni, e privati della vocale finale quando la parola e'
     * lunga ("immagini" e "immagine" danno lo stesso termine, "generazioni" trova "generazione"): basta una ricerca per sottostringa sul testo normalizzato.
     */
    private static List<String> terms(String query) {
        if (query == null) {
            return List.of();
        }
        return Arrays.stream(normalize(query).split("[^a-z0-9]+"))
                .filter(t -> t.length() >= MIN_TERM_LENGTH && !STOPWORDS.contains(t))
                .map(ManualService::stem)
                .distinct()
                .toList();
    }

    private static String stem(String term) {
        return term.length() > 4 && "aeio".indexOf(term.charAt(term.length() - 1)) >= 0 ? term.substring(0, term.length() - 1) : term;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private Index index(String language) {
        return indexes.computeIfAbsent(language == null ? "" : language, this::build);
    }

    private Index build(String language) {
        List<IndexedPage> pages = new ArrayList<>();
        Map<String, IndexedPage> bySlug = new LinkedHashMap<>();
        for (ManualDocument document : source.documents(language)) {
            String markdown = document.markdown().replace("\r\n", "\n");
            RenderedMarkdown rendered = renderer.render(markdown, "");
            String title = rendered.title() != null ? rendered.title() : document.slug();
            ManualEntry entry = new ManualEntry(document.group(), document.slug(), title, rendered.summary());
            List<IndexedSection> sections = sections(entry, markdown, rendered.headings()).stream()
                    .map(s -> new IndexedSection(s, normalize(s.title()), normalize(s.markdown())))
                    .toList();
            IndexedPage page = new IndexedPage(entry, markdown, normalize(title), sections);
            if (bySlug.putIfAbsent(document.slug(), page) != null) {
                throw new ManualException("Due pagine del manuale hanno lo stesso slug: " + document.slug());
            }
            pages.add(page);
        }
        return new Index(List.copyOf(pages), Map.copyOf(bySlug));
    }

    /**
     * Si taglia ai titoli di primo e secondo livello (i sotto-titoli restano nella loro sezione): una sezione e' abbastanza piccola da rispondere a una
     * domanda e abbastanza grande da non perdere il contesto. Il testo prima del primo titolo e' parte della prima sezione.
     */
    private static List<ManualSection> sections(ManualEntry entry, String markdown, List<ManualHeading> headings) {
        String[] lines = markdown.split("\n", -1);
        List<ManualHeading> starts = headings.stream().filter(h -> h.level() <= 2).toList();
        if (starts.isEmpty()) {
            return List.of(new ManualSection(entry.slug(), "", entry.title(), markdown.strip()));
        }
        List<ManualSection> sections = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            ManualHeading start = starts.get(i);
            int from = i == 0 ? 0 : start.line() - 1;
            int to = i + 1 < starts.size() ? starts.get(i + 1).line() - 1 : lines.length;
            String text = Arrays.stream(lines, from, Math.max(from, to)).collect(Collectors.joining("\n")).strip();
            sections.add(new ManualSection(entry.slug(), start.id(), start.text(), text));
        }
        return List.copyOf(sections);
    }
}
