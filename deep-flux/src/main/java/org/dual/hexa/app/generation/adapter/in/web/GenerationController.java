package org.dual.hexa.app.generation.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.hexa.app.shared.domain.AppEventSubjects;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.storage.domain.UploadedFile;
import org.dual.hexa.core.storage.domain.SourceImage;
import org.dual.hexa.core.web.HtmxEvents;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationConfig;
import org.dual.hexa.app.generation.domain.GenerationFormType;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.ReplicateModel;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.app.generation.domain.ReplicateException;
import org.dual.hexa.app.generation.port.in.IModelCatalog;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.app.generation.port.in.IGenerationForms;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.dual.hexa.ai.llm.domain.PromptEnhancementRefusedException;
import org.dual.hexa.ai.llm.port.in.IPromptEnhancer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creazione di una generazione, listato paginato (qualunque stato) e
 * dettaglio/cancellazione. Il dettaglio vive sulla stessa
 * GET /generations/{id} del polling di stato ("stessa URL, due risposte":
 * fragment se chiamato da htmx via hx-trigger="every 2s" mentre non
 * terminale, pagina intera altrimenti) - a stato terminale quello stesso
 * fragment (fragments/app/generation.html :: status) mostra anche prompt/
 * parametri/immagini cancellabili, niente pagina di dettaglio separata
 * (vedi CLAUDE.md).
 */
@Controller
@RequestMapping("/generations")
public class GenerationController {


    private final IGenerations generationService;
    private final IModelCatalog modelCatalog;
    private final IGenerationForms forms;
    private final Messages messages;
    private final IPromptEnhancer promptEnhancementService;
    private final IImageStorageService imageStorageService;
    private final ISystemEvents systemEvents;
    private final HtmxEvents htmx;

    public GenerationController(IGenerations generationService,
                                 IModelCatalog modelCatalog,
                                 IGenerationForms forms,
                                 Messages messages,
                                 IPromptEnhancer promptEnhancementService,
                                 IImageStorageService imageStorageService,
                                 ISystemEvents systemEvents, HtmxEvents htmx) {
        this.systemEvents = systemEvents;
        this.htmx = htmx;
        this.generationService = generationService;
        this.modelCatalog = modelCatalog;
        this.forms = forms;
        this.messages = messages;
        this.promptEnhancementService = promptEnhancementService;
        this.imageStorageService = imageStorageService;
    }

    /** Tetto di {@code num_outputs} per i fragment dei form-type: Thymeleaf non permette {@code T(...)} nei parametri di un fragment. */
    @ModelAttribute("maxNumOutputs")
    public int maxNumOutputs() {
        return IGenerationForms.MAX_NUM_OUTPUTS;
    }

    /**
     * Limite dell'upload sorgente (IImageStorageService#storeUpload) per il controllo lato client del
     * campo sourceUpload di P_VIDEO: in ogni vista di questo controller, anche /params, perche' li'
     * Thymeleaf non permette T(...) sugli attributi data-*.
     */
    @ModelAttribute("maxUploadBytes")
    public long maxUploadBytes() {
        return IImageStorageService.MAX_UPLOAD_BYTES;
    }

