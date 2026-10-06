package org.dual.replicate.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.port.out.ISystemEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;

@SpringBootTest
class HtmxEventsTest {

    @Autowired
    private HtmxEvents htmx;

    @Autowired
    private ISystemEvents events;

    @Autowired
    private ISystemEventStore store;

    @BeforeEach
    void clean() {
        store.deleteAll();
    }

    /** L'header HX-Trigger e' uno solo: aggiungere il toast a un evento gia' presente non lo sovrascrive. */
    @Test
    void addingAToastKeepsAnExistingHxTriggerEvent() {
        var response = new MockHttpServletResponse();
        response.setHeader("HX-Trigger", "gallery-update");

        htmx.addToastHeader(response, events.record("op", new IllegalStateException("bug")));

        String header = response.getHeader("HX-Trigger");
        assertThat(header).startsWith("{").contains("\"gallery-update\"").contains("\"system-toast\"")
                .contains("\"message\"").contains("\"transient\":false");
    }

    @Test
    void addingAnEventToAJsonHxTriggerMergesBothAndKeepsDetails() {
        var response = new MockHttpServletResponse();
        response.setHeader("HX-Trigger", "{\"showMessage\":\"ciao\"}");

        htmx.addHxTrigger(response, "gallery-update", "");

        assertThat(response.getHeader("HX-Trigger")).contains("\"showMessage\":\"ciao\"").contains("\"gallery-update\"");
    }

    @Test
    void theToastHeaderCarriesTheSeverity() {
        var response = new MockHttpServletResponse();
        htmx.addToastHeader(response, events.warn(CoreEventSource.TOKENS, "tokenExpired", "token:3", "scaduto"));

        assertThat(response.getHeader("HX-Trigger")).contains("\"severity\":\"WARNING\"").contains("\"transient\":false");
    }
}
