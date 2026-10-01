package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * con gli argomenti/vararg di quel metodo (1 per defaultSystem(String), 2 per defaultTools(Object...) chiamato con 2 argomenti, ecc.).
 */
@ExtendWith(MockitoExtension.class)
class SpringAiAssistantTest {

    @Mock
    private WebSearchTool webSearchTool;

    @Mock
    private ImageGenerationTool imageGenerationTool;

    @Mock
    private Messages i18n;

    private SpringAiAssistant assistant(ChatClient.Builder builder, Optional<ArchiveSearchTool> archive) {
        return new SpringAiAssistant(builder, webSearchTool, imageGenerationTool, archive, i18n, "guida");
    }

    /** Con la ricerca semantica attiva il modello riceve anche searchArchive; senza, solo i due tool storici. */
    @Test
    void theArchiveSearchToolIsRegisteredOnlyWhenPresent() {
        ChatClient.Builder withTool = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        assistant(withTool, Optional.of(mock(ArchiveSearchTool.class)));
        ChatClient.Builder without = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        assistant(without, Optional.empty());

        verify(withTool.defaultSystem(anyString())).defaultTools(any(), any(), any());
        verify(without.defaultSystem(anyString())).defaultTools(any(), any());
    }

    @Test
    void respondReturnsTheTextOfTheModel() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        ChatResponse chatResponse = new ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(new AssistantMessage("Ciao! Come posso aiutarti?"))),
                ChatResponseMetadata.builder().build());
        when(builder.defaultSystem(anyString()).defaultTools(any(), any()).build()
                .prompt().messages(anyList()).toolContext(anyMap()).call().chatResponse())
                .thenReturn(chatResponse);

        ChatReply reply = assistant(builder, Optional.empty())
                .respond(List.of(new ChatTurn("user", "ciao")), "owner/model", Map.of());

        assertThat(reply.text()).isEqualTo("Ciao! Come posso aiutarti?");
        assertThat(reply.startedGenerationIds()).isEmpty();
    }

    /** Un guasto dell'LLM e' tradotto (OpenRouterException con la causa originale) e portato da AssistantException. */
    @Test
    void respondWrapsAModelFailureInAnAssistantException() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        RuntimeException outage = new RuntimeException("OpenRouter giu'");
        when(builder.defaultSystem(anyString()).defaultTools(any(), any()).build()
                .prompt().messages(anyList()).toolContext(anyMap()).call().chatResponse())
                .thenThrow(outage);
        SpringAiAssistant assistant = assistant(builder, Optional.empty());
        List<ChatTurn> history = List.of(new ChatTurn("user", "ciao"));

        assertThatThrownBy(() -> assistant.respond(history, "owner/model", Map.of()))
                .isInstanceOfSatisfying(AssistantException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(OpenRouterException.class);
                    assertThat(e.getCause().getCause()).isSameAs(outage);
                    assertThat(e.startedGenerationIds()).isEmpty();
                });
    }
}