    @GetMapping("/new")
    public String form(@RequestParam(required = false) String prompt,
                        @RequestParam(required = false) Long source,
                        @RequestParam(required = false) String sourceImage,
                        @RequestParam(required = false) String kind,
                        @RequestParam(required = false) Long config,
                        @RequestParam(required = false) String file,
                        Model model) {
        // "Usa configurazione": ripropone modello, parametri, prompt e seed di una generazione passata, ricostruiti dal server.
        // Ha la precedenza su kind/source/prompt; se non si puo' riproporre, pagina normale con il motivo.
        if (config != null) {
            Optional<GenerationConfig> reused = generationService.reuseConfig(config, file == null || file.isBlank() ? null : file);
            if (reused.isEmpty()) {
                model.addAttribute("error", messages.get("generateForm.error.reuseNotFound"));
            } else if (!modelCatalog.contains(reused.get().model())) {
                model.addAttribute("error", messages.get("generateForm.error.reuseModelUnavailable", reused.get().model()));
            } else {
                return reuseForm(reused.get(), model);
            }
            return form(null, null, null, null, null, null, model);
        }
        String defaultModel = modelCatalog.models(GenerationKind.IMAGE).stream().findFirst()
                .map(ReplicateModel::getIdentifier).orElse("");
        // Video stand-alone (link dell'header): preseleziona il primo modello video, la sorgente
        // e' l'immagine caricata dall'utente nel form (campo sourceUpload di P_VIDEO).
        if ("video".equalsIgnoreCase(kind)) {
            defaultModel = modelCatalog.models(GenerationKind.VIDEO).stream().findFirst()
                    .map(ReplicateModel::getIdentifier).orElse(defaultModel);
        }
        // "Anima"/"Usa come sorgente" (vedi fragments/app/generation.html :: status): preseleziona il primo modello
        // video (immagine con sorgente se kind=image) e porta con se' la generazione immagine sorgente (hidden sourceGenerationId nel form).
        // kind=edit (vecchi link della pagina di modifica, ormai unita a quella delle immagini) vale kind=image.
        Generation sourceGeneration = source == null ? null : animatableSource(source, sourceImage);
        if (sourceGeneration != null) {
            if ("image".equalsIgnoreCase(kind) || "edit".equalsIgnoreCase(kind)) {
                // "Usa come sorgente" / "Scegli dall'archivio": img2img, il primo modello immagine che prende una sorgente (ff3, flux-dev-lora);
                // kontext e flux-fill-* li sceglie l'utente dal combobox.
                defaultModel = modelCatalog.models(GenerationKind.IMAGE).stream()
                        .filter(m -> m.getFormType().takesSourceImage()).findFirst()
                        .map(ReplicateModel::getIdentifier).orElse(defaultModel);
            } else {
                defaultModel = modelCatalog.models(GenerationKind.VIDEO).stream().findFirst()
                        .map(ReplicateModel::getIdentifier).orElse(defaultModel);
            }
            model.addAttribute("sourceGeneration", sourceGeneration);
            model.addAttribute("sourceImage", sourceImage);
            if (prompt == null) {
                prompt = sourceGeneration.getPrompt();
            }
        }
        model.addAttribute("prompt", prompt);
        populateGenerationParamsModel(model, defaultModel, Map.of());
        return "app/generate";
    }

    /** Pagina di /generations/new compilata da una {@link GenerationConfig}: nessun ripiego sul modello video della sorgente. */
    private String reuseForm(GenerationConfig config, Model model) {
        GenerationFormType formType = modelCatalog.formTypeOf(config.model()).orElseThrow();
        Map<String, String> fields = new LinkedHashMap<>(forms.formFields(formType, config.parameters()));
        if (config.seed() != null) {
            fields.put("seed", String.valueOf(config.seed()));
            model.addAttribute("seed", config.seed());
        }
        if (config.sourceGenerationId() != null) {
            generationService.findAnimatableSource(config.sourceGenerationId(), config.sourceImage()).ifPresent(source -> {
                model.addAttribute("sourceGeneration", source);
                model.addAttribute("sourceImage", config.sourceImage());
            });
        }
        model.addAttribute("prompt", config.prompt());
        // Il form ripristinerebbe lo stato salvato nel browser sopra questi valori: qui vince il link (vedi data-persist-fresh).
        model.addAttribute("persistFresh", true);
        populateGenerationParamsModel(model, config.model(), fields);
        return "app/generate";
    }

