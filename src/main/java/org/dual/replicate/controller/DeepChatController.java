package org.dual.replicate.controller;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationKind;
import org.dual.replicate.domain.ReplicateModel;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.dual.replicate.service.ChatConversationService;
import org.dual.replicate.service.DeepChatService;
import org.dual.replicate.service.GenerationParameterHandler;
import org.dual.replicate.service.GenerationService;
import org.dual.replicate.service.GenerationParameterHandlers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Pagina che ospita il Web Component &lt;deep-chat&gt; (vedi
 * DeepChatApiController per l'endpoint JSON che lo alimenta) e la
 * sidebar delle conversazioni. /deep-chat supporta piu' conversazioni
 * (vedi CLAUDE.md, punto 3 dello Scopo): questo controller possiede
 * tutte le route sotto /deep-chat/* che restituiscono HTML (pagina
 * intera o fragment htmx per la sidebar/galleria contestuale), mentre
 * DeepChatApiController resta la sola API JSON per il widget.
 */
@Controller
public class DeepChatController {

    private final ReplicateModelCatalog modelCatalog;
    private final GenerationParameterHandlers parameterHandlers;
    private final ChatConversationRepository chatConversationRepository;
    private final ChatConversationService chatConversationService;
    private final ChatMessageRepository chatMessageRepository;
    private final GenerationService generationService;
    private final ObjectMapper objectMapper;
    private final Messages messages;
    private final org.dual.replicate.app.TokenInputResolver apiTokens;
    private final org.dual.replicate.service.LoraPresetService loraPresets;

    public DeepChatController(ReplicateModelCatalog modelCatalog,
                               GenerationParameterHandlers parameterHandlers,
                               ChatConversationRepository chatConversationRepository,
                               ChatConversationService chatConversationService,
                               ChatMessageRepository chatMessageRepository,
                               GenerationService generationService,
                               ObjectMapper objectMapper,
                               Messages messages,
                               org.dual.replicate.app.TokenInputResolver apiTokens,
                               org.dual.replicate.service.LoraPresetService loraPresets) {
        this.apiTokens = apiTokens;
        this.loraPresets = loraPresets;
        this.modelCatalog = modelCatalog;
        this.parameterHandlers = parameterHandlers;
        this.chatConversationRepository = chatConversationRepository;
        this.chatConversationService = chatConversationService;
        this.chatMessageRepository = chatMessageRepository;
        this.generationService = generationService;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    /** Nessuna pagina "senza conversazione": risolve sempre quella piu' di recente attiva (o ne crea una nuova al primo avvio) e ci naviga. */
    @GetMapping("/deep-chat")
    public String defaultConversation(@RequestParam(required = false) Long seed) {
        Long id = chatConversationService.resolveDefault().getId();
        return "redirect:/deep-chat/" + id + (seed != null ? "?seed=" + seed : "");
    }

    @GetMapping("/deep-chat/{id}")
    public String page(@PathVariable Long id, @RequestParam(required = false) Long seed, Model model) {
        chatConversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("deepchat.error.conversationNotFound")));

        // chatHistoryJson e' serializzata qui (non nel template) come
        // stringa JSON gia' pronta: il template la inlinea via Thymeleaf
        // JS-inlining (th:inline="javascript"), che sa escapare una
        // String Java per un contesto JS in modo sicuro. I modelli
        // (models sotto) sono invece una vera entity JPA con getter
        // JavaBean: la select del combobox li legge con un semplice
        // th:each, nessun bridge JSON/data-* necessario.
        model.addAttribute("chatHistoryJson", objectMapper.writeValueAsString(loadHistory(id)));
        // Placeholder da ripristinare: generazioni di QUESTA conversazione ancora in corso (vedi deep-chat.html).
        model.addAttribute("pendingGenerationIds", generationService.inProgressForConversation(id).stream()
                .map(org.dual.replicate.domain.Generation::getId).toList());
        model.addAttribute("conversations", chatConversationRepository.findAllByOrderByUpdatedAtDesc());
        model.addAttribute("activeConversationId", id);
        model.addAttribute("contextualItems", generationService.succeededForConversation(id).stream()
                .map(org.dual.replicate.service.GalleryItem::first).toList());

        // Solo modelli immagine: il tool di chat genera immagini (i video passano da /generations/new).
        model.addAttribute("models", modelCatalog.models(GenerationKind.IMAGE));
        Optional<ReplicateModel> defaultModel = modelCatalog.defaultModel();
        model.addAttribute("model", defaultModel.map(ReplicateModel::getIdentifier).orElse(""));
        GenerationParameterHandler handler = defaultModel.map(m -> parameterHandlers.get(m.getFormType())).orElse(null);
        model.addAttribute("formType", handler == null ? null : handler.formType().name());
        if (handler != null) {
            handler.defaultFields().forEach(model::addAttribute);
            if (handler.formType() == org.dual.replicate.domain.GenerationFormType.FLUX_DEV_LORA) {
                apiTokens.formOptions().forEach(model::addAttribute);
                loraPresets.formOptions().forEach(model::addAttribute);
            }
        }
        // Push del seed dal dettaglio di una generazione (vedi fragments/app/generation.html :: status,
        // ramo SUCCEEDED), stesso motivo del GenerationController#form: seed non e' in
        // defaultFields(), va impostato a parte.
        if (seed != null) {
            model.addAttribute("seed", seed);
        }
        return "app/deep-chat";
    }

    /**
     * Galleria contestuale della conversazione (accordion in
     * deep-chat.html), solo htmx: nessuna vista a pagina intera, non
     * avrebbe senso come pagina a se stante. Richiamata ad ogni evento
     * "new-message" del Web Component (vedi deep-chat.html), cosi' una
     * nuova immagine generata in chat appare qui senza dover ricaricare
     * l'intera pagina.
     */
    @GetMapping("/deep-chat/{id}/gallery")
    public String gallery(@PathVariable Long id, Model model) {
        model.addAttribute("contextualItems", generationService.succeededForConversation(id).stream()
                .map(org.dual.replicate.service.GalleryItem::first).toList());
        model.addAttribute("contextualGalleryEmptyMessage", messages.get("deepChat.accordion.gallery.empty"));
        model.addAttribute("conversationId", id);
        return "fragments/app/gallery :: gridOrEmpty(items=${contextualItems}, emptyMessage=${contextualGalleryEmptyMessage}, conversationId=${conversationId})";
    }

    @PostMapping("/deep-chat/new")
    public String create(HttpServletRequest request, HttpServletResponse response) {
        ChatConversation conversation = chatConversationService.create();
        response.setHeader("HX-Redirect", request.getContextPath() + "/deep-chat/" + conversation.getId());
        return null;
    }

    /** Non tocca mai la chat visibile (non rinomina quella attualmente aperta in modo distruttivo): sempre solo la sidebar da aggiornare. */
    @PostMapping("/deep-chat/{id}/rename")
    public String rename(@PathVariable Long id,
                          @RequestParam String title,
                          @RequestParam Long activeConversationId,
                          Model model) {
        chatConversationService.rename(id, title);
        model.addAttribute("conversations", chatConversationRepository.findAllByOrderByUpdatedAtDesc());
        model.addAttribute("activeConversationId", activeConversationId);
        return "fragments/app/conversation-list :: items(conversations=${conversations}, activeConversationId=${activeConversationId})";
    }

    /**
     * Se si cancella la conversazione attualmente aperta, il browser
     * deve navigare altrove: verso /deep-chat "nudo", che risolve da
     * solo la conversazione piu' di recente attiva rimasta (o ne crea
     * una se era l'ultima) — stessa logica di defaultConversation()
     * sopra, non duplicata qui. Altrimenti (si cancella una
     * conversazione diversa da quella aperta) basta aggiornare la sola
     * lista in sidebar, la chat visibile non cambia.
     */
    @PostMapping("/deep-chat/{id}/delete")
    public String delete(@PathVariable Long id,
                          @RequestParam Long activeConversationId,
                          HttpServletRequest request,
                          HttpServletResponse response,
                          Model model) {
        chatConversationService.delete(id);
        if (id.equals(activeConversationId)) {
            response.setHeader("HX-Redirect", request.getContextPath() + "/deep-chat");
            return null;
        }
        model.addAttribute("conversations", chatConversationRepository.findAllByOrderByUpdatedAtDesc());
        model.addAttribute("activeConversationId", activeConversationId);
        return "fragments/app/conversation-list :: items(conversations=${conversations}, activeConversationId=${activeConversationId})";
    }

    /**
     * Cronologia persistita di una conversazione (ChatMessageRepository)
     * nello stesso formato "role"/"text"/"files" che deep-chat usa per
     * le risposte live (vedi DeepChatApiController): cosi' un turno con
     * immagine ripristinato dopo un reload appare identico a quando e'
     * stato generato, non solo come testo.
     */
    private List<HistoryMessage> loadHistory(Long conversationId) {
        List<ChatMessage> messages = chatMessageRepository.findByConversationIdOrderByIdAsc(conversationId);
        // Le generazioni dei turni in un colpo solo (la chat ne conosce solo l'id): una cancellata e' semplicemente assente.
        java.util.Map<Long, Generation> generations = generationService.findAllById(messages.stream()
                        .map(ChatMessage::getGenerationId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Generation::getId, g -> g));
        return messages.stream().map(message -> toHistoryMessage(message, generations.get(message.getGenerationId()))).toList();
    }

    private HistoryMessage toHistoryMessage(ChatMessage message, Generation generation) {
        String role = message.getRole() == ChatMessageRole.USER ? "user" : "ai";
        List<DeepChatService.FileRef> files = DeepChatService.toFiles(generation);
        return new HistoryMessage(role, message.getContent(), files, message.isError() ? Boolean.TRUE : null,
                generation != null ? generation.getId() : null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record HistoryMessage(String role, String text, List<DeepChatService.FileRef> files, Boolean error,
                                  Long generationId) {
    }
}
