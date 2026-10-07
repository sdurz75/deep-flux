package org.hexa.app.generation.adapter.in.web;

import java.util.List;
import java.util.Optional;

import org.hexa.app.generation.domain.GalleryItem;
import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.ImportReport;
import org.hexa.app.generation.port.in.IGenerations;
import org.hexa.app.generation.port.in.IImportedImages;
import org.hexa.core.storage.port.in.IImageStorageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Immagini di provenienza esterna ({@link IImportedImages}): pagina {@code /import} con un form multipart (funziona anche senza JS) migliorato
 * da una dropzone Alpine (trascina, incolla, selezione multipla, anteprime), l'esito per file e l'elenco delle ultime importate con lo stato
 * dell'analisi di contenuto. Stessa URL, due risposte: htmx riceve il fragment, un invio nativo la pagina intera.
 */
@Controller
@RequestMapping("/import")
public class ImportController {

    /** Quante importate mostra "Ultime importate". */
    static final int RECENT = 12;

    private final IImportedImages imports;
    private final IGenerations generations;

    public ImportController(IImportedImages imports, IGenerations generations) {
        this.imports = imports;
        this.generations = generations;
    }

    @GetMapping
    public String page(Model model) {
        populatePage(model);
        return "app/import";
    }

    @PostMapping
    public String importImages(@RequestParam(value = "images", required = false) List<MultipartFile> images,
                               @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        List<MultipartFile> files = images == null ? List.of() : images;
        ImportReport report = imports.importImages(files.stream().map(UploadedFiles::of).toList());
        model.addAttribute("report", report);
        if ("true".equalsIgnoreCase(hxRequest)) {
            return "fragments/app/import-result :: result(report=${report})";
        }
        populatePage(model);
        return "app/import";
    }

    /** Le ultime importate (si ricarica da sola a ogni {@code gallery-update}: importazione o fine analisi). */
    @GetMapping("/recent")
    public String recent(Model model) {
        model.addAttribute("items", recentItems());
        return "fragments/app/import-recent :: section(items=${items})";
    }

    /**
     * Dettaglio di un'immagine importata: pagina propria (non quella di una "Generazione": niente prompt/modello/costo, non c'e' stata una prediction).
     * Una riga non importata va al suo dettaglio ({@code /generations/{id}}, che a sua volta rimanda qui per le importate); una cancellata, all'elenco.
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Optional<Generation> found = generations.find(id);
        if (found.isEmpty()) {
            return "redirect:/import";
        }
        Generation generation = found.get();
        if (!generation.isImported()) {
            return "redirect:/generations/" + id;
        }
        model.addAttribute("generation", generation);
        return "app/import-detail";
    }

    /** Lo stato dell'analisi di UNA immagine: si ripete da solo finche' e' in corso (vedi {@code import-analysis.html}). */
    @GetMapping("/{id}/analysis")
    public String analysis(@PathVariable Long id, Model model) {
        model.addAttribute("generation", generations.get(id));
        return "fragments/app/import-analysis :: box(generation=${generation})";
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable Long id, Model model) {
        imports.retryAnalysis(id);
        model.addAttribute("generation", generations.get(id));
        return "fragments/app/import-analysis :: box(generation=${generation})";
    }

    private void populatePage(Model model) {
        model.addAttribute("maxBytes", IImageStorageService.MAX_UPLOAD_BYTES);
        model.addAttribute("maxFiles", imports.maxFiles());
        model.addAttribute("items", recentItems());
    }

    private List<GalleryItem> recentItems() {
        return generations.importedPage(0, RECENT).content();
    }
}