    @PostMapping
    public String create(@RequestParam String model,
                          @RequestParam(required = false) String version,
                          @RequestParam String prompt,
                          @RequestParam(required = false) Long sourceGenerationId,
                          @RequestParam(required = false) String sourceImage,
                          @RequestParam(required = false) MultipartFile sourceUpload,
                          @RequestParam(required = false) MultipartFile maskUpload,
                          @RequestParam Map<String, String> allParams,
                          Model uiModel, HttpServletResponse response) {
        // Solo per ri-renderizzare la form (anteprima della sorgente): la regola e la precedenza upload > sorgente sono in IGenerations#create.
        Generation sourceGeneration = animatableSource(sourceGenerationId, sourceImage);
        if (sourceGeneration != null) {
            uiModel.addAttribute("sourceGeneration", sourceGeneration);
            uiModel.addAttribute("sourceImage", sourceImage);
        }
        try {
            GenerationFormType formType = modelCatalog.formTypeOf(model)
                    .orElseThrow(() -> new ReplicateException(messages.get("generateForm.error.unknownModel", model)));
            String resolvedVersion = (version == null || version.isBlank())
                    ? modelCatalog.versionOf(model).orElse(null)
                    : version;
            Map<String, Object> parameters = forms.parameters(formType, allParams);
            // img2video / modifica: sorgente (upload o generazione "Anima"/"Modifica"), precedenza, validita' e salvataggio dell'upload
            // li decide il servizio (ignorata da un text-to-image); qui si passa solo cio' che ha inviato il form.
            UploadedFile upload = sourceUpload == null || sourceUpload.isEmpty() ? null : uploaded(sourceUpload);
            // Inpainting: la maschera disegnata nell'editor arriva come file PNG (campo maskUpload), stessa via della sorgente.
            UploadedFile mask = maskUpload == null || maskUpload.isEmpty() ? null : uploaded(maskUpload);
            Generation generation = generationService.create(new IGenerations.CreateCommand(model, resolvedVersion, prompt,
                    parameters, sourceGenerationId, sourceImage, upload, mask));
            uiModel.addAttribute("generation", generation);
            // Appena creata: mai terminale al primo giro (status()/refresh() la portera' li' col
            // polling), quindi conversationId qui non decide ancora nulla - lo si valorizza comunque
            // per coerenza col Model di status() sotto, stesso fragment condiviso.
            uiModel.addAttribute("conversationId", null);
            return "fragments/app/generation :: status";
        } catch (org.dual.hexa.core.kernel.remote.RemoteServiceException e) {
            // Validazioni applicative (modello sconosciuto, sorgente mancante, troppe in corso) non hanno una
            // causa: sono un rifiuto, non un errore di comunicazione, e restano solo nel form. Il resto
            // (chiamata a Replicate fallita, storage) e' registrato e notificato anche come toast.
            if (e.isReportable()) {
                htmx.addToastHeader(response, systemEvents.record("createGeneration", e));
            }
            return createFailed(e.getMessage(), version, prompt, model, allParams, uiModel);
        } catch (RuntimeException e) {
            // Errore inatteso (upload illeggibile, DB, serializzazione...): mai un 500 che htmx non renderizza.
            htmx.addToastHeader(response, systemEvents.record("createGeneration", e));
            return createFailed(ISystemEvents.sanitize(e), version, prompt, model, allParams, uiModel);
        }
    }

    private String createFailed(String error, String version, String prompt, String model,
                                 Map<String, String> allParams, Model uiModel) {
        uiModel.addAttribute("error", error);
        uiModel.addAttribute("version", version);
        uiModel.addAttribute("prompt", prompt);
        populateGenerationParamsModel(uiModel, model, allParams);
        // Il seed non e' fra i defaultFields dei form-type (vedi populateFormTypeFields): senza, la form ri-renderizzata
        // lo perderebbe e il salvataggio lato client lo scriverebbe vuoto.
        String seed = allParams.get("seed");
        if (seed != null && !seed.isBlank()) {
            uiModel.addAttribute("seed", seed);
        }
        return "fragments/app/generate-form :: form";
    }

    /** {@code MultipartFile} -> tipo di dominio dello storage (che non conosce il framework web). */
    private static UploadedFile uploaded(MultipartFile file) {
        return UploadedFiles.of(file);
    }

    private SourceImage resolveEnhanceImage(MultipartFile upload, Long sourceGenerationId, String sourceImage) {
        if (upload != null && !upload.isEmpty()) {
            return imageStorageService.inspectUpload(uploaded(upload));
        }
        Generation source = sourceGenerationId == null ? null : animatableSource(sourceGenerationId, sourceImage);
        return source == null ? null : imageStorageService.read(sourceImage);
    }

    /**
     * La generazione immagine completata da animare, o null se non esiste/non e' animabile o se
     * {@code image} non e' uno dei suoi file (parametro ignorato).
     */
    private Generation animatableSource(Long id, String image) {
        return generationService.findAnimatableSource(id, image).orElse(null);
    }

    /**
     * Campi che hanno una scala PROPRIA per modello (default e limiti diversi: guidance 2.5/3/30/60, passi 28/50/4, step 1 o 0.1): nello
     * swap fra modelli ({@link #params}) un valore del form-type precedente non si eredita, sarebbe fuori scala (o invalido per il {@code step}
     * del nuovo campo, e il browser bloccherebbe il submit) e il significato non e' lo stesso. Gli altri campi comuni (formato, qualita',
     * num_outputs...) si conservano. Non vale per "Usa configurazione" e per il re-render dopo un create rifiutato: li' il modello e' lo stesso.
     */
    private static final java.util.Set<String> MODEL_SCALED_FIELDS = java.util.Set.of("guidance", "guidance_scale", "num_inference_steps", "steps");

