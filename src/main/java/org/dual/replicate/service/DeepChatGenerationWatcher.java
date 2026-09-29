package org.dual.replicate.service;

import java.time.Duration;
import java.util.Locale;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.domain.event.ChatMessagePushEvent;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Attende in background (fuori dal thread della richiesta HTTP di
 * /api/deep-chat) l'esito di una generazione avviata da
 * ImageGenerationTool, poi lo persiste come nuovo turno della
 * conversazione e lo notifica via SSE (GenerationEventBroadcaster) a chi
 * ha quella conversazione aperta. Avviato da DeepChatService.reply, uno
 * per ogni generazione avviata nel turno (vedi GenerationResultHolder).
 */
@Service
public class DeepChatGenerationWatcher {

    /**
     * Margine sopra il timeout interno di GenerationService.refresh
     * (5 minuti): a quel punto la generazione e' comunque gia' terminale
     * (marcata FAILED per timeout), quindi waitUntilTerminal ritorna
     * prima di arrivare qui. Questo e' solo un tetto di sicurezza.
     */
    private static final Duration WATCH_TIMEOUT = Duration.ofMinutes(6);

    private final GenerationService generationService;
    private final ChatConversationRepository chatConversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final GenerationEventBroadcaster broadcaster;
    private final Messages i18n;

    public DeepChatGenerationWatcher(GenerationService generationService,
                                      ChatConversationRepository chatConversationRepository,
                                      ChatMessageRepository chatMessageRepository,
                                      GenerationEventBroadcaster broadcaster,
                                      Messages i18n) {
        this.generationService = generationService;
        this.chatConversationRepository = chatConversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.broadcaster = broadcaster;
        this.i18n = i18n;
    }

    /**
     * Sincrono (a differenza di watch, @Async): il legame con la conversazione
     * deve esistere prima che la risposta HTTP del turno raggiunga il client,
     * cosi' un reload subito dopo trova la generazione in corso.
     */
    public void attachToConversation(Long generationId, Long conversationId) {
        generationService.attachToConversation(generationId, conversationId);
    }

    @Async
    public void watch(Long generationId, Long conversationId, Locale locale) {
        // Un thread @Async non eredita LocaleContextHolder dalla
        // richiesta: senza questo, Messages risolverebbe sulla locale
        // della JVM, non quella del browser che ha avviato la
        // generazione (vedi DeepChatService.reply, dove viene catturata).
        LocaleContextHolder.setLocale(locale);
        try {
            Generation generation;
            try {
                generation = generationService.waitUntilTerminal(generationId, WATCH_TIMEOUT);
            } catch (ReplicateException e) {
                // La generazione e' stata cancellata (da /generations, ora possibile anche
                // mentre non e' ancora terminale, vedi CLAUDE.md) mentre questo watcher la
                // stava ancora aspettando: nessun turno di chat da scrivere ne' evento da
                // notificare per un id ormai inesistente, e non e' un errore reale da
                // segnalare (l'utente l'ha cancellata di proposito) - stesso trattamento
                // silenzioso della conversazione cancellata sotto.
                return;
            }
            ChatConversation conversation = chatConversationRepository.findById(conversationId).orElse(null);
            if (conversation == null) {
                // Conversazione cancellata mentre la generazione era in corso.
                return;
            }

            // Un errorMessage nullo capita solo se il tetto di sicurezza
            // WATCH_TIMEOUT scade senza che la generazione sia ancora
            // terminale (mai osservato in pratica: il timeout interno di
            // GenerationService.refresh, piu' breve, arriva sempre prima).
            String text = generation.getStatus() == GenerationStatus.SUCCEEDED
                    ? i18n.get("deepchat.push.succeeded")
                    : i18n.get("deepchat.push.failed", generation.getErrorMessage() != null
                            ? generation.getErrorMessage() : i18n.get("generation.error.failedGeneric"));

            conversation.touch();
            chatConversationRepository.save(conversation);
            chatMessageRepository.save(new ChatMessage(conversation, ChatMessageRole.AI, text, generation));

            broadcaster.broadcastChatMessage(new ChatMessagePushEvent(
                    conversationId, generationId, text, DeepChatService.toFiles(generation)));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
