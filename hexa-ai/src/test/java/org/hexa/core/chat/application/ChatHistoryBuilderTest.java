package org.hexa.core.chat.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hexa.core.chat.domain.ChatConversation;
import org.hexa.core.chat.domain.ChatMessage;
import org.hexa.core.chat.domain.ChatMessageRole;
import org.hexa.core.chat.domain.ChatTurn;
import org.hexa.core.chat.port.out.IChatMessageStore;
import org.hexa.core.chat.domain.ChatOutcomeView;
import org.hexa.core.chat.port.in.IChatOutcomeResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** La cronologia del modello e' costruita dal server: finestra, errori esclusi, esiti come note, ruoli alternati. */
class ChatHistoryBuilderTest {

    private final IChatMessageStore store = mock(IChatMessageStore.class);
    /** Un risolutore finto: la nota per il modello di ogni riferimento (la sua forma e' dell'host, vedi GenerationChatOutcomesTest). */
    private final Map<Long, ChatOutcomeView> outcomes = new HashMap<>();
    private final IChatOutcomeResolver resolver = refs -> {
        Map<Long, ChatOutcomeView> found = new HashMap<>();
        refs.forEach(ref -> {
            if (outcomes.containsKey(ref)) {
                found.put(ref, outcomes.get(ref));
            }
        });
        return found;
    };
    private final ChatConversation conversation = new ChatConversation();

    private ChatHistoryBuilder builder(int maxTurns, int maxChars) {
        return new ChatHistoryBuilder(store, java.util.Optional.of(resolver), maxTurns, maxChars);
    }

    private ChatMessage user(String text) {
        return new ChatMessage(conversation, ChatMessageRole.USER, text, null);
    }

    private ChatMessage ai(String text) {
        return new ChatMessage(conversation, ChatMessageRole.AI, text, null);
    }

    private ChatMessage outcome(String text, long generationId) {
        return new ChatMessage(conversation, ChatMessageRole.AI, text, generationId);
    }

    private void stored(ChatMessage... messages) {
        when(store.findByConversation(1L)).thenReturn(List.of(messages));
    }

    @Test
    void persistedTurnsBecomeTheHistoryInOrder() {
        stored(user("ciao"), ai("Ciao! Cosa creiamo?"), user("un gatto"));

        assertThat(builder(40, 60000).build(1L, new ChatTurn("user", "un gatto"))).containsExactly(
                new ChatTurn("user", "ciao"), new ChatTurn("ai", "Ciao! Cosa creiamo?"), new ChatTurn("user", "un gatto"));
    }

    /** Il testo mostrato all'utente ("Immagine pronta") non va al modello: ci va l'esito con l'id e i file. */
    @Test
    void anOutcomeTurnReachesTheModelAsASystemNoteWithTheFiles() {
        outcomes.put(12L, new ChatOutcomeView("Generation #12 finished: files a.png, b.png.", null));
        outcomes.put(13L, new ChatOutcomeView("Generation #13 failed: Generazione annullata.", null));
        stored(user("genera"), ai("Avviata #12 e #13"), outcome("Immagine pronta", 12), outcome("Fallita", 13), user("e adesso?"));

        List<ChatTurn> history = builder(40, 60000).build(1L, new ChatTurn("user", "e adesso?"));

        assertThat(history).extracting(ChatTurn::role).containsExactly("user", "ai", "system", "user");
        assertThat(history.get(2).text()).isEqualTo("Generation #12 finished: files a.png, b.png.\n\nGeneration #13 failed: Generazione annullata.");
        assertThat(history).extracting(ChatTurn::text).doesNotContain("Immagine pronta", "Fallita");
    }

    @Test
    void anOutcomeWhoseGenerationWasDeletedStaysAPlainAssistantTurn() {
        stored(user("genera"), outcome("Immagine pronta", 12), user("ok"));

        assertThat(builder(40, 60000).build(1L, new ChatTurn("user", "ok"))).extracting(ChatTurn::role)
                .containsExactly("user", "ai", "user");
    }

    @Test
    void errorTurnsAreSkippedAndTheTwoRequestsAroundThemMerge() {
        stored(user("genera un gatto"), ChatMessage.errorTurn(conversation, "Errore assistente"), user("riprova"));

        assertThat(builder(40, 60000).build(1L, new ChatTurn("user", "riprova"))).containsExactly(
                new ChatTurn("user", "genera un gatto\n\nriprova"));
    }

    @Test
    void theWindowKeepsTheLastTurnsAndStartsFromAUserTurn() {
        stored(user("uno"), ai("due"), user("tre"), ai("quattro"), user("cinque"));

        // Ultimi 4 = ai(due)? No: ultimi 4 sono due, tre, quattro, cinque; comincia da "tre" (un turno utente).
        assertThat(builder(4, 60000).build(1L, new ChatTurn("user", "cinque"))).containsExactly(
                new ChatTurn("user", "tre"), new ChatTurn("ai", "quattro"), new ChatTurn("user", "cinque"));
    }

    @Test
    void theCharacterBudgetDropsTheOldestTurnsButNeverTheLatestOne() {
        stored(user("a".repeat(50)), ai("b".repeat(50)), user("c".repeat(50)));

        assertThat(builder(40, 120).build(1L, new ChatTurn("user", "c".repeat(50)))).extracting(ChatTurn::text)
                .containsExactly("c".repeat(50));
        assertThat(builder(40, 10).build(1L, new ChatTurn("user", "c".repeat(50)))).extracting(ChatTurn::text)
                .containsExactly("c".repeat(50));
    }

    /** Se la cronologia su DB non finisce con la richiesta corrente (non dovrebbe), il modello risponde comunque a quella. */
    @Test
    void theLatestUserTurnIsAppendedWhenTheStoreDoesNotEndWithIt() {
        stored();

        assertThat(builder(40, 60000).build(1L, new ChatTurn("user", "ciao"))).containsExactly(new ChatTurn("user", "ciao"));
    }
}