    /**
     * Ri-renderizza solo i campi del form-type del modello selezionato
     * (target #generation-params-fields, vedi fragments/app/generation-params.html),
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
    public String params(@RequestParam String model,
                          @RequestParam(required = false) Long sourceGenerationId,
                          @RequestParam(required = false) String sourceImage,
                          @RequestParam Map<String, String> allParams, Model uiModel) {
        GenerationFormType formType = modelCatalog.formTypeOf(model)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("generateForm.error.unknownModel", model)));
        // Sorgente "Modifica"/"Anima" gia' scelta nella pagina (hidden fuori da questo fragment): senza, il fragment ripresenterebbe
        // l'upload (obbligatorio per i modelli di modifica) pur avendo gia' l'immagine. Stessa rivalidazione di form()/create().
        Generation sourceGeneration = animatableSource(sourceGenerationId, sourceImage);
        if (sourceGeneration != null) {
            uiModel.addAttribute("sourceGeneration", sourceGeneration);
            uiModel.addAttribute("sourceImage", sourceImage);
        }
        Map<String, String> carried = new LinkedHashMap<>(allParams);
        carried.keySet().removeAll(MODEL_SCALED_FIELDS);
        populateFormTypeFields(uiModel, formType, carried);
        addTokenOptions(uiModel, formType);
        return GenerationFormFragments.fragmentOf(formType);
    }

    /**
     * Riscrive una bozza di prompt in un prompt Flux ben formato in
     * inglese (icona "AI enhance", fragments/app/button-gen.html :: aiEnhance,
     * fragments/app/generate-form.html :: promptField): solo fragment, mai
     * pagina intera (stesso principio di params() sopra), e SEMPRE 200
     * anche in caso di errore, come DeepChatApiController - htmx non
     * farebbe lo swap di una risposta 4xx/5xx di default, un errore
     * lanciato sparirebbe silenziosamente invece di essere mostrato
     * nell'alert del fragment. "enhanceError" e' un attributo di model
     * separato da "error" usato da create()/form sopra: quello segnala
     * l'esito di una vera POST /generations (spesa reale su Replicate),
     * questo solo di una riscrittura testo via LLM - non vanno confusi.
     */
    @PostMapping("/enhance-prompt")
    public String enhancePrompt(@RequestParam(required = false) String prompt,
                                 @RequestParam(required = false) String model,
                                 @RequestParam(required = false) MultipartFile sourceUpload,
                                 @RequestParam(required = false) MultipartFile maskUpload,
                                 @RequestParam(required = false) Long sourceGenerationId,
                                 @RequestParam(required = false) String sourceImage,
                                 @RequestParam(name = "prompt_strength", required = false) Double promptStrength,
                                 Model uiModel, HttpServletResponse response) {
        String draft = prompt == null ? "" : prompt.trim();
        // Flusso video: l'enhancer guarda l'immagine sorgente (upload > "Anima", stessa precedenza
        // di create) e propone il movimento; con l'immagine anche la bozza vuota e' ammessa.
        boolean video = model != null && modelCatalog.contains(model, GenerationKind.VIDEO);
        // Modifica: l'enhancer guarda la stessa sorgente ma serve una bozza (cosa cambiare).
        // Inpainting (maschera): il prompt descrive SOLO cosa renderizzare nella zona dipinta (guida dedicata, non quella di Kontext ne' quella
        // generica text-to-image che chiederebbe scena, luce e inquadratura); l'enhancer guarda la sorgente per adattare luce/orientamento/stile.
        // Dove la maschera e' opzionale (flux-lora-finetune) e' inpainting solo se una maschera c'e' davvero (la manda hx-include); senza
        // resta img2img (con sorgente) o text-to-image.
        boolean maskPresent = maskUpload != null && !maskUpload.isEmpty();
        boolean inpaint = model != null && modelCatalog.formTypeOf(model)
                .map(t -> t.takesMask() && (t.maskRequired() || maskPresent)).orElse(false);
        // Istruzione di modifica (kontext): sorgente obbligatoria e nessuna maschera.
        boolean edit = model != null && !inpaint && modelCatalog.formTypeOf(model).map(GenerationFormType::isInstructionEdit).orElse(false);
        // img2img (flux-dev-lora con upload): il modello descrive il risultato finale e quanto conta l'immagine lo dice prompt_strength.
        // Senza upload e' un normale text-to-image (l'immagine e' opzionale su questo modello).
        boolean takesSource = model != null && !video && !edit && !inpaint
                && modelCatalog.formTypeOf(model).map(GenerationFormType::takesSourceImage).orElse(false);
        try {
            SourceImage image = (video || edit || inpaint || takesSource) ? resolveEnhanceImage(sourceUpload, sourceGenerationId, sourceImage) : null;
            if (takesSource && image != null) {
                uiModel.addAttribute("prompt", draft.isEmpty() ? prompt : promptEnhancementService.enhanceImg2Img(draft, image, promptStrength));
            } else if (inpaint) {
                uiModel.addAttribute("prompt", draft.isEmpty() ? prompt : promptEnhancementService.enhanceInpaint(draft, image));
            } else if (edit) {
                uiModel.addAttribute("prompt", draft.isEmpty() ? prompt : promptEnhancementService.enhanceEdit(draft, image));
            } else if (draft.isEmpty() && image == null) {
                uiModel.addAttribute("prompt", prompt);
            } else {
                uiModel.addAttribute("prompt", video ? promptEnhancementService.enhanceVideo(draft, image)
                        : promptEnhancementService.enhance(draft));
            }
            uiModel.addAttribute("enhanceError", null);
        } catch (PromptEnhancementRefusedException e) {
            // Il rifiuto del modello NON va nella textarea: si conserva la bozza e si mostra l'errore.
            uiModel.addAttribute("prompt", prompt);
            uiModel.addAttribute("enhanceError", messages.get("generateForm.error.enhanceRefused"));
        } catch (Exception e) {
            uiModel.addAttribute("prompt", prompt);
            if (e instanceof org.dual.hexa.core.kernel.remote.RemoteServiceException rejected && !rejected.isReportable()) {
                // Rifiuto atteso (es. upload sorgente di tipo non valido): solo il messaggio, niente registro/toast.
                uiModel.addAttribute("enhanceError", rejected.getMessage());
            } else {
                htmx.addToastHeader(response, systemEvents.record("enhancePrompt", e));
                uiModel.addAttribute("enhanceError", messages.get("generateForm.error.enhanceFailed", ISystemEvents.sanitize(e)));
            }
        }
        return "fragments/app/generate-form :: promptField(prompt=${prompt}, enhanceError=${enhanceError})";
    }

