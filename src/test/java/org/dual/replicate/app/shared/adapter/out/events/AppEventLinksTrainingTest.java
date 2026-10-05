package org.dual.replicate.app.shared.adapter.out.events;

import java.util.List;

import org.dual.replicate.core.events.domain.EventLink;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Un evento di sistema col subject {@code training:<id>} porta alla pagina del training. */
class AppEventLinksTrainingTest {

    private final Messages messages = mock(Messages.class);
    private final AppEventLinks links = new AppEventLinks(messages);

    AppEventLinksTrainingTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0) + ":" + i.getArgument(1));
    }

    @Test
    void aTrainingSubjectLinksToItsPage() {
        List<EventLink> resolved = links.resolve("training:12");

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).path()).isEqualTo("/trainings/12");
        assertThat(resolved.get(0).label()).isEqualTo("events.link.training:12");
    }

    @Test
    void aMalformedTrainingSubjectHasNoLink() {
        assertThat(links.resolve("training:abc")).isEmpty();
        assertThat(links.resolve("training")).isEmpty();
    }
}
