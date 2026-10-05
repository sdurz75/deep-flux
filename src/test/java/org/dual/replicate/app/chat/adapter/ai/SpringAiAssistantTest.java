package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;

import org.dual.replicate.app.chat.domain.AssistantException;
import org.dual.replicate.app.chat.domain.ChatReply;
import org.dual.replicate.app.chat.domain.ChatTurn;
import org.dual.replicate.app.shared.domain.OpenRouterException;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatClient.Builder e' mockato con RETURNS_DEEP_STUBS: e' un'interfaccia fluente (defaultSystem/defaultTools/build/prompt/messages/
 * toolContext/call). Lo stub va impostato ripercorrendo la catena con ArgumentMatchers (non con valori letterali): un deep stub NON
 * restituisce lo stesso sotto-mock per argomenti diversi, quindi il numero di matcher per ogni chiamata deve combaciare esattamente
 * con gli argomenti di quel metodo (1 per defaultSystem(String), un solo any(Object[].class) per il vararg di defaultTools(Object...)).
 */
@ExtendWith(MockitoExtension.class)
class SpringAiAssistantTest {

    @Mock
    private Messages i18n;

    private static MockEnvironment promptEnvironment() {
        return new MockEnvironment()
                .withProperty("deep-chat.section.core", "SEZIONE-CORE")
                .withProperty("deep-chat.section.guidance", "SEZIONE-GUIDANCE")
                .withProperty("deep-chat.section.appmap", "SEZIONE-APPMAP")
                .withProperty("deep-chat.section.web", "SEZIONE-WEB")
                .withProperty("deep-chat.section.library", "SEZIONE-LIBRARY")
                .withProperty("deep-chat.section.archive", "SEZIONE-ARCHIVE")
                .withProperty("deep-chat.section.curation", "SEZIONE-CURATION")
                .withProperty("deep-chat.section.actions", "SEZIONE-ACTIONS")
                .withProperty("deep-chat.section.notes", "SEZIONE-NOTES")
                .withProperty("deep-chat.section.generation", "SEZIONE-GENERATION")
                .withProperty("prompts.creative-context", "contesto")
                .withProperty("deep-chat.image-prompting-guide", "guida");
    }

    /** Un toolkit finto: la sezione che dichiara e basta (i metodi @Tool non servono, il ChatClient e' mockato). */
    private static ChatToolkit toolkit(String section) {
        return () -> section;
    }

    /** I toolkit di produzione presenti con la ricerca semantica attiva (archivio + note) o spenta. */
    private static List<ChatToolkit> toolkits(boolean search) {
        List<ChatToolkit> toolkits = new java.util.ArrayList<>(List.of(toolkit("deep-chat.section.web"), toolkit("deep-chat.section.library"),
                toolkit("deep-chat.section.curation"), toolkit("deep-chat.section.actions"), toolkit("deep-chat.section.generation")));
        if (search) {
            toolkits.add(2, toolkit("deep-chat.section.archive"));
            toolkits.add(toolkits.size() - 1, toolkit("deep-chat.section.notes"));
        }
        return toolkits;
    }

    private SpringAiAssistant assistant(ChatClient.Builder builder, List<ChatToolkit> toolkits) {
        return new SpringAiAssistant(builder, toolkits, i18n, promptEnvironment());
    }

    /** Il modello riceve esattamente i tool dei toolkit presenti, nell'ordine in cui sono stati iniettati. */
    @Test
    void everyPresentToolkitIsRegisteredAndNothingElse() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        List<ChatToolkit> toolkits = toolkits(true);
        assistant(builder, toolkits);