    /**
     * Attributi richiesti dal guscio fragments/app/generation-params.html
     * (combobox modello + contenitore dei campi del form-type corrente):
     * usato sia dal primo caricamento di /generations/new sia dal path
     * di errore di create(), altrimenti il fragment ri-renderizzato sul
     * path di errore perderebbe la lista modelli (select vuota) oltre ai
     * valori inseriti dall'utente.
     */
    private void populateGenerationParamsModel(Model model, String modelValue, Map<String, String> allParams) {
        // Immagini e video non si mescolano nel select: il tipo di pagina lo decide il modello corrente
        // (video -> solo video, altrimenti tutte le immagini, anche quelle a sorgente obbligatoria come kontext
        // e flux-fill-*). Si passa da un'altra pagina (header), non dal select.
        GenerationFormType current = modelCatalog.formTypeOf(modelValue).orElse(null);
        GenerationKind kind = current == null ? GenerationKind.IMAGE : current.kind();
        List<ReplicateModel> pageModels = modelCatalog.formModels(kind);
        model.addAttribute("models", pageModels);
        model.addAttribute("videoPage", kind == GenerationKind.VIDEO);
        model.addAttribute("model", modelValue);
        // Target del "Reimposta ai default" della form: il primo modello del tipo di pagina che funziona senza sorgente
        // (mai uno a sorgente obbligatoria), non quello corrente.
        model.addAttribute("defaultModel", modelCatalog.models(kind).stream().findFirst().or(() -> pageModels.stream().findFirst())
                .map(ReplicateModel::getIdentifier).orElse(modelValue));
        GenerationFormType shown = current != null ? current
                : pageModels.stream().findFirst().map(ReplicateModel::getFormType).orElse(null);
        model.addAttribute("formType", shown == null ? null : shown.name());
        if (shown != null) {
            populateFormTypeFields(model, shown, allParams);
            addTokenOptions(model, shown);
        }
    }

