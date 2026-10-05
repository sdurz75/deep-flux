package org.dual.replicate.app.training.adapter.in.web;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.UploadReport;
import org.dual.replicate.app.training.port.in.ITrainingDatasets;
import org.dual.replicate.core.kernel.Paged;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.dual.replicate.core.web.PaginationSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

/**
 * Addestramento di un LoRA, parte dei dataset ({@link ITrainingDatasets}): l'elenco delle bozze con il form di creazione ({@code /trainings}) e l'editor
 * di una bozza ({@code /trainings/datasets/{id}}). I form di creazione e di modifica sono NATIVI (POST + redirect: la bozza e' sul server, funzionano anche senza JS);
 * caricamento e rimozione delle immagini sono htmx e rimpiazzano {@code #training-images}; Clona ed Elimina rispondono con {@code HX-Redirect}.
 * Un rifiuto di validazione ricompare nella pagina; un rifiuto su un'azione htmx lo traduce il resolver in un toast.
 */
@Controller
@RequestMapping("/trainings")
public class TrainingController {

    /** Bozze per pagina dell'elenco. */
    static final int PAGE_SIZE = 12;

    private final ITrainingDatasets datasets;
    /** Lato lungo massimo di un ritaglio: lo usa solo il browser (canvas), il server non decodifica le immagini. */
    private final int maxImageSide;

    public TrainingController(ITrainingDatasets datasets, @Value("${app.training.max-image-side:1536}") int maxImageSide) {
        this.datasets = datasets;
        this.maxImageSide = maxImageSide;
    }

    /** Stessa URL, due risposte: la paginazione htmx riceve il solo contenuto, la navigazione la pagina intera. */
    @GetMapping
    public String list(@RequestParam(defaultValue = "1") int page,
                       @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        populateList(model, page);
        model.addAttribute("formName", "");
        model.addAttribute("formTriggerWord", "TOK");
        model.addAttribute("formType", "subject");
        return "true".equalsIgnoreCase(hxRequest)
                ? "fragments/app/training-datasets :: content(datasets=${datasets}, currentPage=${currentPage}, totalPages=${totalPages}, "
                        + "hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers})"
                : "app/training";
    }

    @PostMapping("/datasets")
    public String create(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String triggerWord,
                         @RequestParam(defaultValue = "subject") String loraType, Model model) {
        try {
            TrainingDataset created = datasets.create(name, triggerWord, LoraType.parse(loraType).orElse(null), null);
            return "redirect:/trainings/datasets/" + created.getId();
        } catch (TrainingException e) {
            if (e.isReportable()) {
                throw e;
            }
            populateList(model, 1);
            model.addAttribute("createError", e.getMessage());
            model.addAttribute("formName", name);
            model.addAttribute("formTriggerWord", triggerWord);
            model.addAttribute("formType", loraType);
            return "app/training";
        }
    }

    /** L'editor di una bozza; uno snapshot (congelato) si apre in sola lettura, un id sconosciuto riporta all'elenco. */
    @GetMapping("/datasets/{id}")
    public String editor(@PathVariable Long id, Model model) {
        Optional<TrainingDataset> found = datasets.find(id);
        if (found.isEmpty()) {
            return "redirect:/trainings";
        }
        populateEditor(model, found.get(), null);
        populateForm(model, found.get());
        return "app/training-dataset";
    }

    @PostMapping("/datasets/{id}")
    public String update(@PathVariable Long id, @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String triggerWord,
                         @RequestParam(defaultValue = "subject") String loraType, @RequestParam(defaultValue = "") String note, Model model) {
        try {
            datasets.update(id, name, triggerWord, LoraType.parse(loraType).orElse(null), note);
            return "redirect:/trainings/datasets/" + id;
        } catch (TrainingException e) {
            if (e.isReportable()) {
                throw e;
            }
            Optional<TrainingDataset> found = datasets.find(id);
            if (found.isEmpty()) {
                return "redirect:/trainings";
            }
            populateEditor(model, found.get(), null);
            model.addAttribute("error", e.getMessage());
            model.addAttribute("formName", name);
            model.addAttribute("formTriggerWord", triggerWord);
            model.addAttribute("formType", loraType);
            model.addAttribute("formNote", note);
            return "app/training-dataset";
        }
    }

