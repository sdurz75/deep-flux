package org.dual.hexa.app.training.adapter.in.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.hexa.app.generation.domain.ApiTokenProvider;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.app.shared.domain.AppEventSubjects;
import org.dual.hexa.app.training.domain.LaunchSettings;
import org.dual.hexa.app.training.domain.Training;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.port.in.ITrainingDatasets;
import org.dual.hexa.app.training.port.in.ITrainingHfUploads;
import org.dual.hexa.app.training.port.in.ITrainings;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
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

/**
 * Il training vero ({@link ITrainings}): impostazioni di lancio e controlli di una bozza, avvio, e il dettaglio di un training con il suo avanzamento.
 * Un rifiuto su un'azione htmx (un controllo non superato, un training gia' in corso) lo traduce il resolver in un toast; l'avvio e l'eliminazione rispondono con
 * {@code HX-Redirect}. {@code /trainings/{id}} e' un training, {@code /trainings/datasets/{id}} una bozza: i due spazi di id non si confondono.
 *
 * <p>Il pannello "Avvia" richiede JS (htmx): l'avvio e' un'azione a pagamento con conferma, e i controlli si aggiornano da soli mentre le didascalie finiscono.
 */
@Controller
@RequestMapping("/trainings")
public class TrainingRunController {

    private final ITrainings trainings;
    private final ITrainingDatasets datasets;
    private final ILoraPresets presets;
    private final ITrainingHfUploads hfUploads;
    private final IApiTokens tokens;
    private final ISystemEvents systemEvents;
    /** Per quanto tempo dopo il successo il blocco stato controlla che il risultato si completi: oltre, lo sweep ha smesso di riprenderlo e nulla lo completera'. */
    private final Duration resultWindow;

    public TrainingRunController(ITrainings trainings, ITrainingDatasets datasets, ILoraPresets presets, ITrainingHfUploads hfUploads, IApiTokens tokens,
                                 ISystemEvents systemEvents, @Value("${app.training.result-retry-window:6h}") Duration resultWindow) {
        this.trainings = trainings;
        this.datasets = datasets;
        this.presets = presets;
        this.hfUploads = hfUploads;
        this.tokens = tokens;
        this.systemEvents = systemEvents;
        this.resultWindow = resultWindow;
    }

    // --- pannello "Avvia" di una bozza --------------------------------------------------------------------------

    /**
     * I controlli di lancio (blocchi e avvisi) con il bottone "Avvia": e' il target del polling del pannello. Una bozza sparita (o diventata uno snapshot) svuota
     * il blocco, che cosi' smette di interrogare.
     */
    @GetMapping("/datasets/{id}/launch-check")
    public String launchCheck(@PathVariable Long id, Model model) {
        Optional<TrainingDataset> draft = datasets.find(id).filter(d -> !d.isFrozen());
        if (draft.isEmpty()) {
            return "fragments/app/training-launch :: gone";
        }
        return checkView(draft.get(), model);
    }

    /**
     * Salva le impostazioni di lancio della bozza (azione leggera, htmx sul form) e risponde coi controlli ricalcolati. I checkbox arrivano solo se spuntati:
     * assenti = spenti. Un numero illeggibile diventa un valore fuori limite, cosi' il rifiuto e' quello di sempre. Senza JS torna all'editor.
     */
    @PostMapping("/datasets/{id}/launch-settings")
    public String saveLaunchSettings(@PathVariable Long id, @RequestParam(defaultValue = "") String modelName,
                                     @RequestParam(defaultValue = "") String trainingSteps, @RequestParam(defaultValue = "") String seed,
                                     @RequestParam(required = false) String hfPublish, @RequestParam(defaultValue = "") String hfTokenId,
                                     @RequestParam(defaultValue = "") String hfRepoName, @RequestParam(required = false) String hfPrivate,
                                     @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        LaunchSettings settings = new LaunchSettings(modelName, parseInt(trainingSteps), parseSeed(seed), hfPublish != null, parseLong(hfTokenId),
                hfRepoName, hfPrivate != null);
        TrainingDataset saved = datasets.saveLaunchSettings(id, settings);
        if (!"true".equalsIgnoreCase(hxRequest)) {
            return "redirect:/trainings/datasets/" + id;
        }
        return checkView(saved, model);
    }

    /** Lancia il training (A PAGAMENTO): al successo si va alla sua pagina. */
    @PostMapping("/datasets/{id}/start")
    @ResponseBody
    public void start(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) {
        Training training = trainings.start(id);
        response.setHeader("HX-Redirect", request.getContextPath() + "/trainings/" + training.getId());
    }

    // --- dettaglio di un training -------------------------------------------------------------------------------

