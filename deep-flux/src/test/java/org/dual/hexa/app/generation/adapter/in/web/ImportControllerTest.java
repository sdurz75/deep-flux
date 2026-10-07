package org.dual.hexa.app.generation.adapter.in.web;

import java.util.List;

import org.dual.hexa.app.generation.domain.AnalysisStatus;
import org.dual.hexa.app.generation.domain.GalleryItem;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.ai.llm.domain.ImageAnalysisException;
import org.dual.hexa.ai.llm.domain.ImageDescription;
import org.dual.hexa.ai.llm.port.in.IImageDescriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importazione di immagini esterne end-to-end sul web: form multipart (il primo test multipart del repo), esito per file, analisi in background
 * col modello di visione MOCKATO (mai la rete vera), dettaglio, galleria, selettore dell'archivio e sorgente img2img.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportControllerTest {

    /** Magic bytes PNG + qualche byte: basta a {@code IImageStorageService#storeUpload}, che guarda solo l'intestazione. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @MockitoBean
    private IImageDescriber describer;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IGenerations generations;

    @AfterEach
    void cleanUp() {
        // Solo le importate: lo stesso DB e' condiviso con gli altri test.
        generations.importedPage(0, 100).content().forEach(item -> generations.delete(item.generation().getId()));
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("images", name, "image/png", PNG);
    }

    private String importOne() throws Exception {
        return mockMvc.perform(multipart("/import").file(png("foto.png")).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private Generation firstImported() {
        List<GalleryItem> items = generations.importedPage(0, 10).content();
        assertThat(items).isNotEmpty();
        return items.get(0).generation();
    }

    private void awaitAnalysis(Long id, AnalysisStatus expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (generations.get(id).getAnalysisStatus() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(generations.get(id).getAnalysisStatus()).isEqualTo(expected);
    }

    @Test
    void thePageShowsTheMultipartFormAndTheDropzone() throws Exception {
        String page = mockMvc.perform(get("/import")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Importa immagini").contains("name=\"images\"").contains("multiple")
                .contains("enctype=\"multipart/form-data\"").contains("imageDropzone").contains("data-max-files=\"20\"")
                .contains("Ultime importate").containsPattern("aria-current=\"page\"[^>]*>Importa immagini<");
    }

    @Test
    void importingFilesGivesAPerFileOutcomeAndCreatesImportedRows() throws Exception {
        when(describer.describe(any())).thenReturn(new ImageDescription("Un gatto rosso.", List.of("gatto", "cat")));

        String result = mockMvc.perform(multipart("/import").file(png("foto.png"))
                        .file(new MockMultipartFile("images", "note.txt", "text/plain", "ciao".getBytes()))
                        .header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(result).contains("1 importate, 1 rifiutate").contains("foto.png").contains("note.txt").doesNotContain("<html");
        Generation imported = firstImported();
        assertThat(imported.isImported()).isTrue();
        assertThat(imported.getModel()).isNull();
        // L'analisi parte in background dopo l'importazione e salva la descrizione come "prompt".
        awaitAnalysis(imported.getId(), AnalysisStatus.DONE);
        assertThat(generations.get(imported.getId()).getPrompt()).isEqualTo("Un gatto rosso.");
    }

    @Test
    void aNativeFormSubmitAnswersWithTheWholePage() throws Exception {
        when(describer.describe(any())).thenReturn(new ImageDescription("x", List.of()));

        String page = mockMvc.perform(multipart("/import").file(png("foto.png"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("<html").contains("1 importate, 0 rifiutate").contains("Ultime importate");
    }

    @Test
    void submittingNoFileSaysSo() throws Exception {
        String result = mockMvc.perform(post("/import").header("HX-Request", "true").contentType("multipart/form-data; boundary=x"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(result).contains("Nessun file selezionato");
    }

    @Test
    void aFailedAnalysisShowsTheRetryButtonAndRetryRestartsIt() throws Exception {
        when(describer.describe(any())).thenThrow(new ImageAnalysisException("rifiuto", null));
        importOne();
        Long id = firstImported().getId();
        awaitAnalysis(id, AnalysisStatus.FAILED);

        String box = mockMvc.perform(get("/import/" + id + "/analysis")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(box).contains("id=\"analysis-" + id + "\"").contains("Riprova l&#39;analisi").contains("/import/" + id + "/retry")
                .doesNotContain("every 3s");

        doReturn(new ImageDescription("Ora si.", List.of("si"))).when(describer).describe(any());
        mockMvc.perform(post("/import/" + id + "/retry")).andExpect(status().isOk());
        awaitAnalysis(id, AnalysisStatus.DONE);
        String done = mockMvc.perform(get("/import/" + id + "/analysis")).andReturn().getResponse().getContentAsString();
        assertThat(done).contains("Ora si.").contains(">si<").doesNotContain("Riprova l&#39;analisi");
    }

    @Test
    void thePendingAnalysisBoxPollsItselfUntilDone() throws Exception {
        // Il modello non risponde mai in tempo: la riga resta PENDING e il riquadro si ricarica da solo.
        when(describer.describe(any())).thenAnswer(call -> {
            Thread.sleep(1500);
            return new ImageDescription("tardi", List.of());
        });
        importOne();
        Long id = firstImported().getId();

        String box = mockMvc.perform(get("/import/" + id + "/analysis")).andReturn().getResponse().getContentAsString();

        assertThat(box).contains("Analisi del contenuto in corso").contains("every 3s").contains("/import/" + id + "/analysis");
        awaitAnalysis(id, AnalysisStatus.DONE);
    }

    /** Il dettaglio di un'immagine importata e' una pagina propria: titolo "Immagine importata", analisi al posto di prompt/modello/costo. */
    @Test
    void theDetailOfAnImportedImageIsItsOwnPageWithTheAnalysisAndNoGenerationData() throws Exception {
        when(describer.describe(any())).thenReturn(new ImageDescription("Un gatto rosso su un divano.", List.of("gatto", "cat")));
        importOne();
        Generation imported = firstImported();
        awaitAnalysis(imported.getId(), AnalysisStatus.DONE);

        String detail = mockMvc.perform(get("/import/" + imported.getId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(detail).contains("<title>Immagine importata #" + imported.getId()).contains("<h1>Immagine importata #" + imported.getId())
                .contains("Analisi del contenuto").contains("Un gatto rosso su un divano.").contains("Importata il")
                .contains("Usa la descrizione come prompt")
                .containsPattern("aria-current=\"page\"[^>]*>Immagine importata #" + imported.getId())
                .contains("href=\"/import\"")
                .doesNotContain("Generazione #").doesNotContain("Usa configurazione").doesNotContain("Costo stimato").doesNotContain(">null<")
                .doesNotContain("data-shared-seed=\"");
        // E' una sorgente valida per img2img, video e modifica.
        assertThat(detail).contains("source=" + imported.getId());
    }

    /** I vecchi link a /generations/{id} (chat, ricerca, eventi) di una importata portano al suo dettaglio; il contrario per una generata. */
    @Test
    void theGenerationUrlOfAnImportedImageRedirectsToItsOwnPage() throws Exception {
        when(describer.describe(any())).thenReturn(new ImageDescription("x", List.of()));
        importOne();
        Generation imported = firstImported();

        mockMvc.perform(get("/generations/" + imported.getId())).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/import/" + imported.getId()));
        mockMvc.perform(get("/import/999999999")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/import"));
    }

    @Test
    void theGalleryHasAnImportedTabAndLabelsTheCard() throws Exception {
        when(describer.describe(any())).thenReturn(new ImageDescription("x", List.of()));
        importOne();
        Generation imported = firstImported();

        String tab = mockMvc.perform(get("/gallery").param("tab", "imported").header("HX-Request", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(tab).contains("Importate").contains("Importata").contains("/import/" + imported.getId())
                .contains("source=" + imported.getId() + "&amp;sourceImage=" + imported.getImageFilenames().get(0) + "&amp;kind=image".replace("&amp;kind=image", ""))
                .doesNotContain("Nessuna immagine importata");
        String all = mockMvc.perform(get("/gallery").header("HX-Request", "true")).andReturn().getResponse().getContentAsString();
        assertThat(all).contains("Usa come sorgente");
    }

    @Test
    void theArchivePickerListsImagesAndLinksToTheFormWithTheSource() throws Exception {
        when(describer.describe(any())).thenReturn(new ImageDescription("x", List.of()));
        importOne();
        Generation imported = firstImported();
        String file = imported.getImageFilenames().get(0);

        String picker = mockMvc.perform(get("/gallery/picker").param("tab", "imported").param("kind", "image"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(picker).contains("/images/" + file).contains("source=" + imported.getId()).contains("sourceImage=" + file)
                .contains("kind=image").contains("hx-target=\"#archive-picker\"");
    }

    /** "Usa come sorgente": la pagina immagine con la sorgente scelta, su un modello immagine che la prende (ff3), senza il campo upload. */
    @Test
    void useAsSourceOpensTheImagePageWithTheArchiveImageAsSource() throws Exception {
        when(describer.describe(any())).thenReturn(new ImageDescription("Un gatto rosso.", List.of()));
        importOne();
        Generation imported = firstImported();
        awaitAnalysis(imported.getId(), AnalysisStatus.DONE);
        String file = imported.getImageFilenames().get(0);

        String page = mockMvc.perform(get("/generations/new").param("kind", "image").param("source", String.valueOf(imported.getId()))
                        .param("sourceImage", file))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("name=\"sourceGenerationId\"").contains("value=\"" + imported.getId() + "\"")
                .contains("name=\"sourceImage\"").contains("/images/" + file).contains("Scegli dall&#39;archivio")
                .doesNotContain("id=\"param-source-upload\"").contains("name=\"flux_model\"");
        // La descrizione dell'immagine importata e' il prompt di partenza.
        assertThat(page).contains("Un gatto rosso.");
    }

    @Test
    void theUploadFieldOffersThePickerToo() throws Exception {
        String page = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-dev-lora"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("id=\"param-source-upload\"").contains("Scegli dall&#39;archivio").contains("/gallery/picker?kind=image");
    }
}
