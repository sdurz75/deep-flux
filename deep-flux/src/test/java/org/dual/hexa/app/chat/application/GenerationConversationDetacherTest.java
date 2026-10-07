package org.dual.hexa.app.chat.application;

import org.dual.hexa.ai.chat.domain.event.ChatConversationDeletedEvent;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GenerationConversationDetacherTest {

    @Test
    void aDeletedConversationOnlyDetachesItsGenerations() {
        IGenerations generations = mock(IGenerations.class);

        new GenerationConversationDetacher(generations).on(new ChatConversationDeletedEvent(7L));

        verify(generations).detachFromConversation(7L);
        verify(generations, org.mockito.Mockito.never()).delete(org.mockito.ArgumentMatchers.anyLong());
    }
}
