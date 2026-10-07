package org.hexa.app.chat.application;

import java.time.Duration;
import java.util.Locale;

import org.hexa.app.generation.port.in.IGenerations;
import org.hexa.app.shared.domain.AppEventSubjects;
import org.hexa.core.events.port.in.ISystemEvents;
import org.hexa.core.events.domain.CoreEventSource;
import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.GenerationStatus;
import org.hexa.core.chat.domain.ChatOutcome;
import org.hexa.core.chat.port.in.IChatOutcomes;
import org.hexa.core.kernel.i18n.Messages;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Attende in background (fuori dal thread della richiesta HTTP di
 * /api/deep-chat) l'esito di una generazione avviata da
 * ImageGenerationTool (adapter.ai), poi lo persiste come nuovo turno della
 * conversazione e lo notifica via SSE (IChatNotifier) a chi
 * ha quella conversazione aperta. Avviato da ChatService.reply, uno
 * per ogni generazione avviata nel turno .
 */
@Service
public class ChatGenerationWatcher {

    /**
     * Margine sopra il timeout interno di IGenerations.refresh
     * (5 minuti): a quel punto la generazione e' comunque gia' terminale
     * (marcata FAILED per timeout), quindi waitUntilTerminal ritorna
     * prima di arrivare qui. Questo e' solo un tetto di sicurezza.
     */
    private static final Duration WATCH_TIMEOUT = Duration.ofMinutes(6);

    private final IGenerations generationService;
    private final IChatOutcomes outcomes;
    private final Messages i18n;
    private final ISystemEvents systemEvents;

    public ChatGenerationWatcher(IGenerations generationService,
                                      IChatOutcomes outcomes,
                                      Messages i18n,
                                      ISystemEvents systemEvents) {
        this.generationService = generationService;
        this.outcomes = outcomes;
        this.i18n = i18n;
        this.systemEvents = systemEvents;
    }

    @Async
    public void watch(Long generationId, Long conversationId, Locale locale) {
        // Un thread @Async non eredita LocaleContextHolder dalla
        // richiesta: senza questo, Messages risolverebbe sulla locale
        // della JVM, non quella del browser che ha avviato la
        // generazione (vedi ChatService.reply, dove viene catturata).
        LocaleContextHolder.setLocale(locale);
        try {
            Generation generation;
            try {
                generation = generationService.waitUntilTerminal(generationId, WATCH_TIMEOUT);
            } catch (RuntimeException e) {
                if (!generationService.exists(generationId)) {
                    // La generazione e' stata cancellata (da /generations, possibile anche mentre non e'
                    // ancora terminale, vedi CLAUDE.md) mentre questo watcher la aspettava: nessun turno
                    // da scrivere ne' errore da segnalare (l'utente l'ha cancellata di proposito).
                    return;
                }
                // Qualunque altro fallimento (DB, errore inatteso) NON e' una cancellazione: va registrato
                // e mostrato. Il turno di esito lo scrive comunque GenerationRecoveryService (sweep), quindi il
                // placeholder in chat non resta appeso per sempre.
                systemEvents.record(CoreEventSource.INTERNAL, "watchGeneration", e, AppEventSubjects.of(generationId, conversationId));
                return;
            }
            if (!generation.isTerminal()) {
                // Tetto di sicurezza scaduto o thread interrotto (shutdown): NON e' un fallimento della
                // generazione, quindi niente falso turno "fallita". Ci pensa il recupero (avvio/sweep).
                return;
            }
            persistOutcome(generation, conversationId);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    /**
     * Scrive il turno di esito (riuscita/fallita) di una generazione nella sua conversazione e lo notifica via
     * SSE ({@code IChatOutcomes#append}, che e' idempotente: watcher e recupero possono incrociarsi). Non lancia: un errore di persistenza viene registrato e il turno verra'
     * ritentato dallo sweep di GenerationRecoveryService. Ritorna true se ha scritto un turno.
     */
    public boolean persistOutcome(Generation generation, Long conversationId) {
        try {
            String text = generation.getStatus() == GenerationStatus.SUCCEEDED
                    ? i18n.get("deepchat.push.succeeded")
                    : i18n.get("deepchat.push.failed", generation.getErrorMessage() != null
                            ? generation.getErrorMessage() : i18n.get("generation.error.failedGeneric"));
            return outcomes.append(conversationId,
                    new ChatOutcome(generation.getId(), text, GenerationChatOutcomes.fileRefs(generation)));
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "persistChatTurn", e, AppEventSubjects.of(generation.getId(), conversationId));
            return false;
        }
    }
}
