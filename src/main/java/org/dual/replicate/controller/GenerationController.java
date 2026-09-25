package org.dual.replicate.controller;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.dual.replicate.service.GenerationParameters;
import org.dual.replicate.service.GenerationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

/**
 * Creazione di una generazione e polling del suo stato. Il polling segue
 * il pattern "stessa URL, due risposte": GET /generations/{id} fa
 * avanzare lo stato e risponde con il solo fragment quando chiamato da
 * htmx (hx-trigger="every 2s"), con la pagina intera altrimenti — che
 * include un meta-refresh come fallback per la navigazione senza JS.
 */
@Controller
@RequestMapping("/generations")
public class GenerationController {

    private final GenerationService generationService;
    private final ReplicateModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;

    public GenerationController(GenerationService generationService,
                                 ReplicateModelCatalog modelCatalog,
                                 ObjectMapper objectMapper) {
        this.generationService = generationService;
        this.modelCatalog = modelCatalog;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/new")
    public String form(@RequestParam(required = false) String prompt, Model model) {
        model.addAttribute("prompt", prompt);
        populateGenerationParamsModel(model, "", GenerationParameters.DEFAULT_WIDTH, GenerationParameters.DEFAULT_HEIGHT,
                GenerationParameters.DEFAULT_OUTPUT_FORMAT, GenerationParameters.DEFAULT_NUM_INFERENCE_STEPS,
                GenerationParameters.DEFAULT_GUIDANCE_SCALE, null, GenerationParameters.DEFAULT_LORA_SCALE,
                GenerationParameters.DEFAULT_FLUX_MODEL, GenerationParameters.DEFAULT_NUM_OUTPUTS);
        return "generate";
    }

    @PostMapping
    public String create(@RequestParam String model,
                          @RequestParam(required = false) String version,
                          @RequestParam String prompt,
                          @RequestParam(required = false) Integer width,
                          @RequestParam(required = false) Integer height,
                          @RequestParam(value = "output_format", required = false) String outputFormat,
                          @RequestParam(value = "num_inference_steps", required = false) Integer numInferenceSteps,
                          @RequestParam(value = "guidance_scale", required = false) Double guidanceScale,
                          @RequestParam(required = false) Long seed,
                          @RequestParam(value = "lora_scale", required = false) Double loraScale,
                          @RequestParam(value = "aspect_ratio", required = false) String aspectRatio,
                          @RequestParam(value = "flux_model", required = false) String fluxModel,
                          @RequestParam(value = "num_outputs", required = false) Integer numOutputs,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          Model uiModel) {
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        try {
            String parametersJson = objectMapper.writeValueAsString(GenerationParameters.toMap(
                    aspectRatio, width, height, outputFormat, numInferenceSteps, guidanceScale, seed, loraScale,
                    fluxModel, numOutputs));
            Generation generation = generationService.create(model, version, prompt, parametersJson);
            if (isHtmxRequest) {
                uiModel.addAttribute("generation", generation);
                return "fragments/generation :: status";
            }
            return "redirect:/generations/" + generation.getId();
        } catch (ReplicateException e) {
            uiModel.addAttribute("error", e.getMessage());
            uiModel.addAttribute("version", version);
            uiModel.addAttribute("prompt", prompt);
            populateGenerationParamsModel(uiModel, model, width, height, outputFormat,
                    numInferenceSteps, guidanceScale, seed, loraScale, fluxModel, numOutputs);
            return isHtmxRequest ? "fragments/generate-form :: form" : "generate";
        }
    }

    /**
     * Attributi richiesti da fragments/generation-params.html (combobox
     * modello + 9 campi tipizzati): usato sia dal primo caricamento di
     * /generations/new sia dal path di errore di create(), altrimenti il
     * fragment ri-renderizzato sul path di errore perderebbe le liste
     * modelli (combobox vuoto) oltre ai valori inseriti dall'utente.
     */
    private void populateGenerationParamsModel(Model model, String modelValue, Integer width, Integer height,
                                                String outputFormat, Integer numInferenceSteps,
                                                Double guidanceScale, Long seed, Double loraScale,
                                                String fluxModel, Integer numOutputs) {
        model.addAttribute("personalModelsJson", objectMapper.writeValueAsString(modelCatalog.personalModels()));
        model.addAttribute("catalogModelsJson", objectMapper.writeValueAsString(modelCatalog.models()));
        model.addAttribute("model", modelValue);
        model.addAttribute("width", width);
        model.addAttribute("height", height);
        model.addAttribute("outputFormat", outputFormat);
        model.addAttribute("numInferenceSteps", numInferenceSteps);
        model.addAttribute("guidanceScale", guidanceScale);
        model.addAttribute("seed", seed);
        model.addAttribute("loraScale", loraScale);
        model.addAttribute("fluxModel", fluxModel);
        model.addAttribute("numOutputs", numOutputs);
    }

    @GetMapping("/{id}")
    public String status(@PathVariable Long id,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          Model model) {
        Generation generation = generationService.refresh(id);
        model.addAttribute("generation", generation);

        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        return isHtmxRequest ? "fragments/generation :: status" : "generation-status";
    }
}
