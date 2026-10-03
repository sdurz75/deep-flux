package org.dual.replicate.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.port.out.IChatConversationStore;
import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.domain.ReplicateModel;
import org.dual.replicate.app.generation.port.in.IGenerationForms;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ogni campo numerico dei parametri di generazione ha il reset al default (fragments/app/generation-params-number.html): passa dal
 * fragment condiviso e porta in {@code data-default} il default del suo handler. Il seed ha il proprio reset (default "vuoto").
 */
@SpringBootTest
@AutoConfigureMockMvc
class NumericResetFieldsTests {

    private static final Path FRAGMENTS = Path.of("src/main/resources/templates/fragments/app");
    private static final Pattern NUMBER_INPUT = Pattern.compile("<input\\b[^>]*type=\"number\"[^>]*>");
    private static final Pattern NAME = Pattern.compile("\\bname=\"([^\"]+)\"");
    private static final Pattern DATA_DEFAULT = Pattern.compile("\\bdata-default=\"([^\"]*)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IModelCatalog modelCatalog;

    @Autowired
    private IGenerationForms forms;

    @Autowired
    private IChatConversationStore chatConversationRepository;

    /** Nessun {@code <input type="number">} a mano nei fragment dei form-type: solo il fragment condiviso e il seed li scrivono. */
    @Test
    void everyNumericParameterFieldUsesTheSharedResetFragment() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(FRAGMENTS)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("generation-params-")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("generation-params-number.html") || name.equals("generation-params-seed.html")) {
                    continue;
                }
                if (NUMBER_INPUT.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    offenders.add(name);
                }
            }
        }
        assertThat(offenders).as("fragment con un input numerico fuori da generation-params-number.html").isEmpty();
    }

    /** Per ogni modello censito, ogni numerico (seed escluso) ha un {@code data-default} pari al default del suo handler. */
    @Test
    @Transactional
    void everyNumericFieldOfEveryModelCarriesItsHandlerDefault() throws Exception {
        int checked = 0;
        for (ReplicateModel model : modelCatalog.models()) {
            String html = mockMvc.perform(get("/generations/params").param("model", model.getIdentifier()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Map<String, Object> defaults = forms.defaultFields(model.getFormType());
            checked += assertNumericDefaults(model.getFormType(), html, defaults);
        }
        assertThat(checked).as("campi numerici controllati").isGreaterThan(20);
    }

    /** Il primo render del pannello della chat (percorso {@code IGenerationForms#formModel}) porta gli stessi default. */
    @Test
    @Transactional
    void deepChatPanelCarriesTheDefaultsToo() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        String html = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        GenerationFormType type = modelCatalog.defaultModel().orElseThrow().getFormType();

        assertThat(assertNumericDefaults(type, html, forms.defaultFields(type))).isGreaterThan(3);
    }

    /** Il bottone non e' un submit (il form spende su Replicate), e un attributo opzionale a null non lascia {@code min=""}. */
    @Test
    @Transactional
    void resetButtonIsNeverASubmitAndNullAttributesAreOmitted() throws Exception {
        String finetune = mockMvc.perform(get("/generations/params").param("model", "sdurz75/flux-lora-ff3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(finetune).containsPattern("<button[^>]*type=\"button\"[^>]*x-show=\"changed\"");
        assertThat(finetune).doesNotContain("min=\"\"").doesNotContain("step=\"\"").doesNotContain("max=\"\"");
        // I passi dei fine-tune LoRA restano leggibili dal select per id e portano le classi readonly letterali.
        assertThat(finetune).containsPattern("(?s)<input[^>]*id=\"param-steps\"[^>]*data-default=\"28\"")
                .contains("read-only:opacity-60 read-only:cursor-not-allowed");
        assertThat(finetune).doesNotContain("$refs.stepsInput");
    }

    private static int assertNumericDefaults(GenerationFormType type, String html, Map<String, Object> defaults) {
        int checked = 0;
        Matcher inputs = NUMBER_INPUT.matcher(html);
        while (inputs.find()) {
            String tag = inputs.group();
            Matcher name = NAME.matcher(tag);
            assertThat(name.find()).isTrue();
            if (name.group(1).equals("seed")) {
                continue;
            }
            Matcher dataDefault = DATA_DEFAULT.matcher(tag);
            assertThat(dataDefault.find()).as("%s: %s senza data-default", type, name.group(1)).isTrue();
            assertThat(dataDefault.group(1)).as("%s: data-default di %s", type, name.group(1)).isNotBlank();
            Object expected = defaults.get(name.group(1));
            assertThat(expected).as("%s: %s non e' nei defaultFields", type, name.group(1)).isNotNull();
            assertThat(Double.parseDouble(dataDefault.group(1))).as("%s: default di %s", type, name.group(1))
                    .isEqualTo(Double.parseDouble(String.valueOf(expected)));
            checked++;
        }
        return checked;
    }

    /**
     * I campi "intensita'" e la guidance (lora_scale, extra_lora_scale, prompt_strength, guidance, guidance_scale) hanno i bottoni -/+ ({@code button-gen :: stepNumber}), uno per lato e
     * uno per campo, ovunque compaiano; gli altri numerici (passi, dimensioni...) no.
     */
    @Test
    @Transactional
    void strengthFieldsHaveStepButtonsAndOthersDoNot() throws Exception {
        java.util.regex.Pattern strength = java.util.regex.Pattern.compile("<input[^>]*name=\"(lora_scale|extra_lora_scale|prompt_strength|guidance|guidance_scale)\"");
        int strengthFields = 0;
        for (ReplicateModel model : modelCatalog.models()) {
            String html = mockMvc.perform(get("/generations/params").param("model", model.getIdentifier()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            int expected = (int) strength.matcher(html).results().count();
            strengthFields += expected;
            assertThat(html.split("data-direction=\"up\"", -1).length - 1).as(model.getIdentifier() + " +").isEqualTo(expected);
            assertThat(html.split("data-direction=\"down\"", -1).length - 1).as(model.getIdentifier() + " -").isEqualTo(expected);
        }
        assertThat(strengthFields).as("campi intensita' controllati").isGreaterThan(4);
    }
}
