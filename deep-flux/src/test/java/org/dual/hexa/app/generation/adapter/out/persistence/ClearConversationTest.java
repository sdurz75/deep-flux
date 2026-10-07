package org.dual.hexa.app.generation.adapter.out.persistence;

import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.port.out.IGenerationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Lo schema di generation non ha FK verso la chat: scollegare una conversazione cancellata e' un update esplicito. */
@SpringBootTest
class ClearConversationTest {

    @Autowired
    private IGenerationStore store;

    @AfterEach
    void clean() {
        store.deleteAll();
    }

    private Generation generation(String prediction, Long conversationId) {
        Generation generation = new Generation(prediction, "owner/model", null, "a cat", null);
        generation.setConversationId(conversationId);
        return store.save(generation);
    }

    @Test
    void clearingAConversationDetachesOnlyItsGenerations() {
        Generation mine = generation("pred-a", 41L);
        Generation other = generation("pred-b", 42L);
        Generation none = generation("pred-c", null);

        store.clearConversation(41L);

        assertThat(store.findById(mine.getId()).orElseThrow().getConversationId()).isNull();
        assertThat(store.findById(other.getId()).orElseThrow().getConversationId()).isEqualTo(42L);
        assertThat(store.findById(none.getId()).orElseThrow().getConversationId()).isNull();
    }
}
