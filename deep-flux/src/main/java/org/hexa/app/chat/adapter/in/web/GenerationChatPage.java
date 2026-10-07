package org.hexa.app.chat.adapter.in.web;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.hexa.core.chat.port.in.IChatPageContributor;
import org.hexa.app.generation.domain.GalleryItem;
import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.GenerationKind;
import org.hexa.app.generation.domain.ReplicateModel;
import org.hexa.app.generation.port.in.IGenerationForms;
import org.hexa.app.generation.port.in.IGenerations;
import org.hexa.app.generation.port.in.IModelCatalog;
import org.hexa.core.kernel.Paged;
import org.hexa.core.storage.port.in.IImageStorageService;
import org.springframework.stereotype.Component;

/**
 * Lato app della pagina di /deep-chat: il pannello delle impostazioni di generazione (modelli immagine, form-type del modello di
 * default, limiti di upload e di {@code num_outputs} che i fragment dei form-type leggono anche nel pannello della chat), i
 * placeholder delle generazioni ancora in corso della conversazione e la galleria contestuale.
 */
@Component
class GenerationChatPage implements IChatPageContributor {

    private final IModelCatalog modelCatalog;
    private final IGenerationForms forms;
    private final IGenerations generations;

    GenerationChatPage(IModelCatalog modelCatalog, IGenerationForms forms, IGenerations generations) {
        this.modelCatalog = modelCatalog;
        this.forms = forms;
        this.generations = generations;
    }

    @Override
    public Map<String, Object> pageAttributes(Long conversationId) {
        Map<String, Object> attributes = new HashMap<>();
        // Limite dell'upload sorgente: i fragment dei form-type con un campo upload (flux-lora-finetune, flux-dev-lora) lo leggono anche
        // quando sono nel pannello della chat, dove il blocco e' nascosto ma il markup viene comunque renderizzato.
        attributes.put("maxNumOutputs", IGenerationForms.MAX_NUM_OUTPUTS);
        attributes.put("maxUploadBytes", IImageStorageService.MAX_UPLOAD_BYTES);
        // Placeholder da ripristinare: generazioni di QUESTA conversazione ancora in corso (vedi deep-chat.html).
        attributes.put("pendingGenerationIds", generations.inProgressForConversation(conversationId).stream().map(Generation::getId).toList());
        Paged<GalleryItem> contextual = generations.succeededItemsForConversationPage(conversationId, 0, ChatGalleryController.PAGE_SIZE);
        attributes.put("contextualItems", contextual.content());
        attributes.put("contextualNextPage", contextual.hasNext() ? 2 : null);

        // Solo modelli immagine: il tool di chat genera immagini (i video passano da /generations/new).
        attributes.put("models", modelCatalog.models(GenerationKind.IMAGE));
        Optional<ReplicateModel> defaultModel = modelCatalog.defaultModel();
        attributes.put("model", defaultModel.map(ReplicateModel::getIdentifier).orElse(""));
        attributes.put("formType", defaultModel.map(m -> m.getFormType().name()).orElse(null));
        defaultModel.ifPresent(m -> attributes.putAll(forms.formModel(m.getFormType())));
        return attributes;
    }
}
