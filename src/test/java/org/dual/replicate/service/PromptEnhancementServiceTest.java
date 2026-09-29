package org.dual.replicate.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatClient.Builder mockato con RETURNS_DEEP_STUBS, stesso stile di
 * DeepChatServiceTest: un ArgumentMatcher per ogni argomento reale della
 * catena (defaultSystem(String) -&gt; build() -&gt; prompt() -&gt; user(String)
 * -&gt; call() -&gt; content()), niente defaultTools(...) qui - a differenza di
 * DeepChatService, questo servizio non registra alcun tool.
 */
@ExtendWith(MockitoExtension.class)
class PromptEnhancementServiceTest {

    @Test
    void enhanceReturnsTrimmedModelOutput() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(chatClientBuilder.defaultSystem(anyString()).build()
                .prompt().user(anyString()).call().content())
                .thenReturn("  a majestic orange cat sitting on a windowsill, soft morning light  ");

        PromptEnhancementService service = new PromptEnhancementService(chatClientBuilder, "guida", "video", "modifica", "vision", "fallback");

        assertThat(service.enhance("gatto arancione")).isEqualTo("a majestic orange cat sitting on a windowsill, soft morning light");
    }

    @Test
    void enhanceReturnsEmptyStringWhenModelReturnsNull() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(chatClientBuilder.defaultSystem(anyString()).build()
                .prompt().user(anyString()).call().content())
                .thenReturn(null);

        PromptEnhancementService service = new PromptEnhancementService(chatClientBuilder, "guida", "video", "modifica", "vision", "fallback");

        assertThat(service.enhance("gatto arancione")).isEmpty();
    }

    @Test
    void isRefusalRecognisesTypicalRefusalsAndEmptyOutput() {
        assertThat(PromptEnhancementService.isRefusal("I'm sorry, I can't help with that.")).isTrue();
        assertThat(PromptEnhancementService.isRefusal("I cannot describe this image")).isTrue();
        assertThat(PromptEnhancementService.isRefusal("  ")).isTrue();
        assertThat(PromptEnhancementService.isRefusal(null)).isTrue();
        assertThat(PromptEnhancementService.isRefusal("The camera slowly dollies in as her hair sways in the breeze.")).isFalse();
    }

    @Test
    void enhanceVideoWithoutImageUsesTheTextModel() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(chatClientBuilder.defaultSystem(anyString()).build()
                .prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(" slow pan across the room ");

        PromptEnhancementService service = new PromptEnhancementService(chatClientBuilder, "guida", "video", "modifica", "vision", "fallback");

        assertThat(service.enhanceVideo("stanza", null)).isEqualTo("slow pan across the room");
    }

    @Test
    void enhanceEditWithoutImageUsesTheTextModelWithTheEditGuide() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(chatClientBuilder.defaultSystem(anyString()).build()
                .prompt().system("modifica").user(anyString()).call().content())
                .thenReturn(" Change the jacket to red, keep the face unchanged ");

        PromptEnhancementService service = new PromptEnhancementService(chatClientBuilder, "guida", "video", "modifica", "vision", "fallback");

        assertThat(service.enhanceEdit("giacca rossa", null)).isEqualTo("Change the jacket to red, keep the face unchanged");
    }
}
