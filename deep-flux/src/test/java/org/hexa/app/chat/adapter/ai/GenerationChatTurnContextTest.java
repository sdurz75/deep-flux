package org.hexa.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hexa.core.chat.domain.ChatTurnContext;
import org.hexa.app.generation.domain.GenerationFormType;
import org.hexa.app.generation.port.in.IGenerationForms;
import org.hexa.app.generation.port.in.IModelCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Le impostazioni opache del client diventano le chiavi di ToolContext che leggono i tool dell'app. */
class GenerationChatTurnContextTest {

    private final IModelCatalog catalog = mock(IModelCatalog.class);
    private final IGenerationForms forms = mock(IGenerationForms.class);
    private final GenerationChatTurnContext contributor = new GenerationChatTurnContext(catalog, forms);

    @Test
    void modelAndParametersBecomeToolContextAndASystemNote() {
        GenerationFormType formType = GenerationFormType.values()[0];
        when(catalog.formTypeOf("owner/model")).thenReturn(Optional.of(formType));
        when(forms.parameters(eq(formType), eq(Map.of("aspect_ratio", "1:1", "num_outputs", "2")))).thenReturn(Map.of("aspect_ratio", "1:1"));

        Map<String, Object> context = contributor.contribute(Map.of("model", "owner/model", "parameters", Map.of("aspect_ratio", "1:1", "num_outputs", 2)));

        assertThat(context).containsEntry(ImageGenerationTool.MODEL_CONTEXT_KEY, "owner/model")
                .containsEntry(ImageGenerationTool.PARAMETERS_CONTEXT_KEY, Map.of("aspect_ratio", "1:1"));
        assertThat(context.get(ChatTurnContext.SYSTEM_NOTES)).isEqualTo(List.of("Image generation model currently selected in the UI: owner/model"));
    }

    @Test
    void withoutASelectedModelNothingIsSentAndNoNoteIsAdded() {
        when(catalog.formTypeOf(any())).thenReturn(Optional.empty());

        Map<String, Object> context = contributor.contribute(Map.of());

        assertThat(context).containsEntry(ImageGenerationTool.PARAMETERS_CONTEXT_KEY, Map.of())
                .doesNotContainKeys(ImageGenerationTool.MODEL_CONTEXT_KEY, ChatTurnContext.SYSTEM_NOTES);
    }
}
