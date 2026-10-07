package org.dual.hexa.controller;

import org.dual.hexa.ai.chat.domain.ChatConversation;
import org.dual.hexa.ai.chat.port.out.IChatConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Il form di generazione di /deep-chat e' una proprieta' della conversazione: la pagina lo porta nel form, il client lo salva via POST. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeepChatSettingsTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IChatConversationStore conversations;

    private String page(ChatConversation conversation) throws Exception {
        return mockMvc.perform(get("/deep-chat/" + conversation.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aNewConversationRendersTheEmptyStateAndItsOwnSaveUrl() throws Exception {
        ChatConversation conversation = conversations.save(new ChatConversation());

        String chat = page(conversation);

        // "{}" = default del catalogo: la pagina lo dichiara esplicitamente, cosi' lo script NON ripiega su localStorage.
        assertThat(chat).contains("data-persist-server-state=\"{}\"")
                .contains("data-persist-save-url=\"/deep-chat/" + conversation.getId() + "/settings\"");
    }

    @Test
    void aSavedStateIsRenderedEscapedIntoTheForm() throws Exception {
        ChatConversation conversation = new ChatConversation();
        conversation.setGenerationSettingsJson("{\"model\":\"black-forest-labs/flux-dev-lora\",\"lora_weights\":\"me/a'b\\\"c\"}");
        conversation = conversations.save(conversation);

        String chat = page(conversation);

        assertThat(chat).contains("data-persist-server-state=\"{&quot;model&quot;:&quot;black-forest-labs/flux-dev-lora&quot;,&quot;lora_weights&quot;:&quot;me/a&#39;b\\&quot;c&quot;}\"");
    }

    /** NULL = conversazione precedente alla migrazione: nessun attributo, lo script adotta localStorage. */
    @Test
    void aLegacyConversationHasNoServerStateAttribute() throws Exception {
        ChatConversation conversation = new ChatConversation();
        conversation.setGenerationSettingsJson(null);
        conversation = conversations.save(conversation);

        assertThat(page(conversation)).doesNotContain("data-persist-server-state=\"")
                .contains("data-persist-save-url=\"/deep-chat/" + conversation.getId() + "/settings\"");
    }

    @Test
    void postingSettingsStoresThemOnTheConversation() throws Exception {
        ChatConversation conversation = conversations.save(new ChatConversation());
        String json = "{\"model\":\"owner/m\",\"num_outputs\":\"2\"}";

        mockMvc.perform(post("/deep-chat/" + conversation.getId() + "/settings").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isNoContent());

        assertThat(conversations.findById(conversation.getId()).orElseThrow().getGenerationSettingsJson()).isEqualTo(json);
        assertThat(page(conversation)).contains("&quot;num_outputs&quot;:&quot;2&quot;");
    }

    @Test
    void invalidOrUnknownTargetsAreRejected() throws Exception {
        ChatConversation conversation = conversations.save(new ChatConversation());
        String url = "/deep-chat/" + conversation.getId() + "/settings";

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("[1]")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{broken")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/deep-chat/999999/settings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(conversations.findById(conversation.getId()).orElseThrow().getGenerationSettingsJson()).isEqualTo("{}");
    }

    /** /deep-chat "nudo" porta sempre alla conversazione piu' recente: e' LEI a fornire stato e URL di salvataggio. */
    @Test
    void theBareChatUrlRendersTheMostRecentConversationState() throws Exception {
        ChatConversation conversation = new ChatConversation();
        conversation.setGenerationSettingsJson("{\"num_outputs\":\"3\"}");
        conversation.touch();
        conversation = conversations.save(conversation);

        String target = mockMvc.perform(get("/deep-chat")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        String chat = mockMvc.perform(get(target)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(target).isEqualTo("/deep-chat/" + conversation.getId());
        assertThat(chat).contains("&quot;num_outputs&quot;:&quot;3&quot;")
                .contains("data-persist-save-url=\"/deep-chat/" + conversation.getId() + "/settings\"");
    }

    /** Lo stato salvato non deve far risalire `version` (hash pinnato) e il restore del server passa per `data-persist-ignore`. */
    @Test
    void theServerStatePathHonoursTheIgnoreAndNoRestoreRulesOfTheScript() throws Exception {
        String script = new String(getClass().getResourceAsStream("/templates/fragments/app/generation-settings-persist.html").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        String readState = script.substring(script.indexOf("function readState"), script.indexOf("// Salvataggio sul server"));

        assertThat(readState).contains("names(form, 'data-persist-ignore').forEach(function (name) { delete parsed[name]; })");
        // I campi `data-persist-no-restore` valgono per qualunque fonte: li applica applyStoredFields.
        assertThat(script.substring(script.indexOf("function applyStoredFields"), script.indexOf("var SHARED_KEY")))
                .contains("noRestoreNames(form)");
    }
}