    /** Token e LoRA anagrafati per le select del form-type che li usa (flux-dev-lora): solo dove serve, mai a ogni richiesta. */
    private void addTokenOptions(Model model, GenerationFormType formType) {
        forms.extraFormOptions(formType).forEach(model::addAttribute);
    }

    /** Valori dei campi del form-type: quelli sottomessi (se presenti) sopra i default di quel form-type. */
    private void populateFormTypeFields(Model model, GenerationFormType formType, Map<String, String> allParams) {
        Map<String, Object> defaults = forms.defaultFields(formType);
        Map<String, Object> fields = new LinkedHashMap<>(defaults);
        defaults.keySet().forEach(key -> {
            String submitted = allParams.get(key);
            if (submitted != null && !submitted.isBlank()) {
                fields.put(key, submitted);
            }
        });
        fields.forEach(model::addAttribute);
        // I default da soli (i valori sopra sono quelli correnti): i campi numerici li portano in data-default per il reset al default.
        model.addAttribute(IGenerationForms.FIELD_DEFAULTS, defaults);
    }

    /**
     * Cancellazione di una generazione dall'azione DELETE della chat
     * (elimina SOLO l'id nel path). Nessun redirect: la pagina chiamante
     * resta valida.
     */
    @PostMapping("/{id}/delete")
    @ResponseBody
    public void deleteOne(@PathVariable Long id) {
        generationService.delete(id);
    }

    /**
     * conversationId (opzionale): decide il link "indietro" e il target
     * del redirect dopo una cancellazione da questa pagina (vedi
     * delete/deleteImage sotto) - presente quando si arriva dalla galleria
     * contestuale di una conversazione /deep-chat (vedi
     * fragments/app/gallery-card.html), assente dalla galleria globale
     * (default a /gallery).
     */
    @GetMapping("/{id}")
    public String status(@PathVariable Long id,
                          @RequestParam(required = false) Long conversationId,
                          @RequestParam(required = false) Boolean cancelDisabled,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          HttpServletRequest request, HttpServletResponse response,
                          Model model) {
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        Generation generation;
        try {
            generation = generationService.refresh(id);
        } catch (ReplicateException e) {
            // La generazione e' stata cancellata (da /generations, dal proprio dettaglio o per
            // ultima-immagine-a-cascata, vedi delete/deleteImage sotto) mentre QUESTA tab la
            // stava ancora pollando ogni 2s (possibile solo da quando anche generazioni non
            // terminali sono cancellabili, vedi CLAUDE.md - refresh()/get() la trovano "non
            // trovata"): stesso target "indietro" di una cancellazione riuscita, non un errore
            // generico che il polling ripeterebbe identico ogni 2s all'infinito (htmx non si
            // ferma da solo su una risposta d'errore). Ma ReplicateException la lancia anche
            // IImageStorageService/ReplicateClient per errori VERI (download fallito, disco
            // pieno...): se la riga esiste ancora non e' questo il caso, si ripropaga e basta,
            // altrimenti un errore di storage sparirebbe silenziosamente in un redirect.
            if (generationService.exists(id)) {
                // Errore VERO su una riga esistente: registrato (la serie evita righe/toast a ogni poll) e la
                // pagina resta viva con lo stato attuale, invece di un 500 che htmx non renderizza e che il
                // polling ripeterebbe identico ogni 2s. Il recupero (GenerationRecoveryService) la chiude.
                systemEvents.record(CoreEventSource.INTERNAL, "refreshGeneration", e, AppEventSubjects.of(id, null));
                generation = generationService.find(id).orElseThrow(() -> e);
                return renderStatus(generation, conversationId, cancelDisabled, isHtmxRequest, model);
            }
            if (isHtmxRequest) {
                response.setHeader("HX-Redirect", backTarget(conversationId, request));
                return null;
            }
            return "redirect:" + backPath(conversationId);
        }
        return renderStatus(generation, conversationId, cancelDisabled, isHtmxRequest, model);
    }

    private String renderStatus(Generation generation, Long conversationId,
                                 Boolean cancelDisabled, boolean isHtmxRequest, Model model) {
        if (generation.isImported()) {
            // Un'immagine importata non e' una "Generazione": ha il suo dettaglio (ImportController#detail).
            return "redirect:/import/" + generation.getId();
        }
        model.addAttribute("generation", generation);
        model.addAttribute("conversationId", conversationId);
        model.addAttribute("cancelDisabled", cancelDisabled);

        return isHtmxRequest ? "fragments/app/generation :: status" : "app/generation-status";
    }

