package org.hexa.core.chat.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.hexa.core.chat.domain.ChatMessage;
import org.hexa.core.chat.domain.ChatMessageRole;
import org.hexa.core.chat.domain.ChatTurn;
import org.hexa.core.chat.port.out.IChatMessageStore;
import org.hexa.core.chat.domain.ChatOutcomeView;
import org.hexa.core.chat.port.in.IChatOutcomeResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * La cronologia che alimenta il modello, costruita dal SERVER (da {@link IChatMessageStore}) e non rimandata dal client: cosi' e' la
 * stessa dal vivo e dopo un reload, non cresce senza limiti e contiene cio' che il client non puo' dare, cioe' l'esito delle
 * generazioni. Un turno di esito (scritto con {@code IChatOutcomes}, col riferimento opaco all'esito) arriva al modello come
 * nota {@link ChatTurn#SYSTEM} ("Generation #12 finished: files a.png"), non come il testo localizzato mostrato all'utente.
 *
 * Regole: i turni d'errore non entrano mai; si tengono gli ultimi {@code app.chat.history-max-turns} turni entro
 * {@code app.chat.history-max-chars} caratteri (l'ultimo c'e' sempre); la finestra comincia da un turno utente; turni consecutivi dello
 * stesso ruolo (un errore saltato lascia due richieste di fila) si fondono, perche' alcuni provider vogliono ruoli alternati.
 */
@Component
class ChatHistoryBuilder {

    private final IChatMessageStore messages;
    private final Optional<IChatOutcomeResolver> outcomeResolver;
    private final int maxTurns;
    private final int maxChars;

    ChatHistoryBuilder(IChatMessageStore messages,
                       Optional<IChatOutcomeResolver> outcomeResolver,
                       @Value("${app.chat.history-max-turns:40}") int maxTurns,
                       @Value("${app.chat.history-max-chars:60000}") int maxChars) {
        this.messages = messages;
        this.outcomeResolver = outcomeResolver;
        this.maxTurns = Math.max(1, maxTurns);
        this.maxChars = Math.max(1, maxChars);
    }

    /**
     * @param latestUserTurn il turno utente che ha innescato la chiamata, se il client lo ha mandato: e' gia' su DB, ma se per qualunque
     *                       motivo la cronologia non lo contiene in coda lo si accoda, cosi' il modello risponde sempre all'ultima richiesta
     */
    List<ChatTurn> build(Long conversationId, ChatTurn latestUserTurn) {
        List<ChatMessage> persisted = messages.findByConversation(conversationId).stream()
                .filter(message -> !message.isError()).toList();
        List<Long> outcomeIds = persisted.stream().map(ChatMessage::getOutcomeRef).filter(Objects::nonNull).distinct().toList();
        Map<Long, ChatOutcomeView> outcomes = outcomeIds.isEmpty() || outcomeResolver.isEmpty()
                ? Map.of() : outcomeResolver.get().resolve(outcomeIds);

        List<ChatTurn> turns = new ArrayList<>();
        for (ChatMessage message : persisted) {
            ChatOutcomeView outcome = message.getOutcomeRef() == null ? null : outcomes.get(message.getOutcomeRef());
            turns.add(toTurn(message, outcome));
        }
        if (latestUserTurn != null && (turns.isEmpty() || !ChatTurn.USER.equals(turns.get(turns.size() - 1).role()))) {
            turns.add(latestUserTurn);
        }
        return merge(startAtUserTurn(window(turns)));
    }

    private static ChatTurn toTurn(ChatMessage message, ChatOutcomeView outcome) {
        if (outcome != null) {
            return new ChatTurn(ChatTurn.SYSTEM, outcome.modelNote());
        }
        return new ChatTurn(message.getRole() == ChatMessageRole.USER ? ChatTurn.USER : ChatTurn.AI, message.getContent());
    }

    /** Gli ultimi {@code maxTurns} turni entro {@code maxChars} caratteri; l'ultimo turno non viene mai scartato. */
    private List<ChatTurn> window(List<ChatTurn> turns) {
        int from = Math.max(0, turns.size() - maxTurns);
        int chars = 0;
        int start = turns.size();
        for (int i = turns.size() - 1; i >= from; i--) {
            chars += turns.get(i).text().length();
            if (chars > maxChars && start < turns.size()) {
                break;
            }
            start = i;
        }
        return turns.subList(start, turns.size());
    }

    /** Una finestra non comincia da una risposta o da una nota senza la richiesta che le precede (a meno che non resti altro). */
    private static List<ChatTurn> startAtUserTurn(List<ChatTurn> turns) {
        for (int i = 0; i < turns.size(); i++) {
            if (ChatTurn.USER.equals(turns.get(i).role())) {
                return turns.subList(i, turns.size());
            }
        }
        return turns;
    }

    private static List<ChatTurn> merge(List<ChatTurn> turns) {
        List<ChatTurn> merged = new ArrayList<>();
        for (ChatTurn turn : turns) {
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).role().equals(turn.role())) {
                merged.set(last, new ChatTurn(turn.role(), merged.get(last).text() + "\n\n" + turn.text()));
            } else {
                merged.add(turn);
            }
        }
        return merged;
    }
}