        org.mockito.ArgumentCaptor<Object[]> tools = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(builder.defaultSystem(anyString())).defaultTools(tools.capture());
        assertThat(tools.getValue()).containsExactlyElementsOf(toolkits);
    }

    /** Il prompt e' assemblato a sezioni: nucleo, sezioni dei toolkit presenti nell'ordine dato, poi contesto e guida. */
    @Test
    void theSystemPromptContainsOnlyTheSectionsOfThePresentToolkits() {
        ChatClient.Builder withSearch = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        assistant(withSearch, toolkits(true));
        ChatClient.Builder without = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        assistant(without, toolkits(false));

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(withSearch).defaultSystem(prompt.capture());
        assertThat(prompt.getValue()).contains("SEZIONE-CORE", "SEZIONE-GUIDANCE", "SEZIONE-APPMAP", "SEZIONE-LIBRARY", "SEZIONE-ARCHIVE", "SEZIONE-CURATION", "SEZIONE-ACTIONS", "SEZIONE-NOTES", "SEZIONE-GENERATION", "contesto", "guida");
        assertThat(prompt.getValue().indexOf("SEZIONE-CORE")).isLessThan(prompt.getValue().indexOf("SEZIONE-WEB"));
        assertThat(prompt.getValue().indexOf("SEZIONE-NOTES")).isLessThan(prompt.getValue().indexOf("SEZIONE-GENERATION"));
        assertThat(prompt.getValue().indexOf("SEZIONE-GENERATION")).isLessThan(prompt.getValue().indexOf("contesto"));
        org.mockito.ArgumentCaptor<String> promptWithout = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(without).defaultSystem(promptWithout.capture());
        assertThat(promptWithout.getValue()).contains("SEZIONE-CORE", "SEZIONE-LIBRARY", "SEZIONE-CURATION", "SEZIONE-ACTIONS").doesNotContain("SEZIONE-ARCHIVE", "SEZIONE-NOTES");
    }

    /** Un toolkit senza sezione propria (null) registra i tool ma non aggiunge nulla al prompt. */
    @Test
    void aToolkitWithoutASectionOnlyContributesItsTools() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        assistant(builder, List.of(toolkit(null), toolkit("deep-chat.section.web")));

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(builder).defaultSystem(prompt.capture());
        assertThat(prompt.getValue()).isEqualTo("SEZIONE-CORE\n\nSEZIONE-GUIDANCE\n\nSEZIONE-APPMAP\n\nSEZIONE-WEB\n\ncontesto\n\nguida");
    }

    /** Le note dell'app (esiti delle generazioni) sono messaggi di sistema, non parole dell'utente ne' dell'assistente. */
    @Test
    void historyRolesMapToUserAssistantAndSystemMessages() {
        List<org.springframework.ai.chat.messages.Message> messages = SpringAiAssistant.buildMessages(List.of(
                new ChatTurn("user", "genera"), new ChatTurn("ai", "Avviata #12"),
                new ChatTurn("system", "Generation #12 finished: files a.png."), new ChatTurn("user", "grazie")), "owner/model");

        assertThat(messages).extracting(m -> m.getMessageType().name()).containsExactly("SYSTEM", "USER", "ASSISTANT", "SYSTEM", "USER");
        assertThat(messages.get(0).getText()).contains("owner/model");
        assertThat(messages.get(3).getText()).isEqualTo("Generation #12 finished: files a.png.");
    }

    @Test
    void respondReturnsTheTextOfTheModel() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        ChatResponse chatResponse = new ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(new AssistantMessage("Ciao! Come posso aiutarti?"))),
                ChatResponseMetadata.builder().build());
        when(builder.defaultSystem(anyString()).defaultTools(any(Object[].class)).build()
                .prompt().messages(anyList()).toolContext(anyMap()).call().chatResponse())
                .thenReturn(chatResponse);

        ChatReply reply = assistant(builder, toolkits(false))
                .respond(5L, List.of(new ChatTurn("user", "ciao")), "owner/model", Map.of());

        assertThat(reply.text()).isEqualTo("Ciao! Come posso aiutarti?");
        assertThat(reply.startedGenerationIds()).isEmpty();
    }

    /** Un guasto dell'LLM e' tradotto (OpenRouterException con la causa originale) e portato da AssistantException. */
    @Test
    void respondWrapsAModelFailureInAnAssistantException() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        RuntimeException outage = new RuntimeException("OpenRouter giu'");
        when(builder.defaultSystem(anyString()).defaultTools(any(Object[].class)).build()
                .prompt().messages(anyList()).toolContext(anyMap()).call().chatResponse())
                .thenThrow(outage);
        SpringAiAssistant assistant = assistant(builder, toolkits(false));
        List<ChatTurn> history = List.of(new ChatTurn("user", "ciao"));

        assertThatThrownBy(() -> assistant.respond(5L, history, "owner/model", Map.of()))
                .isInstanceOfSatisfying(AssistantException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(OpenRouterException.class);
                    assertThat(e.getCause().getCause()).isSameAs(outage);
                    assertThat(e.startedGenerationIds()).isEmpty();
                });
    }
}