    /**
     * Interrompe una generazione in corso (bottone del placeholder, vedi
     * fragments/app/generation-placeholder.html). Due chiamanti: htmx
     * (/generations/{id}, HX-Request presente) riceve il fragment di stato
     * aggiornato - se l'interruzione non e' riuscita, o e' stata richiesta ma
     * la prediction non e' ancora terminale, con cancelDisabled=true (il
     * bottone si disabilita e il polling prosegue, propagando il flag
     * nell'hx-get); il fetch JS del placeholder in /deep-chat (nessun
     * HX-Request) riceve solo 204 (richiesta accolta) o 409 col messaggio.
     */
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id,
                          @RequestParam(required = false) Long conversationId,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          HttpServletResponse response, Model model) {
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        Generation generation = null;
        boolean cancelFailed = false;
        String errorText = null;
        try {
            generation = generationService.get(id);
            generation = generationService.cancel(id);
        } catch (ReplicateException e) {
            cancelFailed = true;
            errorText = e.getMessage();
            // La generazione puo' essere diventata terminale nel frattempo (es. cancel rifiutato
            // perche' gia' finita): rileggerla, il fragment mostra l'esito vero.
            Generation fallback = generation;
            generation = generationService.find(id).orElse(fallback);
            if (generation == null) {
                throw e; // id inesistente: nulla da mostrare (404-like), come prima
            }
            // Un rifiuto perche' la prediction era gia' terminale non e' un errore da segnalare; se invece la
            // generazione e' ancora in corso il cancel e' davvero fallito: registrato e notificato.
            if (!generation.isTerminal()) {
                ISystemEvents.Recorded recorded = systemEvents.record("cancelGeneration", e, AppEventSubjects.of(id, conversationId));
                if (isHtmxRequest) {
                    htmx.addToastHeader(response, recorded);
                }
            }
        }
        if (!isHtmxRequest) {
            if (cancelFailed) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("text/plain;charset=UTF-8");
                try {
                    response.getWriter().write(errorText);
                } catch (java.io.IOException ignored) {
                    // il solo status 409 basta al client
                }
            } else {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            }
            return null;
        }
        model.addAttribute("generation", generation);
        model.addAttribute("conversationId", conversationId);
        model.addAttribute("cancelDisabled", true);
        return "fragments/app/generation :: status";
    }

    /**
     * Cancellazione dal dettaglio (bottone in fragments/app/generation.html
     * :: status, ramo SUCCEEDED/FAILED): a differenza di deleteOne sopra,
     * la pagina corrente smette di esistere dopo la cancellazione, serve
     * un redirect (HX-Redirect, non HX-Refresh: la pagina corrente non
     * c'e' piu', va lasciata del tutto). "Indietro" segue la stessa
     * provenienza del link mostrato in pagina (vedi backTarget sotto).
     */
    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id,
                          @RequestParam(required = false) Long conversationId,
                          HttpServletRequest request, HttpServletResponse response) {
        generationService.delete(id);
        response.setHeader("HX-Redirect", backTarget(conversationId, request));
        return null;
    }

    /**
     * Cancellazione per-immagine dal dettaglio: due esiti possibili
     * (vedi GenerationService#deleteImage). Se cascaded, l'intera
     * generazione e' sparita, stesso redirect di delete(...) sopra. Se no,
     * il dettaglio resta valido: si ri-renderizza solo la griglia
     * immagini aggiornata (hx-target/hx-swap sul bottone stesso, vedi
     * fragments/app/generation-images.html), niente redirect.
     */
    @DeleteMapping("/{id}/images/{filename}")
    public String deleteImage(@PathVariable Long id, @PathVariable String filename,
                               @RequestParam(required = false) Long conversationId,
                                    HttpServletRequest request, HttpServletResponse response, Model model) {
        boolean cascaded;
        try {
            cascaded = generationService.deleteImage(id, filename);
        } catch (org.dual.hexa.core.storage.domain.StorageException e) {
            // Lo storage non ha cancellato il file: il DB e' rimasto invariato (coerente), la griglia non cambia.
            // Registrato come STORAGE (non come 500 generico) e notificato con il toast.
            htmx.addToastHeader(response, systemEvents.record("deleteFile", e, AppEventSubjects.of(id, conversationId)));
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, null, e);
        }
        if (cascaded) {
            response.setHeader("HX-Redirect", backTarget(conversationId, request));
            return null;
        }
        Generation generation = generationService.get(id);
        model.addAttribute("generation", generation);
        model.addAttribute("conversationId", conversationId);
        return "fragments/app/generation-images :: grid(generation=${generation}, conversationId=${conversationId})";
    }

    /**
     * Inverte la star di un file (vedi GenerationService#toggleFavourite) e
     * ritorna il solo bottone aggiornato (hx-swap="outerHTML" sul bottone
     * stesso, fragments/app/button-gen.html :: starOverlay). variant sceglie la
     * posizione dell'icona (card di galleria vs griglia del dettaglio).
     * refresh=true (tab "Preferiti"): la card deve sparire togliendo la
     * star, quindi si emette "gallery-update" (HX-Trigger), lo stesso
     * evento che il wrapper di fragments/app/gallery.html :: content ascolta
     * gia' per gli aggiornamenti SSE.
     */
    @PostMapping("/{id}/favourite")
    public String toggleFavourite(@PathVariable Long id, @RequestParam String filename,
                                   @RequestParam(defaultValue = "card") String variant,
                                   @RequestParam(defaultValue = "false") boolean refresh,
                                   HttpServletResponse response, Model model) {
        boolean favourite = generationService.toggleFavourite(id, filename);
        if (refresh) {
            response.setHeader("HX-Trigger", "gallery-update");
        }
        model.addAttribute("generationId", id);
        model.addAttribute("filename", filename);
        model.addAttribute("favourite", favourite);
        model.addAttribute("refresh", refresh);
        model.addAttribute("variant", variant);
        return "fragments/app/button-gen :: starOverlay(generationId=${generationId}, filename=${filename}, favourite=${favourite}, refresh=${refresh}, variant=${variant})";
    }

    /**
     * Tag utente di una generazione (filename assente) o di UN suo file: aggiunge/toglie e risponde con l'editor aggiornato
     * (fragments/app/tag-editor.html), che si sostituisce da solo. Un file non della generazione o troppi tag e' un rifiuto
     * ({@code ReplicateException} REJECTED: il resolver risponde 422 con il toast).
     */
    @PostMapping("/{id}/tags/add")
    public String addTag(@PathVariable Long id, @RequestParam String tag, @RequestParam(required = false) String filename, Model model) {
        generationService.addTag(id, blankToNull(filename), tag);
        return tagEditor(id, blankToNull(filename), model);
    }

    @PostMapping("/{id}/tags/remove")
    public String removeTag(@PathVariable Long id, @RequestParam String tag, @RequestParam(required = false) String filename, Model model) {
        generationService.removeTag(id, blankToNull(filename), tag);
        return tagEditor(id, blankToNull(filename), model);
    }

    private String tagEditor(Long id, String filename, Model model) {
        model.addAttribute("generation", generationService.get(id));
        model.addAttribute("filename", filename);
        model.addAttribute("suggestions", filename == null);
        return "fragments/app/tag-editor :: generationEditor(generation=${generation}, filename=${filename}, suggestions=${suggestions})";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Percorso "indietro" dopo la cancellazione di una generazione (o
     * della sua ultima immagine, o la scomparsa per race mentre la si
     * pollava, vedi status(...) sopra) dal proprio dettaglio:
     * conversationId (deep-chat) se si arriva dalla conversazione,
     * altrimenti il default /gallery. SENZA prefisso di contextPath:
     * usato sia come header HX-Redirect (via backTarget sotto, che il
     * prefisso lo aggiunge) sia come nome di vista "redirect:..." (che
     * il prefisso lo aggiunge gia' da solo, vedi CLAUDE.md - prependerlo
     * qui lo duplicherebbe in quel secondo caso).
     */
    private String backPath(Long conversationId) {
        if (conversationId != null) {
            return "/deep-chat/" + conversationId;
        }
        return "/gallery";
    }

    /** Come backPath(...) sopra, ma per l'header di risposta HX-Redirect: li' il prefisso di un eventuale reverse proxy va aggiunto a mano, vedi CLAUDE.md. */
    private String backTarget(Long conversationId, HttpServletRequest request) {
        return request.getContextPath() + backPath(conversationId);
    }
}
