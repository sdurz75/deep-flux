package org.dual.replicate.controller;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.dual.replicate.repository.ChatMessageRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pagina che ospita il Web Component &lt;deep-chat&gt; (vedi
 * DeepChatApiController per l'endpoint JSON che lo alimenta).
 */
@Controller
public class DeepChatController {

    private final ReplicateModelCatalog modelCatalog;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public DeepChatController(ReplicateModelCatalog modelCatalog,
                               ChatMessageRepository chatMessageRepository,
                               ObjectMapper objectMapper) {
        this.modelCatalog = modelCatalog;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/deep-chat")
    public String page(Model model) {
        // Serializzate qui (non nel template) come stringhe JSON gia'
        // pronte: il template le inlinea via Thymeleaf JS-inlining
        // (th:inline="javascript"), che sa escapare una String Java per
        // un contesto JS in modo sicuro senza dover capire come
        // Thymeleaf serializzerebbe i record (owner()/name() non sono
        // getter "getOwner()" in stile JavaBean).
        model.addAttribute("personalModelsJson", objectMapper.writeValueAsString(modelCatalog.personalModels()));
        model.addAttribute("catalogModelsJson", objectMapper.writeValueAsString(modelCatalog.models()));
        model.addAttribute("chatHistoryJson", objectMapper.writeValueAsString(loadHistory()));
        return "deep-chat";
    }

    /**
     * Cronologia persistita (ChatMessageRepository) nello stesso formato
     * "role"/"text"/"files" che deep-chat usa per le risposte live (vedi
     * DeepChatApiController): cosi' un turno con immagine ripristinato
     * dopo un reload appare identico a quando e' stato generato, non
     * solo come testo.
     */
    private List<HistoryMessage> loadHistory() {
        return chatMessageRepository.findAllByOrderByIdAsc().stream()
                .map(this::toHistoryMessage)
                .toList();
    }

    private HistoryMessage toHistoryMessage(ChatMessage message) {
        String role = message.getRole() == ChatMessageRole.USER ? "user" : "ai";
        List<DeepChatApiController.FileRef> files = DeepChatApiController.toFiles(message.getGeneration());
        return new HistoryMessage(role, message.getContent(), files);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record HistoryMessage(String role, String text, List<DeepChatApiController.FileRef> files) {
    }
}
