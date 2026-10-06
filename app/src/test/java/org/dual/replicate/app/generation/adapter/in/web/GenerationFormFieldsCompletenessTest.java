package org.dual.replicate.app.generation.adapter.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.dual.replicate.app.generation.application.form.Flux2Klein9bParameterHandler;
import org.dual.replicate.app.generation.application.form.FluxDevLoraParameterHandler;
import org.dual.replicate.app.generation.application.form.FluxFillDevParameterHandler;
import org.dual.replicate.app.generation.application.form.FluxFillProParameterHandler;
import org.dual.replicate.app.generation.application.form.FluxKontextDevParameterHandler;
import org.dual.replicate.app.generation.application.form.FluxKreaDevParameterHandler;
import org.dual.replicate.app.generation.application.form.FluxLoraFinetuneParameterHandler;
import org.dual.replicate.app.generation.application.form.IGenerationParameterHandler;
import org.dual.replicate.app.generation.application.form.PVideoParameterHandler;
import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il requisito di "Usa configurazione" e' riproporre TUTTI i campi di un form: un campo aggiunto al fragment di un form-type e
 * dimenticato in {@code defaultFields()} verrebbe scartato in silenzio da {@code toFormFields}/{@code populateFormTypeFields}
 * (e' gia' successo ai LoRA di flux-dev-lora). Questo test lo rende un errore.
 */
class GenerationFormFieldsCompletenessTest {

    private static final Pattern NAME = Pattern.compile("\\bname=\"([^\"]+)\"");

    /** Campi del fragment che non sono parametri dell'handler: upload, seed (lo porta la generazione), aspect_ratio fisso dei fine-tune LoRA. */
    private static final Map<GenerationFormType, Set<String>> NOT_HANDLER_FIELDS = Map.of(
            GenerationFormType.FLUX_LORA_FINETUNE, Set.of("aspect_ratio"));
    private static final Set<String> NEVER_HANDLER_FIELDS = Set.of("sourceUpload", "maskUpload", "seed");

    private static final List<IGenerationParameterHandler> HANDLERS = List.of(new FluxLoraFinetuneParameterHandler(),
            new Flux2Klein9bParameterHandler(), new FluxKreaDevParameterHandler(), new PVideoParameterHandler(),
            new FluxKontextDevParameterHandler(), new FluxDevLoraParameterHandler(), new FluxFillDevParameterHandler(),
            new FluxFillProParameterHandler());

    @Test
    void everyFormTypeHasAHandlerInThisTest() {
        assertThat(HANDLERS.stream().map(IGenerationParameterHandler::formType).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(GenerationFormType.values());
    }

    @Test
    void everyNamedFieldOfAFormTypeFragmentIsAKnownHandlerField() throws IOException {
        Map<GenerationFormType, IGenerationParameterHandler> byType = HANDLERS.stream()
                .collect(Collectors.toMap(IGenerationParameterHandler::formType, Function.identity()));
        for (GenerationFormType type : GenerationFormType.values()) {
            Set<String> names = new TreeSet<>();
            Matcher matcher = NAME.matcher(fragment(type));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            names.removeAll(NEVER_HANDLER_FIELDS);
            names.removeAll(NOT_HANDLER_FIELDS.getOrDefault(type, Set.of()));
            assertThat(byType.get(type).defaultFields().keySet())
                    .as("campi del fragment di %s mancanti nei defaultFields dell'handler", type)
                    .containsAll(names);
        }
    }

    private static final Pattern MEGAPIXELS_SELECT = Pattern.compile(
            "(?s)<select[^>]*name=\"megapixels\".*?</select>");
    private static final Pattern OPTION_VALUE = Pattern.compile("<option[^>]*\\bvalue=\"([^\"]+)\"");

    /** Le opzioni della select Megapixel di ogni form coincidono con l'enum (schema Replicate) che l'handler accetta: ne' di piu' ne' di meno. */
    @Test
    void megapixelOptionsOfEveryFormMatchTheHandlerEnum() throws IOException {
        Map<GenerationFormType, Set<String>> enums = Map.of(
                GenerationFormType.FLUX_LORA_FINETUNE, FluxLoraFinetuneParameterHandler.MEGAPIXELS,
                GenerationFormType.FLUX_2_KLEIN_9B, Flux2Klein9bParameterHandler.MEGAPIXELS,
                GenerationFormType.FLUX_KREA_DEV, FluxKreaDevParameterHandler.MEGAPIXELS,
                GenerationFormType.FLUX_DEV_LORA, FluxDevLoraParameterHandler.MEGAPIXELS,
                GenerationFormType.FLUX_FILL_DEV, FluxFillDevParameterHandler.MEGAPIXELS);
        for (GenerationFormType type : GenerationFormType.values()) {
            Matcher select = MEGAPIXELS_SELECT.matcher(fragment(type));
            if (!enums.containsKey(type)) {
                assertThat(select.find()).as("%s non ha megapixels nello schema", type).isFalse();
                continue;
            }
            assertThat(select.find()).as("select megapixels di %s", type).isTrue();
            Set<String> options = new TreeSet<>();
            Matcher option = OPTION_VALUE.matcher(select.group());
            while (option.find()) {
                options.add(option.group(1));
            }
            assertThat(options).as("opzioni megapixels del fragment di %s", type).containsExactlyInAnyOrderElementsOf(enums.get(type));
        }
    }

    private static String fragment(GenerationFormType type) throws IOException {
        try (InputStream in = new ClassPathResource(GenerationFormFragments.resourceOf(type)).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
