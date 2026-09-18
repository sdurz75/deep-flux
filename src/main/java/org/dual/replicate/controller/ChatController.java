package org.dual.replicate.controller;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.service.ChatException;
import org.dual.replicate.service.ChatService;
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

/**
 * Chat con l'assistente OpenRouter per rifinire un prompt di generazione
 * immagine. Nessun tool-calling: l'utente sceglie esplicitamente quando
 * usare una risposta come prompt su GenerationController (vedi il link
 * "Usa come prompt" nel fragment dei messaggi).
 */
@Controller
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatConversationRepository conversationRepository;

    public ChatController(ChatService chatService, ChatConversationRepository conversationRepository) {
        this.chatService = chatService;
        this.conversationRepository = conversationRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("conversations", chatService.listConversations());
        return "chat-list";
    }

    @PostMapping
    public String start() {
        ChatConversation conversation = chatService.startConversation();
        return "redirect:/chat/" + conversation.getId();
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        conversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversazione non trovata"));
        model.addAttribute("conversationId", id);
        model.addAttribute("messages", chatService.getMessages(id));
        return "chat";
    }

    @PostMapping("/{id}/messages")
    public String send(@PathVariable Long id,
                        @RequestParam String message,
                        @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                        Model model) {
        boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
        try {
            var messages = chatService.sendMessage(id, message);
            model.addAttribute("conversationId", id);
            model.addAttribute("messages", messages);
            return isHtmxRequest ? "fragments/chat :: messages" : "redirect:/chat/" + id;
        } catch (ChatException e) {
            model.addAttribute("conversationId", id);
            model.addAttribute("messages", chatService.getMessages(id));
            model.addAttribute("error", e.getMessage());
            return isHtmxRequest ? "fragments/chat :: messages" : "chat";
        }
    }
}
