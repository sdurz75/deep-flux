package org.dual.hexa.app.training.adapter.in.web;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.hexa.app.generation.domain.ApiTokenProvider;
import org.dual.hexa.app.training.domain.LoraType;
import org.dual.hexa.app.training.domain.Training;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.domain.TrainingException;
import org.dual.hexa.app.training.domain.TrainingImage;
import org.dual.hexa.app.training.domain.UploadReport;
import org.dual.hexa.app.training.port.in.ITrainingCaptions;
import org.dual.hexa.app.training.port.in.ITrainingDatasets;
import org.dual.hexa.app.training.port.in.ITrainings;
import org.dual.hexa.core.kernel.Paged;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.dual.hexa.core.web.PaginationSupport;
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
 * Addestramento di un LoRA, parte dei dataset ({@link ITrainingDatasets}): l'hub ({@code /trainings}: elenco delle bozze con il form di creazione, oppure lo storico
 * dei training con {@code ?tab=history}) e l'editor di una bozza ({@code /trainings/datasets/{id}}), che porta anche il pannello "Avvia" (impostazioni di lancio e
 * controlli: le azioni sono in {@link TrainingRunController}, qui si prepara solo il Model). I form di creazione e di modifica sono NATIVI (POST + redirect: la bozza e' sul server, funzionano anche senza JS);
 * caricamento e rimozione delle immagini sono htmx e rimpiazzano {@code #training-images}; Clona ed Elimina rispondono con {@code HX-Redirect}.
 * Un rifiuto di validazione ricompare nella pagina; un rifiuto su un'azione htmx lo traduce il resolver in un toast.
 */
@Controller
@RequestMapping("/trainings")
public class TrainingController {

    /** Bozze per pagina dell'elenco. */
    static final int PAGE_SIZE = 12;

    private final ITrainingDatasets datasets;
    private final ITrainingCaptions captions;
    private final ITrainings trainings;
    private final IApiTokens tokens;
    /** Lato lungo massimo di un ritaglio: lo usa solo il browser (canvas), il server non decodifica le immagini. */
    private final int maxImageSide;

    public TrainingController(ITrainingDatasets datasets, ITrainingCaptions captions, ITrainings trainings, IApiTokens tokens,
                              @Value("${app.training.max-image-side:1536}") int maxImageSide) {
        this.datasets = datasets;
        this.captions = captions;
        this.trainings = trainings;
        this.tokens = tokens;
        this.maxImageSide = maxImageSide;
    }

    /**
     * L'hub, con due schede: {@code datasets} (le bozze, con il form di creazione) e {@code history} (lo storico dei training). Stessa URL, due risposte: la
     * paginazione htmx riceve il solo contenuto della scheda, la navigazione la pagina intera. Le schede sono link normali (pagina intera).
     */
    @GetMapping
    public String list(@RequestParam(defaultValue = "datasets") String tab, @RequestParam(defaultValue = "1") int page,
                       @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        boolean htmx = "true".equalsIgnoreCase(hxRequest);
        if ("history".equals(tab)) {
            populateHistory(model, page);
            model.addAttribute("tab", "history");
            return htmx
                    ? "fragments/app/training-history :: content(trainings=${trainings}, currentPage=${currentPage}, totalPages=${totalPages}, "
                            + "hasPrevious=${hasPrevious}, hasNext=${hasNext}, pageNumbers=${pageNumbers})"
                    : "app/training";
        }
        populateList(model, page);
        model.addAttribute("tab", "datasets");
        model.addAttribute("formName", "");
        model.addAttribute("formTriggerWord", "TOK");
        model.addAttribute("formType", "subject");
        return htmx
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
            model.addAttribute("tab", "datasets");
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
        populateLaunch(model, found.get());
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
            populateLaunch(model, found.get());
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

    // --- didascalie ---------------------------------------------------------------------------------------------

    /**
     * La didascalia di un'immagine: e' il target del polling delle card in sospeso (ogni 3 s). Un'immagine o una bozza sparite nel frattempo non sono un errore
     * da mostrare: la risposta e' vuota e l'elemento sparisce.
     */
    @GetMapping("/datasets/{id}/images/{imageId}/caption")
    public String captionBox(@PathVariable Long id, @PathVariable Long imageId, Model model) {
        Optional<TrainingDataset> dataset = datasets.find(id);
        Optional<TrainingImage> image = dataset.flatMap(d -> d.findImage(imageId));
        return image.isEmpty() ? "fragments/app/training-caption :: none" : box(model, dataset.get(), image.get());
    }

    /** Salva la didascalia scritta a mano (al `change` della textarea): risponde con la stessa didascalia. */
    @PostMapping("/datasets/{id}/images/{imageId}/caption")
    public String saveCaption(@PathVariable Long id, @PathVariable Long imageId, @RequestParam(defaultValue = "") String caption,
                              @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        TrainingDataset saved = captions.saveCaption(id, imageId, caption);
        return captionResponse(id, imageId, saved, hxRequest, model);
    }

    /** Rigenera la didascalia di una immagine (anche scritta a mano: e' una richiesta esplicita). */
    @PostMapping("/datasets/{id}/images/{imageId}/recaption")
    public String recaption(@PathVariable Long id, @PathVariable Long imageId,
                            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        TrainingDataset saved = captions.recaption(id, imageId);
        return captionResponse(id, imageId, saved, hxRequest, model);
    }

    /** Rigenera le automatiche (e le mancanti o non riuscite): risponde con tutta la griglia, in cui le card in sospeso ripartono col polling. */
    @PostMapping("/datasets/{id}/captions/regenerate")
    public String regenerateCaptions(@PathVariable Long id, @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        captions.recaptionAutomatic(id);
        return imagesView(id, null, hxRequest, model);
    }

    /** Mette la trigger word davanti alle didascalie che non la nominano. */
    @PostMapping("/datasets/{id}/captions/trigger-word")
    public String addTriggerWord(@PathVariable Long id, @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        captions.addTriggerWord(id);
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

    /** Con htmx la sola didascalia; un invio nativo (senza JS) torna all'editor. */
    private String captionResponse(Long id, Long imageId, TrainingDataset saved, String hxRequest, Model model) {
        if (!"true".equalsIgnoreCase(hxRequest)) {
            return "redirect:/trainings/datasets/" + id;
        }
        return box(model, saved, saved.findImage(imageId).orElseThrow());
    }

    private static String box(Model model, TrainingDataset dataset, TrainingImage image) {
        model.addAttribute("dataset", dataset);
        model.addAttribute("image", image);
        return "fragments/app/training-caption :: box(dataset=${dataset}, image=${image})";
    }

    private String imagesView(Long id, UploadReport report, String hxRequest, Model model) {
        TrainingDataset dataset = datasets.get(id);
        populateEditor(model, dataset, report);
        if ("true".equalsIgnoreCase(hxRequest)) {
            return "fragments/app/training-dataset :: images(dataset=${dataset}, report=${report}, maxImages=${maxImages}, maxBytes=${maxBytes})";
        }
        populateForm(model, dataset);
        populateLaunch(model, dataset);
        return "app/training-dataset";
    }

    /**
     * Il pannello "Avvia" di una bozza: controlli di lancio (sola lettura, nessuna chiamata remota) e i token HuggingFace fra cui scegliere (nome e scadenza,
     * mai il segreto). Per uno snapshot, invece, il training a cui appartiene (per il link): non ha impostazioni da cambiare.
     */
    private void populateLaunch(Model model, TrainingDataset dataset) {
        if (dataset.isFrozen()) {
            Training owner = trainings.findBySnapshot(dataset.getId()).orElse(null);
            model.addAttribute("snapshotTraining", owner);
            return;
        }
        model.addAttribute("launchCheck", trainings.check(dataset.getId()));
        model.addAttribute("minSteps", datasets.minSteps());
        model.addAttribute("maxSteps", datasets.maxSteps());
        model.addAttribute("hfTokens", tokens.options(ApiTokenProvider.HUGGINGFACE.name()));
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

    /** Una pagina dello storico (1-based), con lo stesso riporto all'ultima pagina esistente dell'elenco delle bozze. */
    private void populateHistory(Model model, int page) {
        int pageIndex = Math.max(0, page - 1);
        Paged<Training> result = trainings.page(pageIndex, PAGE_SIZE);
        if (result.isEmpty() && result.totalPages() > 0 && pageIndex >= result.totalPages()) {
            pageIndex = result.totalPages() - 1;
            result = trainings.page(pageIndex, PAGE_SIZE);
        }
        int currentPage = pageIndex + 1;
        model.addAttribute("trainings", result.content());
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", result.totalPages());
        model.addAttribute("hasPrevious", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        model.addAttribute("pageNumbers", PaginationSupport.window(currentPage, result.totalPages()));
    }
}