    /** Aggiunge immagini. Con htmx risponde il solo contenuto di {@code #training-images}; un invio nativo la pagina intera. */
    @PostMapping("/datasets/{id}/images")
    public String addImages(@PathVariable Long id, @RequestParam(value = "images", required = false) List<MultipartFile> images,
                            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        List<MultipartFile> files = images == null ? List.of() : images;
        UploadReport report = datasets.addImages(id, files.stream().map(UploadedFiles::of).toList());
        return imagesView(id, report, hxRequest, model);
    }

    @PostMapping("/datasets/{id}/images/{imageId}/delete")
    public String removeImage(@PathVariable Long id, @PathVariable Long imageId,
                              @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        datasets.removeImage(id, imageId);
        return imagesView(id, null, hxRequest, model);
    }

    /**
     * Ritaglio di un'immagine: il browser ritaglia sull'originale (editor in {@code crop-editor.html}) e invia il risultato con il rettangolo in
     * pixel dell'originale. Risponde col contenuto di {@code #training-images}, come caricamento e rimozione.
     */
    @PostMapping("/datasets/{id}/crop")
    public String crop(@PathVariable Long id, @RequestParam Long imageId, @RequestParam("file") MultipartFile file,
                       @RequestParam int x, @RequestParam int y, @RequestParam int w, @RequestParam int h,
                       @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        datasets.cropImage(id, imageId, UploadedFiles.of(file), x, y, w, h);
        return imagesView(id, null, hxRequest, model);
    }

    /** Torna all'originale. */
    @PostMapping("/datasets/{id}/images/{imageId}/crop/reset")
    public String resetCrop(@PathVariable Long id, @PathVariable Long imageId,
                            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        datasets.resetCrop(id, imageId);
        return imagesView(id, null, hxRequest, model);
    }

    /** Nuova bozza dalla stessa (anche uno snapshot): e' l'editor della copia che si apre. */
    @PostMapping("/datasets/{id}/duplicate")
    @ResponseBody
    public void duplicate(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) {
        TrainingDataset copy = datasets.duplicate(id);
        response.setHeader("HX-Redirect", request.getContextPath() + "/trainings/datasets/" + copy.getId());
    }

    @PostMapping("/datasets/{id}/delete")
    @ResponseBody
    public void delete(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) {
        datasets.delete(id);
        response.setHeader("HX-Redirect", request.getContextPath() + "/trainings");
    }

    // --- interno ------------------------------------------------------------------------------------------------

    private String imagesView(Long id, UploadReport report, String hxRequest, Model model) {
        TrainingDataset dataset = datasets.get(id);
        populateEditor(model, dataset, report);
        if ("true".equalsIgnoreCase(hxRequest)) {
            return "fragments/app/training-dataset :: images(dataset=${dataset}, report=${report}, maxImages=${maxImages}, maxBytes=${maxBytes})";
        }
        populateForm(model, dataset);
        return "app/training-dataset";
    }

    /** I valori del form di configurazione: quelli salvati (dopo un errore di validazione il chiamante li sovrascrive con quelli digitati). */
    private static void populateForm(Model model, TrainingDataset dataset) {
        model.addAttribute("formName", dataset.getName());
        model.addAttribute("formTriggerWord", dataset.getTriggerWord());
        model.addAttribute("formType", dataset.getLoraType().name().toLowerCase(Locale.ROOT));
        model.addAttribute("formNote", dataset.getNote());
    }

    private void populateEditor(Model model, TrainingDataset dataset, UploadReport report) {
        model.addAttribute("dataset", dataset);
        model.addAttribute("report", report);
        model.addAttribute("maxImages", datasets.maxImages());
        model.addAttribute("maxBytes", IImageStorageService.MAX_UPLOAD_BYTES);
        model.addAttribute("maxImageSide", maxImageSide);
    }

    /** Una pagina dell'elenco (1-based); con una pagina oltre l'ultima (ultima bozza eliminata) si torna all'ultima che esiste. */
    private void populateList(Model model, int page) {
        int pageIndex = Math.max(0, page - 1);
        Paged<TrainingDataset> result = datasets.page(pageIndex, PAGE_SIZE);
        if (result.isEmpty() && result.totalPages() > 0 && pageIndex >= result.totalPages()) {
            pageIndex = result.totalPages() - 1;
            result = datasets.page(pageIndex, PAGE_SIZE);
        }
        int currentPage = pageIndex + 1;
        model.addAttribute("datasets", result.content());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", result.totalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageNumbers", PaginationSupport.window(currentPage, result.totalPages()));
    }
}