    /** Un id sconosciuto riporta allo storico. La pagina mostra lo stato salvato: l'aggiornamento (chiamata a Replicate) lo fa il polling del blocco stato. */
    @GetMapping("/{id:\\d+}")
    public String detail(@PathVariable Long id, Model model) {
        Optional<Training> found = trainings.find(id);
        if (found.isEmpty()) {
            return "redirect:/trainings?tab=history";
        }
        populateRun(model, found.get());
        return "app/training-run";
    }

    /**
     * Il blocco stato ({@code #training-status}), target del polling ogni 5 s finche' il training non e' terminale (poi il fragment non ha piu' il trigger).
     * Fa avanzare il training interrogando Replicate; un training eliminato nel frattempo svuota il blocco.
     */
    @GetMapping("/{id:\\d+}/status")
    public String status(@PathVariable Long id, Model model) {
        if (trainings.find(id).isEmpty()) {
            return "fragments/app/training-run :: gone";
        }
        return statusView(trainings.refresh(id), model);
    }

    /** Interrompe il training; risponde col blocco stato aggiornato (l'esito vero: se era appena finito, e' quello). */
    @PostMapping("/{id:\\d+}/cancel")
    public String cancel(@PathVariable Long id, Model model) {
        return statusView(trainings.cancel(id), model);
    }

    /**
     * Avvia il caricamento a mano dei pesi su HuggingFace (il rimedio quando il trainer non l'ha fatto) e risponde col blocco stato gia' "in corso", che si aggiorna da
     * solo. Ritorna subito: il lavoro e' in background. Un rifiuto (token scaduto o di sola lettura, training non caricabile, uno gia' in corso) e' un toast.
     */
    @PostMapping("/{id:\\d+}/hf-upload")
    public String hfUpload(@PathVariable Long id, @RequestParam(defaultValue = "") String hfTokenId, Model model) {
        hfUploads.request(id, parseLong(hfTokenId));
        return statusView(trainings.get(id), model);
    }

    /** Elimina il training col suo dataset congelato (non il modello Replicate, il repo HuggingFace ne' il preset). */
    @PostMapping("/{id:\\d+}/delete")
    @ResponseBody
    public void delete(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) {
        trainings.delete(id);
        response.setHeader("HX-Redirect", request.getContextPath() + "/trainings?tab=history");
    }

    // --- interno ------------------------------------------------------------------------------------------------

    private String checkView(TrainingDataset dataset, Model model) {
        model.addAttribute("dataset", dataset);
        model.addAttribute("launchCheck", trainings.check(dataset.getId()));
        return "fragments/app/training-launch :: check(datasetId=${dataset.id}, check=${launchCheck})";
    }

    private String statusView(Training training, Model model) {
        populateRun(model, training);
        return "fragments/app/training-run :: status(training=${training}, snapshot=${snapshot}, preset=${preset}, polling=${polling})";
    }

    private void populateRun(Model model, Training training) {
        model.addAttribute("training", training);
        model.addAttribute("snapshot", datasets.find(training.getSnapshotDatasetId()).orElse(null));
        model.addAttribute("preset", presetOf(training));
        boolean uploading = hfUploads.isUploading(training.getId());
        boolean uploadable = hfUploads.canUpload(training);
        model.addAttribute("hfUploading", uploading);
        model.addAttribute("hfUploadable", uploadable);
        model.addAttribute("hfTokens", uploadable ? tokens.options(ApiTokenProvider.HUGGINGFACE.name()) : List.of());
        model.addAttribute("polling", uploading || isPolling(training));
    }

    /** Il blocco stato continua a interrogare finche' il training e' in corso (o un caricamento su HuggingFace lo e': l'esito arriva da solo), e dopo il successo finche' il risultato e' incompleto ENTRO la finestra di ripresa. */
    private boolean isPolling(Training training) {
        if (!training.isTerminal()) {
            return true;
        }
        Instant completed = training.getCompletedAt() != null ? training.getCompletedAt() : training.getCreatedAt();
        return training.isResultIncomplete() && Duration.between(completed, Instant.now()).compareTo(resultWindow) < 0;
    }

    /** Il preset creato dal training, o null se non c'e' ancora (il risultato nasce in background) o l'utente lo ha eliminato; un guasto vero e' registrato. */
    private ILoraPresets.LoraView presetOf(Training training) {
        if (training.getPresetId() == null) {
            return null;
        }
        try {
            return presets.get(training.getPresetId());
        } catch (RemoteServiceException e) {
            if (e.isReportable()) {
                systemEvents.record("getLoraPreset", e, AppEventSubjects.ofTraining(training.getId()));
            } // altrimenti il preset non c'e' (piu'): atteso
            return null;
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return 0; // fuori dai limiti: il rifiuto e' quello dei passi
        }
    }

    private static Long parseSeed(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return -1L; // fuori dai limiti: il rifiuto e' quello del seed
        }
    }

    private static Long parseLong(String text) {
        try {
            return text.isBlank() ? null : Long.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
