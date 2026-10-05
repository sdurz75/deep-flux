package org.dual.replicate.app.chat.adapter.ai;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il system prompt della chat sui testi VERI di {@code prompts.properties} (letti col parser di Boot, senza contesto Spring): ha un tetto
 * di lunghezza (ogni sezione in piu' lo allunga a ogni turno), e ogni sezione nomina solo tool che in quel momento esistono. Le sezioni dei
 * gruppi condizionali (archivio e note, solo con {@code app.search.enabled}) non possono essere citate da quelle sempre presenti.
 */
class ChatPromptTest {

    /** Tetto del prompt completo (tutti i gruppi presenti), in caratteri: ~2.500 token. */
    private static final int MAX_PROMPT_CHARS = 10_000;

    private static final List<Class<? extends ChatToolkit>> TOOLKITS = List.of(WebSearchTool.class, LibraryTool.class,
            ManualTool.class, ArchiveSearchTool.class, CurationTool.class, ActionProposalTool.class, NoteTool.class, CreditsTool.class, VisionTool.class,
            ImageGenerationTool.class);

    /** Sezioni sempre presenti, che non appartengono a un toolkit. */
    private static final List<String> ALWAYS = List.of("deep-chat.section.core", "deep-chat.section.guidance", "deep-chat.section.appmap");

    private static Map<String, Object> prompts() throws IOException {
        Map<String, Object> all = new LinkedHashMap<>();
        for (PropertySource<?> source : new PropertiesPropertySourceLoader().load("prompts", new ClassPathResource("prompts.properties"))) {
            for (String name : ((org.springframework.core.env.EnumerablePropertySource<?>) source).getPropertyNames()) {
                all.put(name, source.getProperty(name));
            }
        }
        return all;
    }

    /** Il testo come lo vede il modello (le proprieta' sono stringhe gia' risolte: nessun placeholder deve restare). */
    private static String text(Map<String, Object> prompts, String key) {
        Object value = prompts.get(key);
        assertThat(value).as(key).isNotNull();
        return String.valueOf(value);
    }

    private static String sectionOf(Class<? extends ChatToolkit> toolkit) {
        // promptSection() non dipende dallo stato: i toolkit si istanziano senza collaboratori solo per leggere la chiave.
        try {
            return ((ChatToolkit) org.springframework.objenesis.ObjenesisHelper.newInstance(toolkit)).promptSection();
        } catch (RuntimeException e) {
            throw new IllegalStateException(toolkit.getSimpleName(), e);
        }
    }

    private static Set<String> toolNames(Class<?> toolkit) {
        return Stream.of(toolkit.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Tool.class)).map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean conditional(Class<?> toolkit) {
        return toolkit.isAnnotationPresent(ConditionalOnProperty.class);
    }

    @Test
    void theFullPromptStaysUnderTheCap() throws IOException {
        Map<String, Object> prompts = prompts();
        List<String> keys = new ArrayList<>(ALWAYS);
        TOOLKITS.stream().map(ChatPromptTest::sectionOf).filter(Objects::nonNull).forEach(keys::add);
        keys.add("prompts.creative-context");
        keys.add("deep-chat.image-prompting-guide");

        String prompt = keys.stream().map(key -> text(prompts, key)).collect(Collectors.joining("\n\n"));

        assertThat(prompt).doesNotContain("${");
        assertThat(prompt.length()).as("system prompt length").isLessThanOrEqualTo(MAX_PROMPT_CHARS);
    }

    /** Ogni toolkit con una sezione ne ha il testo, e ogni sezione `deep-chat.section.*` e' di un toolkit o sempre presente (nessuna orfana). */
    @Test
    void everySectionBelongsToAToolkitOrIsAlwaysPresent() throws IOException {
        Map<String, Object> prompts = prompts();
        Set<String> owned = TOOLKITS.stream().map(ChatPromptTest::sectionOf).filter(Objects::nonNull).collect(Collectors.toSet());
        assertThat(owned).allSatisfy(key -> assertThat(prompts).containsKey(key));

        Set<String> defined = prompts.keySet().stream().filter(k -> k.startsWith("deep-chat.section.")).collect(Collectors.toSet());
        Set<String> expected = new TreeSet<>(owned);
        expected.addAll(ALWAYS);
        assertThat(defined).isEqualTo(expected);
    }

    /** Nessun testo sempre presente puo' nominare un tool di un gruppo condizionale: senza ricerca il modello ne cercherebbe uno che non ha. */
    @Test
    void alwaysPresentTextsNameOnlyAlwaysPresentTools() throws IOException {
        Map<String, Object> prompts = prompts();
        Set<String> conditionalTools = TOOLKITS.stream().filter(ChatPromptTest::conditional).flatMap(t -> toolNames(t).stream())
                .collect(Collectors.toSet());
        assertThat(conditionalTools).containsExactlyInAnyOrder("searchArchive", "saveNote");

        List<String> alwaysPresentKeys = new ArrayList<>(ALWAYS);
        TOOLKITS.stream().filter(t -> !conditional(t)).map(ChatPromptTest::sectionOf).filter(Objects::nonNull).forEach(alwaysPresentKeys::add);
        alwaysPresentKeys.add("prompts.creative-context");
        alwaysPresentKeys.add("deep-chat.image-prompting-guide");

        for (String key : alwaysPresentKeys) {
            String text = text(prompts, key);
            assertThat(conditionalTools.stream().filter(text::contains).toList()).as(key).isEmpty();
        }
    }

    /** Un nome di tool scritto in un testo (parola intera) deve esistere davvero fra i tool registrati: un tool rinominato non lascia riferimenti morti. */
    @Test
    void everyToolNameMentionedInTheSectionsExists() throws IOException {
        Map<String, Object> prompts = prompts();
        Set<String> real = TOOLKITS.stream().flatMap(t -> toolNames(t).stream()).collect(Collectors.toSet());
        // Nomi con l'aspetto di un tool (camelCase con prefisso verbo) che i testi citano: devono essere reali.
        java.util.regex.Pattern verbNames = java.util.regex.Pattern.compile(
                "\\b(?:search|generate|list|get|set|propose|save|describe|rename|recent|conversation|read)[A-Z]\\w*");
        for (Map.Entry<String, Object> entry : prompts.entrySet()) {
            if (!entry.getKey().startsWith("deep-chat.")) {
                continue;
            }
            java.util.regex.Matcher matcher = verbNames.matcher(String.valueOf(entry.getValue()));
            while (matcher.find()) {
                assertThat(real).as(entry.getKey() + " mentions " + matcher.group()).contains(matcher.group());
            }
        }
        // E nei testi c'e' un tool di OGNI toolkit (nessun gruppo resta senza spiegazione): la sezione ne nomina almeno uno.
        for (Class<? extends ChatToolkit> toolkit : TOOLKITS) {
            String section = sectionOf(toolkit);
            if (section == null) {
                continue;
            }
            String text = text(prompts, section);
            assertThat(toolNames(toolkit).stream().anyMatch(text::contains)).as(section + " names a tool of " + toolkit.getSimpleName()).isTrue();
        }
    }

    /** I punti fermi: la guida non cita modelli che non ci sono ne' un modello "richiesto", e la contraddizione sui soldi e' sparita. */
    @Test
    void theGuidesDoNotNameModelsAndDoNotPromiseAButtonForPaidGeneration() throws IOException {
        Map<String, Object> prompts = prompts();
        String guide = text(prompts, "deep-chat.image-prompting-guide");
        assertThat(guide).doesNotContain("flux-schnell").doesNotContain("sdxl").doesNotContain("requested model");
        assertThat(text(prompts, "deep-chat.section.guidance")).doesNotContain("anything that deletes, stops or costs money");
        assertThat(text(prompts, "deep-chat.section.generation")).contains("confirms that exact prompt");
    }
}
