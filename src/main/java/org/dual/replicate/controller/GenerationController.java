package org.dual.replicate.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationFormType;
import org.dual.replicate.domain.ReplicateModel;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.dual.replicate.service.GenerationParameterHandler;
import org.dual.replicate.service.GenerationParameterHandlers;
import org.dual.replicate.service.GenerationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
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
    private final GenerationParameterHandlers parameterHandlers;
    private final ObjectMapper objectMapper;
    private final Messages messages;

    public GenerationController(GenerationService generationService,
                                 ReplicateModelCatalog modelCatalog,
                                 GenerationParameterHandlers parameterHandlers,
                                 ObjectMapper objectMapper,
                                 Messages messages) {
        this.generationService = generationService;
        this.modelCatalog = modelCatalog;
        this.parameterHandlers = parameterHandlers;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @GetMapping("/new")
    public String form(@RequestParam(required = false) String prompt, Model model) {
        model.addAttribute("prompt", prompt);
        String defaultModel = modelCatalog.defaultModel().map(ReplicateModel::getIdentifier).orElse("");
        populateGenerationParamsModel(model, defaultModel, Map.of());
        return "generate";
    }

    @PostMapping
    public String create(@RequestParam String model,
                          @RequestParam(required = false) String version,
                          @RequestParam String prompt,
                          @RequestParam Map<String, String> allParams,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          Model uiModel) {
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        try {
            GenerationFormType formType = modelCatalog.formTypeOf(model)
                    .orElseThrow(() -> new ReplicateException(messages.get("generateForm.error.unknownModel", model)));
            String resolvedVersion = (version == null || version.isBlank())
                    ? modelCatalog.versionOf(model).orElse(null)
                    : version;
            Map<String, Object> parameters = parameterHandlers.get(formType).toParameterMap(allParams);
            String parametersJson = objectMapper.writeValueAsString(parameters);
            Generation generation = generationService.create(model, resolvedVersion, prompt, parametersJson);
            if (isHtmxRequest) {
                uiModel.addAttribute("generation", generation);
                return "fragments/generation :: status";
            }
            return "redirect:/generations/" + generation.getId();
        } catch (ReplicateException e) {
            uiModel.addAttribute("error", e.getMessage());
            uiModel.addAttribute("version", version);
            uiModel.addAttribute("prompt", prompt);
            populateGenerationParamsModel(uiModel, model, allParams);
            return isHtmxRequest ? "fragments/generate-form :: form" : "generate";
        }
    }

    /**
     * Ri-renderizza solo i campi del form-type del modello selezionato
     * (target #generation-params-fields, vedi fragments/generation-params.html),
     * scatenata dalla &lt;select&gt; modello ad ogni cambio
     * (hx-trigger="change"): cosi' un modello con una form diversa mostra
     * subito i campi giusti. I valori gia' sottomessi (hx-include, vedi
     * il fragment) sono preservati per i campi che il nuovo form-type
     * condivide col precedente, altrimenti si usano i default di quel
     * form-type. Solo fragment: nessuna variante a pagina intera, non
     * avrebbe senso come URL a se stante (stesso principio di
     * DeepChatController#gallery).
     */
    @GetMapping("/params")
    public String params(@RequestParam String model, @RequestParam Map<String, String> allParams, Model uiModel) {
        GenerationFormType formType = modelCatalog.formTypeOf(model)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("generateForm.error.unknownModel", model)));
        GenerationParameterHandler handler = parameterHandlers.get(formType);
        populateFormTypeFields(uiModel, handler, allParams);
        return handler.fragmentName();
    }

    /**
     * Attributi richiesti dal guscio fragments/generation-params.html
     * (combobox modello + contenitore dei campi del form-type corrente):
     * usato sia dal primo caricamento di /generations/new sia dal path
     * di errore di create(), altrimenti il fragment ri-renderizzato sul
     * path di errore perderebbe la lista modelli (select vuota) oltre ai
     * valori inseriti dall'utente.
     */
    private void populateGenerationParamsModel(Model model, String modelValue, Map<String, String> allParams) {
        model.addAttribute("models", modelCatalog.models());
        model.addAttribute("model", modelValue);
        GenerationParameterHandler handler = modelCatalog.formTypeOf(modelValue)
                .map(parameterHandlers::get)
                .orElseGet(() -> modelCatalog.defaultModel()
                        .map(m -> parameterHandlers.get(m.getFormType()))
                        .orElse(null));
        model.addAttribute("formType", handler == null ? null : handler.formType().name());
        if (handler != null) {
            populateFormTypeFields(model, handler, allParams);
        }
    }

    /** Valori dei campi del form-type: quelli sottomessi (se presenti) sopra i default di quel form-type. */
    private void populateFormTypeFields(Model model, GenerationParameterHandler handler, Map<String, String> allParams) {
        Map<String, Object> fields = new LinkedHashMap<>(handler.defaultFields());
        handler.defaultFields().keySet().forEach(key -> {
            String submitted = allParams.get(key);
            if (submitted != null && !submitted.isBlank()) {
                fields.put(key, submitted);
            }
        });
        fields.forEach(model::addAttribute);
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
