package org.dual.replicate.controller;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.dual.replicate.service.ChatConversationService;
import org.dual.replicate.service.DeepChatService;
import org.dual.replicate.service.GenerationParameters;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final ChatConversationRepository chatConversationRepository;
    private final ChatConversationService chatConversationService;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final Messages messages;

    public DeepChatController(ReplicateModelCatalog modelCatalog,
                               ChatConversationRepository chatConversationRepository,
                               ChatConversationService chatConversationService,
                               ChatMessageRepository chatMessageRepository,
                               ObjectMapper objectMapper,
                               Messages messages) {
        this.modelCatalog = modelCatalog;
        this.chatConversationRepository = chatConversationRepository;
        this.chatConversationService = chatConversationService;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    /** Nessuna pagina "senza conversazione": risolve sempre quella piu' di recente attiva (o ne crea una nuova al primo avvio) e ci naviga. */
    @GetMapping("/deep-chat")
    public String defaultConversation() {
        return "redirect:/deep-chat/" + chatConversationService.resolveDefault().getId();
    }

    @GetMapping("/deep-chat/{id}")
    public String page(@PathVariable Long id, Model model) {
        chatConversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("deepchat.error.conversationNotFound")));

        // Serializzate qui (non nel template) come stringhe JSON gia'
        // pronte: il template le inlinea via Thymeleaf JS-inlining
        // (th:inline="javascript"), che sa escapare una String Java per
        // un contesto JS in modo sicuro senza dover capire come
        // Thymeleaf serializzerebbe i record (owner()/name() non sono
        // getter "getOwner()" in stile JavaBean).
        model.addAttribute("personalModelsJson", objectMapper.writeValueAsString(modelCatalog.personalModels()));
        model.addAttribute("catalogModelsJson", objectMapper.writeValueAsString(modelCatalog.models()));
        model.addAttribute("chatHistoryJson", objectMapper.writeValueAsString(loadHistory(id)));
        model.addAttribute("conversations", chatConversationRepository.findAllByOrderByUpdatedAtDesc());
        model.addAttribute("activeConversationId", id);
        model.addAttribute("contextualGenerations", chatMessageRepository.findSucceededGenerationsByConversationId(id));
        model.addAttribute("model", defaultModel());
        model.addAttribute("width", GenerationParameters.DEFAULT_WIDTH);
        model.addAttribute("height", GenerationParameters.DEFAULT_HEIGHT);
        model.addAttribute("outputFormat", GenerationParameters.DEFAULT_OUTPUT_FORMAT);
        model.addAttribute("numInferenceSteps", GenerationParameters.DEFAULT_NUM_INFERENCE_STEPS);
        model.addAttribute("guidanceScale", GenerationParameters.DEFAULT_GUIDANCE_SCALE);
        model.addAttribute("seed", null);
        model.addAttribute("loraScale", GenerationParameters.DEFAULT_LORA_SCALE);
        model.addAttribute("fluxModel", GenerationParameters.DEFAULT_FLUX_MODEL);
        model.addAttribute("numOutputs", GenerationParameters.DEFAULT_NUM_OUTPUTS);
        return "deep-chat";
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
        model.addAttribute("contextualGenerations", chatMessageRepository.findSucceededGenerationsByConversationId(id));
        model.addAttribute("contextualGalleryEmptyMessage", messages.get("deepChat.accordion.gallery.empty"));
        return "fragments/gallery :: gridOrEmpty(generations=${contextualGenerations}, emptyMessage=${contextualGalleryEmptyMessage})";
    }

    @PostMapping("/deep-chat/new")
    public String create(@RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          HttpServletRequest request,
                          HttpServletResponse response) {
        ChatConversation conversation = chatConversationService.create();
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        if (isHtmxRequest) {
            response.setHeader("HX-Redirect", request.getContextPath() + "/deep-chat/" + conversation.getId());
            return null;
        }
        return "redirect:/deep-chat/" + conversation.getId();
    }

    /** Non tocca mai la chat visibile (non rinomina quella attualmente aperta in modo distruttivo): sempre solo la sidebar da aggiornare. */
    @PostMapping("/deep-chat/{id}/rename")
    public String rename(@PathVariable Long id,
                          @RequestParam String title,
                          @RequestParam Long activeConversationId,
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          Model model) {
        chatConversationService.rename(id, title);
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        if (!isHtmxRequest) {
            return "redirect:/deep-chat/" + activeConversationId;
        }
        model.addAttribute("conversations", chatConversationRepository.findAllByOrderByUpdatedAtDesc());
        model.addAttribute("activeConversationId", activeConversationId);
        return "fragments/conversation-list :: items(conversations=${conversations}, activeConversationId=${activeConversationId})";
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
                          @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                          HttpServletRequest request,
                          HttpServletResponse response,
                          Model model) {
        chatConversationService.delete(id);
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        if (id.equals(activeConversationId)) {
            if (isHtmxRequest) {
                response.setHeader("HX-Redirect", request.getContextPath() + "/deep-chat");
                return null;
            }
            return "redirect:/deep-chat";
        }
        model.addAttribute("conversations", chatConversationRepository.findAllByOrderByUpdatedAtDesc());
        model.addAttribute("activeConversationId", activeConversationId);
        return "fragments/conversation-list :: items(conversations=${conversations}, activeConversationId=${activeConversationId})";
    }

    /** Primo modello personale, altrimenti primo di catalogo, altrimenti nessuno: sostituisce la vecchia scelta lato client in deep-chat.html. */
    private String defaultModel() {
        if (!modelCatalog.personalModels().isEmpty()) {
            return modelCatalog.personalModels().get(0).id();
        }
        if (!modelCatalog.models().isEmpty()) {
            return modelCatalog.models().get(0).id();
        }
        return "";
    }

    /**
     * Cronologia persistita di una conversazione (ChatMessageRepository)
     * nello stesso formato "role"/"text"/"files" che deep-chat usa per
     * le risposte live (vedi DeepChatApiController): cosi' un turno con
     * immagine ripristinato dopo un reload appare identico a quando e'
     * stato generato, non solo come testo.
     */
    private List<HistoryMessage> loadHistory(Long conversationId) {
        return chatMessageRepository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(this::toHistoryMessage)
                .toList();
    }

    private HistoryMessage toHistoryMessage(ChatMessage message) {
        String role = message.getRole() == ChatMessageRole.USER ? "user" : "ai";
        List<DeepChatService.FileRef> files = DeepChatService.toFiles(message.getGeneration());
        return new HistoryMessage(role, message.getContent(), files);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record HistoryMessage(String role, String text, List<DeepChatService.FileRef> files) {
    }
}
