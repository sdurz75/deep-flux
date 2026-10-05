package org.dual.replicate.app.training.adapter.in.web;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.app.training.domain.LaunchSettings;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.port.in.ITrainingDatasets;
import org.dual.replicate.app.training.port.in.ITrainings;
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

    public TrainingRunController(ITrainings trainings, ITrainingDatasets datasets) {
        this.trainings = trainings;
        this.datasets = datasets;
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
        return "fragments/app/training-run :: status(training=${training}, snapshot=${snapshot})";
    }

    private void populateRun(Model model, Training training) {
        model.addAttribute("training", training);
        model.addAttribute("snapshot", datasets.find(training.getSnapshotDatasetId()).orElse(null));
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
