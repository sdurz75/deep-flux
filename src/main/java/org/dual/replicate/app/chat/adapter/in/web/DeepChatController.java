package org.dual.replicate.app.chat.adapter.in.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.core.chat.domain.ChatConversation;
import org.dual.replicate.core.chat.domain.ChatMessage;
import org.dual.replicate.core.chat.domain.ChatMessageRole;
import org.dual.replicate.core.chat.domain.ChatOutcomeView;
import org.dual.replicate.core.chat.domain.FileRef;
import org.dual.replicate.core.chat.port.in.IChatOutcomeResolver;
import org.dual.replicate.core.chat.port.in.IChatPageContributor;
import org.dual.replicate.core.chat.port.in.IChatConversations;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final IChatConversations conversations;
    private final List<IChatPageContributor> pageContributors;
    private final ChatLinkPaths linkPaths;
    private final java.util.Optional<IChatOutcomeResolver> outcomeResolver;
    private final ObjectMapper objectMapper;
    private final Messages messages;

    public DeepChatController(IChatConversations conversations,
                               List<IChatPageContributor> pageContributors,
                               ChatLinkPaths linkPaths,
                               java.util.Optional<IChatOutcomeResolver> outcomeResolver,
                               ObjectMapper objectMapper,
                               Messages messages) {
        this.conversations = conversations;
        this.pageContributors = pageContributors;
        this.linkPaths = linkPaths;
        this.outcomeResolver = outcomeResolver;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    /** Nessuna pagina "senza conversazione": risolve sempre quella piu' di recente attiva (o ne crea una nuova al primo avvio) e ci naviga. */
    @GetMapping("/deep-chat")
    public String defaultConversation() {
        return "redirect:/deep-chat/" + conversations.resolveDefault().getId();
    }

    @GetMapping("/deep-chat/{id}")
    public String page(@PathVariable Long id, Model model) {
        ChatConversation conversation = conversations.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("deepchat.error.conversationNotFound")));
        // Form di generazione di QUESTA conversazione (NULL = precedente alla persistenza lato server: il form adotta quello del browser).
        model.addAttribute("generationSettingsJson", conversation.getGenerationSettingsJson());

        // chatHistoryJson e' serializzata qui (non nel template) come
        // stringa JSON gia' pronta: il template la inlinea via Thymeleaf
        // JS-inlining (th:inline="javascript"), che sa escapare una
        // String Java per un contesto JS in modo sicuro. I modelli
        // (models sotto) sono invece una vera entity JPA con getter
        // JavaBean: la select del combobox li legge con un semplice
        // th:each, nessun bridge JSON/data-* necessario.
        model.addAttribute("chatHistoryJson", objectMapper.writeValueAsString(loadHistory(id)));
        model.addAttribute("conversations", conversations.list());
        model.addAttribute("activeConversationId", id);
        model.addAttribute("chatLinkAlternatives", linkPaths.alternatives());
        model.addAttribute("chatLinkEntities", linkPaths.entities());
        // Il resto del model (pannello di generazione, placeholder, galleria contestuale) lo mette chi ospita la chat.
        pageContributors.forEach(contributor -> model.addAllAttributes(contributor.pageAttributes(id)));
        return "app/deep-chat";
    }

    /**
     * Salva lo stato del form di generazione della conversazione (fetch dello script di persistenza, best effort: nessuna UI da aggiornare,
     * quindi 204). Il corpo e' JSON grezzo, la chat non lo interpreta.
     */
    @PostMapping(value = "/deep-chat/{id}/settings", consumes = "application/json")
    public ResponseEntity<Void> saveSettings(@PathVariable Long id, @RequestBody String json) {
        if (conversations.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, messages.get("deepchat.error.conversationNotFound"));
        }
        try {
            conversations.saveGenerationSettings(id, json);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/deep-chat/new")
    public String create(HttpServletRequest request, HttpServletResponse response) {
        ChatConversation conversation = conversations.create();
        response.setHeader("HX-Redirect", request.getContextPath() + "/deep-chat/" + conversation.getId());
        return null;
    }

    /** Non tocca mai la chat visibile (non rinomina quella attualmente aperta in modo distruttivo): sempre solo la sidebar da aggiornare. */
    @PostMapping("/deep-chat/{id}/rename")
    public String rename(@PathVariable Long id,
                          @RequestParam String title,
                          @RequestParam Long activeConversationId,
                          Model model) {
        conversations.rename(id, title);
        model.addAttribute("conversations", conversations.list());
        model.addAttribute("activeConversationId", activeConversationId);
        return "fragments/app/conversation-list :: items(conversations=${conversations}, activeConversationId=${activeConversationId})";
    }

    /** Tag utente della conversazione: come {@link #rename}, risponde con la sola sidebar. Oltre il tetto di tag e' un 422 (toast di errore di htmx). */
    @PostMapping("/deep-chat/{id}/tags/add")
    public String addTag(@PathVariable Long id, @RequestParam String tag, @RequestParam Long activeConversationId, Model model) {
        try {
            conversations.addTag(id, tag);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        return conversationItems(activeConversationId, model);
    }

    @PostMapping("/deep-chat/{id}/tags/remove")
    public String removeTag(@PathVariable Long id, @RequestParam String tag, @RequestParam Long activeConversationId, Model model) {
        try {
            conversations.removeTag(id, tag);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        return conversationItems(activeConversationId, model);
    }

    private String conversationItems(Long activeConversationId, Model model) {
        model.addAttribute("conversations", conversations.list());
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
        conversations.delete(id);
        if (id.equals(activeConversationId)) {
            response.setHeader("HX-Redirect", request.getContextPath() + "/deep-chat");
            return null;
        }
        model.addAttribute("conversations", conversations.list());
        model.addAttribute("activeConversationId", activeConversationId);
        return "fragments/app/conversation-list :: items(conversations=${conversations}, activeConversationId=${activeConversationId})";
    }

    /**
     * Cronologia persistita di una conversazione (IChatConversations)
     * nello stesso formato "role"/"text"/"files" che deep-chat usa per
     * le risposte live (vedi DeepChatApiController): cosi' un turno con
     * immagine ripristinato dopo un reload appare identico a quando e'
     * stato generato, non solo come testo.
     */
    private List<HistoryMessage> loadHistory(Long conversationId) {
        List<ChatMessage> messages = conversations.history(conversationId);
        // Gli esiti dei turni in un colpo solo (la chat ne conosce solo il riferimento): uno cancellato e' semplicemente assente.
        java.util.List<Long> refs = messages.stream().map(ChatMessage::getOutcomeRef).filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<Long, ChatOutcomeView> outcomes = refs.isEmpty() || outcomeResolver.isEmpty()
                ? java.util.Map.of() : outcomeResolver.get().resolve(refs);
        return messages.stream().map(message -> toHistoryMessage(message, message.getOutcomeRef(),
                message.getOutcomeRef() == null ? null : outcomes.get(message.getOutcomeRef()))).toList();
    }

    private HistoryMessage toHistoryMessage(ChatMessage message, Long outcomeRef, ChatOutcomeView outcome) {
        String role = message.getRole() == ChatMessageRole.USER ? "user" : "ai";
        return new HistoryMessage(role, message.getContent(), outcome != null ? outcome.files() : null,
                message.isError() ? Boolean.TRUE : null, outcome != null ? outcomeRef : null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record HistoryMessage(String role, String text, List<FileRef> files, Boolean error,
                                  Long generationId) {
    }
}
