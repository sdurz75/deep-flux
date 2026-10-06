package org.dual.replicate.core.chat.adapter.in.web;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Il JSON di risposta resta quello che deep-chat.html legge: le parti dei toolkit sono chiavi di primo livello, non un oggetto "extras". */
class DeepChatReplyJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolkitExtrasAreFlattenedIntoTheReply() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new DeepChatApiController.Reply(
                "Sto generando", null, Map.of("generationIds", List.of(12, 13)))));

        assertThat(json.get("text").asText()).isEqualTo("Sto generando");
        assertThat(json.get("generationIds")).hasSize(2);
        assertThat(json.has("extras")).isFalse();
        assertThat(json.has("error")).isFalse();
    }

    @Test
    void aReplyWithoutExtrasHasOnlyTheText() {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(new DeepChatApiController.Reply("ciao", null, null)));

        assertThat(json.propertyNames()).containsExactly("text");
    }
}
