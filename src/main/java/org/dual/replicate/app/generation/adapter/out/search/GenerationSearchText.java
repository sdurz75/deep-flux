package org.dual.replicate.app.generation.adapter.out.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.port.in.ILoraPresets.LoraView;
import org.dual.replicate.core.search.domain.DocumentTypes;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Il testo che viene embeddato per una generazione: il prompt seguito (dopo {@link DocumentTypes#TAGS_SEPARATOR}) da tag in
 * linguaggio naturale, it/en (il modello e5 e' multilingue), ricavate dai metadati della richiesta: tipo di media, modello,
 * orientamento, LoRA (e relative trigger words), generazione derivata. Servono a far trovare "un video", "immagine verticale" o
 * "flux krea" anche se il prompt non lo dice. Sono vocabolario d'indice, NON testo per l'utente. Seed, costo e passi non entrano: non
 * hanno significato e diluirebbero l'embedding.
 */
final class GenerationSearchText {

    private static final Pattern RATIO = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*:\\s*(\\d+(?:\\.\\d+)?)");

    private GenerationSearchText() {
    }

    static String of(Generation generation, List<LoraView> loraPresets, ObjectMapper objectMapper) {
        String tags = String.join(", ", tags(generation, loraPresets, objectMapper));
        String prompt = generation.getPrompt() == null ? "" : generation.getPrompt().strip();
        if (tags.isEmpty()) {
            return prompt;
        }
        // Il tetto di DocumentTypes.MAX_CHARS taglia la coda: il prompt cede spazio, le tag restano.
        int room = DocumentTypes.MAX_CHARS - DocumentTypes.TAGS_SEPARATOR.length() - tags.length();
        if (prompt.length() > room) {
            prompt = prompt.substring(0, Math.max(0, room));
        }
        return prompt + DocumentTypes.TAGS_SEPARATOR + tags;
    }

    static List<String> tags(Generation generation, List<LoraView> loraPresets, ObjectMapper objectMapper) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(generation.isVideo() ? "video, clip, filmato" : "foto, immagine, image, picture");
        if (generation.isImported()) {
            // Importata: il "prompt" e' la descrizione dell'analisi; qui la provenienza e i tag che la stessa analisi ha prodotto (it/en).
            tags.add("immagine importata, imported image");
            tags.addAll(generation.getAnalysisTagList());
        }

        String model = generation.getModel();
        if (model != null && !model.isBlank()) {
            tags.add(model);
            tags.add(model.substring(model.indexOf('/') + 1));
        }

        JsonNode parameters = parse(generation.getParametersJson(), objectMapper);
        orientation(parameters).ifPresent(tags::add);
        text(parameters, "resolution").ifPresent(resolution -> tags.add(resolution));
        loras(parameters, loraPresets, tags);

        if (generation.getSourceGenerationId() != null || generation.getSourceUploadFilename() != null) {
            tags.add(generation.isVideo() ? "animazione di un'immagine, image to video" : "modifica di un'immagine, image edit");
        }
        return new ArrayList<>(tags);
    }

    /** Verticale / orizzontale / quadrato, da {@code aspect_ratio} ("16:9") o da {@code width} x {@code height}. */
    private static Optional<String> orientation(JsonNode parameters) {
        double width = 0;
        double height = 0;
        Matcher ratio = text(parameters, "aspect_ratio").map(RATIO::matcher).filter(Matcher::matches).orElse(null);
        if (ratio != null) {
            width = Double.parseDouble(ratio.group(1));
            height = Double.parseDouble(ratio.group(2));
        } else if (parameters.path("width").isNumber() && parameters.path("height").isNumber()) {
            width = parameters.path("width").asDouble();
            height = parameters.path("height").asDouble();
        }
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }
        if (width == height) {
            return Optional.of("quadrato, square");
        }
        return Optional.of(height > width ? "verticale, portrait" : "orizzontale, landscape");
    }

    /**
     * Il nome corto di ogni LoRA ({@code lora_weights}/{@code extra_lora}: ultimo segmento di {@code owner/nome} o dell'URL, senza
     * estensione) e, se la sorgente coincide con un preset anagrafato, il suo nome e le trigger words.
     */
    private static void loras(JsonNode parameters, List<LoraView> presets, Set<String> tags) {
        for (String key : List.of("lora_weights", "extra_lora")) {
            String source = text(parameters, key).orElse(null);
            if (source == null) {
                continue;
            }
            tags.add("LoRA " + shortName(source));
            for (LoraView preset : presets) {
                if (source.equals(preset.source())) {
                    tags.add(preset.name());
                    if (preset.triggerWords() != null && !preset.triggerWords().isBlank()) {
                        tags.add(preset.triggerWords().strip());
                    }
                }
            }
        }
    }

    static String shortName(String source) {
        String name = source.strip();
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.toLowerCase(Locale.ROOT).endsWith(".safetensors") ? name.substring(0, name.length() - ".safetensors".length()) : name;
    }

    private static Optional<String> text(JsonNode parameters, String key) {
        JsonNode node = parameters.path(key);
        return node.isString() && !node.asString().isBlank() ? Optional.of(node.asString().strip()) : Optional.empty();
    }

    private static JsonNode parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            return objectMapper.createObjectNode();
        }
    }
}
