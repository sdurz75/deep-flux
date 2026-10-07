package org.hexa.app.training.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import org.hexa.app.training.domain.CaptionSource;
import org.hexa.app.training.domain.CaptionStatus;
import org.hexa.app.training.domain.LoraType;
import org.hexa.app.training.domain.TrainingDataset;
import org.hexa.app.training.domain.TrainingImage;
import org.hexa.app.training.port.in.ICaptionJobs;
import org.hexa.app.training.port.in.ITrainingCaptions;
import org.hexa.app.training.port.in.ITrainingDatasets;
import org.hexa.app.training.port.out.ITrainingDatasetStore;
import org.hexa.core.storage.domain.UploadedFile;
import org.hexa.core.storage.port.in.IImageStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dataset di addestramento sul web: elenco con form di creazione, editor, caricamento e rimozione delle immagini (htmx e nativo), clone, eliminazione,
 * sola lettura di uno snapshot. Lo storage e' MOCKATO: nessun file scritto in {@code data/images}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrainingControllerTest {

    /** Magic bytes PNG: basta al controller, che non guarda il contenuto (lo storage e' mockato). */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @MockitoBean
    private IImageStorageService storage;
    /**
     * Il lavoro di didascalia gira in un thread in background e chiamerebbe il modello di visione vero: qui e' un mock, cosi' le immagini restano nello stato
     * in cui le lascia il test (le azioni dell'utente, {@link ITrainingCaptions}, sono invece quelle vere).
     */
    @MockitoBean
    private ICaptionJobs captionJobs;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ITrainingDatasets datasets;
    @Autowired
    private ITrainingCaptions captions;
    @Autowired
    private ITrainingDatasetStore store;

    private final AtomicInteger names = new AtomicInteger();

    @BeforeEach
    void storageStubs() {
        when(storage.storeUpload(any(UploadedFile.class))).thenAnswer(i -> "file-" + names.incrementAndGet() + ".png");
        when(storage.copy(anyString())).thenAnswer(i -> "copy-of-" + i.getArgument(0));
    }

    @AfterEach
    void cleanUp() {
        // Le tabelle sono solo di questa feature, ma il DB e' condiviso con gli altri test: si lascia come si e' trovato.
        store.deleteAll();
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("images", name, "image/png", PNG);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private TrainingDataset newDataset() {
        return datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null);
    }

    // --- elenco e creazione -------------------------------------------------------------------------------------

    @Test
    void thePageShowsTheCreateFormAndAnEmptyList() throws Exception {
        String page = body(mockMvc.perform(get("/trainings")).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Addestra un LoRA").contains("Nuovo dataset").contains("name=\"triggerWord\"").contains("value=\"TOK\"")
                .contains("name=\"loraType\"").contains("Nessun dataset ancora")
                .containsPattern("aria-current=\"page\"[^>]*>Addestra un LoRA<");
    }

    @Test
    void creatingADatasetRedirectsToItsEditor() throws Exception {
        mockMvc.perform(post("/trainings/datasets").param("name", "  Stile acquerello ").param("triggerWord", "WTRCLR").param("loraType", "style"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("/trainings/datasets/*"));

        TrainingDataset created = datasets.page(0, 10).content().get(0);
        assertThat(created.getName()).isEqualTo("Stile acquerello");
        assertThat(created.getLoraType()).isEqualTo(LoraType.STYLE);
        assertThat(created.getTriggerWord()).isEqualTo("WTRCLR");
    }

    @Test
    void anInvalidCreationReRendersThePageWithTheMessageAndTheTypedValues() throws Exception {
        String page = body(mockMvc.perform(post("/trainings/datasets").param("name", " ").param("triggerWord", "due parole").param("loraType", "style"))
                .andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Serve un nome per il dataset").contains("value=\"due parole\"").contains("<html");
        assertThat(datasets.page(0, 10).isEmpty()).isTrue();
    }

    @Test
    void theListShowsEachDraftWithItsConfigurationAndImageCount() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));

        String page = body(mockMvc.perform(get("/trainings")).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Il mio gatto").contains("TOKCAT").contains("Soggetto").contains("Immagini: 1")
                .contains("/trainings/datasets/" + dataset.getId()).contains("/trainings/datasets/" + dataset.getId() + "/duplicate")
                .contains("/trainings/datasets/" + dataset.getId() + "/delete").doesNotContain("Nessun dataset ancora");
    }

    @Test
    void paginationAnswersWithTheBareContentForHtmx() throws Exception {
        newDataset();

        String fragment = body(mockMvc.perform(get("/trainings").header("HX-Request", "true")).andExpect(status().isOk()).andReturn());

        assertThat(fragment).contains("Il mio gatto").doesNotContain("<html").doesNotContain("Nuovo dataset");
    }

    // --- editor -------------------------------------------------------------------------------------------------

    @Test
    void theEditorShowsTheConfigurationTheDropzoneAndTheRemainingRoom() throws Exception {
        TrainingDataset dataset = newDataset();

        String page = body(mockMvc.perform(get("/trainings/datasets/" + dataset.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("value=\"Il mio gatto\"").contains("value=\"TOKCAT\"").contains("imageDropzone")
                .contains("enctype=\"multipart/form-data\"").contains("data-max-files=\"25\"").contains("0 di 25 immagini")
                .contains("Nessuna immagine ancora")
                .containsPattern("aria-current=\"page\"[^>]*>Il mio gatto<").contains("href=\"/trainings\"");
    }

    @Test
    void anUnknownDatasetSendsYouBackToTheList() throws Exception {
        mockMvc.perform(get("/trainings/datasets/999999")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/trainings"));
    }

    @Test
    void savingTheConfigurationRedirectsBackToTheEditor() throws Exception {
        TrainingDataset dataset = newDataset();

        mockMvc.perform(post("/trainings/datasets/" + dataset.getId()).param("name", "Rinominato").param("triggerWord", "NUOVA")
                        .param("loraType", "style").param("note", "una nota"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/trainings/datasets/" + dataset.getId()));

        TrainingDataset saved = datasets.get(dataset.getId());
        assertThat(saved.getName()).isEqualTo("Rinominato");
        assertThat(saved.getTriggerWord()).isEqualTo("NUOVA");
        assertThat(saved.getNote()).isEqualTo("una nota");
    }

    @Test
    void anInvalidConfigurationShowsTheMessageKeepsWhatWasTypedAndSavesNothing() throws Exception {
        TrainingDataset dataset = newDataset();

        String page = body(mockMvc.perform(post("/trainings/datasets/" + dataset.getId()).param("name", "Troppo bello")
                        .param("triggerWord", "con spazio").param("loraType", "subject").param("note", ""))
                .andExpect(status().isOk()).andReturn());

        assertThat(page).contains("una sola parola").contains("value=\"con spazio\"").contains("value=\"Troppo bello\"");
        assertThat(datasets.get(dataset.getId()).getName()).isEqualTo("Il mio gatto");
    }

    // --- immagini -----------------------------------------------------------------------------------------------

    @Test
    void uploadingWithHtmxAnswersWithTheImagesFragmentAndPerFileOutcome() throws Exception {
        TrainingDataset dataset = newDataset();

        String fragment = body(mockMvc.perform(multipart("/trainings/datasets/" + dataset.getId() + "/images").file(png("foto.png"))
                        .file(png("altra.png")).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());

        assertThat(fragment).contains("2 aggiunte, 0 rifiutate").contains("2 di 25 immagini").contains("foto.png").doesNotContain("<html");
        assertThat(datasets.get(dataset.getId()).getImages()).hasSize(2);
    }

    @Test
    void aFileOverTheLimitIsRejectedWithItsReasonAndTheOthersAreKept() throws Exception {
        TrainingDataset dataset = newDataset();
        for (int i = 0; i < 24; i++) {
            datasets.addImages(dataset.getId(), List.of(UploadedFile.of("p" + i + ".png", PNG)));
        }

        String fragment = body(mockMvc.perform(multipart("/trainings/datasets/" + dataset.getId() + "/images").file(png("entra.png"))
                        .file(png("fuori.png")).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());

        assertThat(fragment).contains("1 aggiunte, 1 rifiutate").contains("fuori.png").contains("al massimo 25 immagini").contains("25 di 25 immagini");
        assertThat(fragment).as("a dataset pieno non si offre piu' il caricamento").doesNotContain("training-upload-form");
        assertThat(datasets.get(dataset.getId()).getImages()).hasSize(25);
    }

    @Test
    void aNativeUploadAnswersWithTheWholeEditor() throws Exception {
        TrainingDataset dataset = newDataset();

        String page = body(mockMvc.perform(multipart("/trainings/datasets/" + dataset.getId() + "/images").file(png("foto.png")))
                .andExpect(status().isOk()).andReturn());

        assertThat(page).contains("<html").contains("1 aggiunte, 0 rifiutate").contains("value=\"TOKCAT\"");
    }

    @Test
    void anEmptySubmitIsNotAnError() throws Exception {
        TrainingDataset dataset = newDataset();

        String fragment = body(mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/images").contentType("multipart/form-data; boundary=x")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());

        assertThat(fragment).contains("Nessun file selezionato");
    }

    @Test
    void removingAnImageUpdatesTheFragment() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG), UploadedFile.of("b.png", PNG)));
        Long first = datasets.get(dataset.getId()).getImages().get(0).getId();

        String fragment = body(mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/images/" + first + "/delete").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());

        assertThat(fragment).contains("1 di 25 immagini").doesNotContain("training-image-" + first);
        assertThat(datasets.get(dataset.getId()).getImages()).hasSize(1);
    }

    @Test
    void theRemoveButtonUsesTheSharedFragmentAndDoesNotBlockThePage() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));

        String page = body(mockMvc.perform(get("/trainings/datasets/" + dataset.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("hx-target=\"#training-images\"").contains("data-busy=\"off\"").contains("Togli dal dataset");
    }

    // --- didascalie ---------------------------------------------------------------------------------------------

    /** Prepara lo stato di un'immagine attraverso lo store (come farebbe il lavoro in background), non attraverso la pagina. */
    private void withImage(Long datasetId, Long imageId, Consumer<TrainingImage> change) {
        TrainingDataset dataset = store.findById(datasetId).orElseThrow();
        change.accept(dataset.findImage(imageId).orElseThrow());
        store.save(dataset);
    }

    private Long firstImageOf(TrainingDataset dataset) {
        return datasets.get(dataset.getId()).getImages().get(0).getId();
    }

    @Test
    void aNewImageAsksForItsCaptionInTheBackgroundAndShowsItPendingWithPolling() throws Exception {
        TrainingDataset dataset = newDataset();

        String fragment = body(mockMvc.perform(multipart("/trainings/datasets/" + dataset.getId() + "/images").file(png("a.png"))
                .header("HX-Request", "true")).andExpect(status().isOk()).andReturn());

        Long imageId = firstImageOf(dataset);
        // L'evento arriva davvero al lavoro (listener + executor): e' asincrono, quindi con un'attesa.
        verify(captionJobs, timeout(5000)).caption(dataset.getId(), imageId);
        assertThat(fragment).contains("Descrizione in corso").contains("hx-trigger=\"load delay:3s\"")
                .contains("/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/caption")
                .contains("id=\"training-caption-" + imageId + "\"").doesNotContain("<textarea");
    }

    @Test
    void aPendingCaptionIsPolledAndStopsPollingOnceItArrives() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));
        Long imageId = firstImageOf(dataset);
        String url = "/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/caption";

        String pending = body(mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn());
        withImage(dataset.getId(), imageId, i -> i.applyAutoCaption(i.getFilename(), "TOKCAT, a cat on a sofa"));
        String done = body(mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn());

        assertThat(pending).contains("hx-trigger=\"load delay:3s\"").doesNotContain("<textarea");
        assertThat(done).doesNotContain("load delay").contains("<textarea").contains("TOKCAT, a cat on a sofa").contains("automatica");
    }

    @Test
    void anEditableCaptionSavesOnChangeWithoutBlockingThePage() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));
        Long imageId = firstImageOf(dataset);
        withImage(dataset.getId(), imageId, i -> i.applyAutoCaption(i.getFilename(), "TOKCAT, a cat"));

        String box = body(mockMvc.perform(get("/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/caption")).andReturn());

        assertThat(box).contains("hx-trigger=\"change\"").contains("hx-swap=\"outerHTML\"").contains("data-busy=\"off\"")
                .contains("hx-post=\"/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/caption\"")
                .contains("hx-target=\"#training-caption-" + imageId + "\"").contains("name=\"caption\"");
    }

    @Test
    void anUnknownImageAnswersWithAnEmptyBodySoThePollingElementJustDisappears() throws Exception {
        TrainingDataset dataset = newDataset();

        String empty = body(mockMvc.perform(get("/trainings/datasets/" + dataset.getId() + "/images/999999/caption"))
                .andExpect(status().isOk()).andReturn());
        String noDataset = body(mockMvc.perform(get("/trainings/datasets/999999/images/1/caption")).andExpect(status().isOk()).andReturn());

        assertThat(empty.strip()).isEmpty();
        assertThat(noDataset.strip()).isEmpty();
    }

    @Test
    void savingACaptionStoresItAsHandWrittenAndAnswersWithTheCaptionBox() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));
        Long imageId = firstImageOf(dataset);

        String box = body(mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/caption")
                        .param("caption", "  TOKCAT, la mia didascalia  ").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());

        TrainingImage saved = datasets.get(dataset.getId()).getImages().get(0);
        assertThat(saved.getCaption()).isEqualTo("TOKCAT, la mia didascalia");
        assertThat(saved.getCaptionSource()).isEqualTo(CaptionSource.MANUAL);
        assertThat(saved.getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
        assertThat(box).contains("TOKCAT, la mia didascalia").contains("scritta a mano").doesNotContain("<html");
    }

    @Test
    void aCaptionTooLongIsAnExpectedRejectionAndChangesNothing() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));
        Long imageId = firstImageOf(dataset);

        mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/caption")
                        .param("caption", "x".repeat(ITrainingCaptions.MAX_CAPTION + 1)).header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(datasets.get(dataset.getId()).getImages().get(0).getCaption()).isNull();
    }

    @Test
    void regeneratingOneCaptionPutsItPendingAndStartsTheJob() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));
        Long imageId = firstImageOf(dataset);
        withImage(dataset.getId(), imageId, i -> i.applyAutoCaption(i.getFilename(), "TOKCAT, vecchia"));

        String box = body(mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/recaption")
                .header("HX-Request", "true")).andExpect(status().isOk()).andReturn());

        assertThat(box).contains("Descrizione in corso").contains("load delay:3s");
        assertThat(datasets.get(dataset.getId()).getImages().get(0).isCaptionPending()).isTrue();
        verify(captionJobs, timeout(5000).atLeastOnce()).caption(dataset.getId(), imageId);
    }

    @Test
    void onlyAHandWrittenCaptionAsksForConfirmationBeforeBeingReplaced() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG), UploadedFile.of("b.png", PNG)));
        Long auto = datasets.get(dataset.getId()).getImages().get(0).getId();
        Long manual = datasets.get(dataset.getId()).getImages().get(1).getId();
        withImage(dataset.getId(), auto, i -> i.applyAutoCaption(i.getFilename(), "TOKCAT, automatica"));
        withImage(dataset.getId(), manual, i -> i.writeCaption("TOKCAT, mia"));

        String autoBox = body(mockMvc.perform(get("/trainings/datasets/" + dataset.getId() + "/images/" + auto + "/caption")).andReturn());
        String manualBox = body(mockMvc.perform(get("/trainings/datasets/" + dataset.getId() + "/images/" + manual + "/caption")).andReturn());

        assertThat(autoBox).contains("/recaption\"").doesNotContain("hx-confirm");
        assertThat(manualBox).contains("/recaption\"").contains("hx-confirm=");
    }

    @Test
    void aCaptionThatDoesNotNameTheTriggerWordIsFlaggedAndTheBulkActionFixesIt() throws Exception {
        TrainingDataset dataset = newDataset(); // trigger word TOKCAT
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));
        Long imageId = firstImageOf(dataset);
        withImage(dataset.getId(), imageId, i -> i.writeCaption("a cat on a sofa"));
        String boxUrl = "/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/caption";

        String flagged = body(mockMvc.perform(get(boxUrl)).andReturn());
        String fragment = body(mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/captions/trigger-word")
                .header("HX-Request", "true")).andExpect(status().isOk()).andReturn());
        String fixed = body(mockMvc.perform(get(boxUrl)).andReturn());

        assertThat(flagged).contains("Non nomina la trigger word");
        assertThat(fragment).contains("training-image-" + imageId).doesNotContain("<html");
        assertThat(datasets.get(dataset.getId()).getImages().get(0).getCaption()).isEqualTo("TOKCAT, a cat on a sofa");
        assertThat(fixed).doesNotContain("Non nomina la trigger word");
    }

    @Test
    void regeneratingTheAutomaticCaptionsAnswersWithTheWholeGridAndLeavesTheHandWrittenOnes() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG), UploadedFile.of("b.png", PNG)));
        Long auto = datasets.get(dataset.getId()).getImages().get(0).getId();
        Long manual = datasets.get(dataset.getId()).getImages().get(1).getId();
        withImage(dataset.getId(), auto, i -> i.applyAutoCaption(i.getFilename(), "TOKCAT, automatica"));
        withImage(dataset.getId(), manual, i -> i.writeCaption("TOKCAT, mia"));

        String fragment = body(mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/captions/regenerate")
                .header("HX-Request", "true")).andExpect(status().isOk()).andReturn());

        TrainingDataset saved = datasets.get(dataset.getId());
        assertThat(saved.findImage(auto).orElseThrow().isCaptionPending()).isTrue();
        assertThat(saved.findImage(manual).orElseThrow().getCaption()).isEqualTo("TOKCAT, mia");
        assertThat(saved.findImage(manual).orElseThrow().isCaptionPending()).isFalse();
        assertThat(fragment).contains("training-image-" + auto).contains("training-image-" + manual).contains("Descrizione in corso");
    }

    @Test
    void theEditorOffersTheBulkCaptionActions() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));

        String page = body(mockMvc.perform(get("/trainings/datasets/" + dataset.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Rigenera le automatiche").contains("/captions/regenerate").contains("Aggiungi la trigger word dove manca")
                .contains("/captions/trigger-word");
    }

    @Test
    void aSnapshotShowsItsCaptionsReadOnly() throws Exception {
        TrainingDataset snapshot = store.save(new TrainingDataset("snapshot", "TOKCAT", LoraType.SUBJECT, null, true, null, Instant.now()));
        snapshot.addImage("snap.png", "snap.png", Instant.now()).writeCaption("TOKCAT, una didascalia congelata");
        snapshot = store.save(snapshot);

        String page = body(mockMvc.perform(get("/trainings/datasets/" + snapshot.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("TOKCAT, una didascalia congelata").doesNotContain("<textarea").doesNotContain("/recaption")
                .doesNotContain("/captions/regenerate").doesNotContain("/captions/trigger-word");
    }

    // --- ritaglio -----------------------------------------------------------------------------------------------

    private static MockMultipartFile crop() {
        return new MockMultipartFile("file", "crop.jpg", "image/jpeg", PNG);
    }

    @Test
    void theEditorCarriesTheCropEditorOnceAndEachCardOpensItOnTheOriginal() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG))); // file-1.png

        String page = body(mockMvc.perform(get("/trainings/datasets/" + dataset.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Alpine.data('cropEditor'").contains("x-data=\"cropEditor\"").contains("data-max-side=\"1536\"")
                .contains("/trainings/datasets/" + dataset.getId() + "/crop\"").contains("hx-target=\"#training-images\"")
                .contains("data-image-id=\"").contains("data-url=\"/images/file-1.png\"").contains("Ritaglia")
                .doesNotContain("/crop/reset");
        assertThat(page.split("Alpine.data\\('cropEditor'", -1)).as("il componente si registra una volta sola").hasSize(2);
    }

    @Test
    void croppingAnswersWithTheImagesFragmentAndTheCardNowShowsTheCropAndHowToUndoIt() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG))); // file-1.png
        Long imageId = datasets.get(dataset.getId()).getImages().get(0).getId();

        String fragment = body(mockMvc.perform(multipart("/trainings/datasets/" + dataset.getId() + "/crop").file(crop())
                        .param("imageId", String.valueOf(imageId)).param("x", "10").param("y", "20").param("w", "300").param("h", "400")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());

        // La card mostra il ritaglio (src) ma l'editor si riapre sull'originale (data-url).
        assertThat(fragment).doesNotContain("<html").contains("ritagliata")
                .contains("src=\"/images/file-2.png\"").contains("data-url=\"/images/file-1.png\"")
                .contains("data-crop-x=\"10\"").contains("data-crop-y=\"20\"")
                .contains("data-crop-w=\"300\"").contains("data-crop-h=\"400\"")
                .contains("/images/" + imageId + "/crop/reset").contains("Ripristina l&#39;originale");
        TrainingDataset saved = datasets.get(dataset.getId());
        assertThat(saved.getImages().get(0).getFilename()).isEqualTo("file-2.png");
        assertThat(saved.getImages().get(0).getOriginalFilename()).isEqualTo("file-1.png");
    }

    @Test
    void resettingTheCropAnswersWithTheFragmentOfTheOriginal() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));
        Long imageId = datasets.get(dataset.getId()).getImages().get(0).getId();
        datasets.cropImage(dataset.getId(), imageId, UploadedFile.of("c.jpg", PNG), 1, 2, 30, 40);

        String fragment = body(mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/images/" + imageId + "/crop/reset")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());

        assertThat(fragment).contains("src=\"/images/file-1.png\"").doesNotContain("ritagliata").doesNotContain("/crop/reset");
        assertThat(datasets.get(dataset.getId()).getImages().get(0).isCropped()).isFalse();
    }

    @Test
    void aRectangleThatIsNotACropIsAnExpectedRejectionAndChangesNothing() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));
        Long imageId = datasets.get(dataset.getId()).getImages().get(0).getId();

        mockMvc.perform(multipart("/trainings/datasets/" + dataset.getId() + "/crop").file(crop())
                        .param("imageId", String.valueOf(imageId)).param("x", "0").param("y", "0").param("w", "0").param("h", "10")
                        .header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(datasets.get(dataset.getId()).getImages().get(0).isCropped()).isFalse();
    }

    @Test
    void aSnapshotHasNeitherTheCropEditorNorTheCropButtons() throws Exception {
        TrainingDataset snapshot = store.save(new TrainingDataset("snapshot", "TOKCAT", LoraType.SUBJECT, null, true, null, Instant.now()));
        snapshot.addImage("snap.png", "snap.png", Instant.now());
        snapshot = store.save(snapshot);

        String page = body(mockMvc.perform(get("/trainings/datasets/" + snapshot.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).doesNotContain("cropEditor").doesNotContain("crop-open").doesNotContain("/crop");
    }

    // --- clone, eliminazione, snapshot --------------------------------------------------------------------------

    @Test
    void cloningRedirectsToTheEditorOfTheCopyAndLeavesTheOriginal() throws Exception {
        TrainingDataset dataset = newDataset();
        datasets.addImages(dataset.getId(), List.of(UploadedFile.of("a.png", PNG)));

        MvcResult result = mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/duplicate").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn();

        String redirect = result.getResponse().getHeader("HX-Redirect");
        assertThat(redirect).matches("/trainings/datasets/\\d+").isNotEqualTo("/trainings/datasets/" + dataset.getId());
        TrainingDataset copy = datasets.get(Long.valueOf(redirect.substring(redirect.lastIndexOf('/') + 1)));
        assertThat(copy.getName()).isEqualTo("Il mio gatto (copia)");
        assertThat(copy.getImages()).hasSize(1);
        assertThat(datasets.get(dataset.getId()).getImages()).hasSize(1);
        assertThat(datasets.page(0, 10).totalElements()).isEqualTo(2);
    }

    @Test
    void deletingRedirectsToTheListAndRemovesTheDataset() throws Exception {
        TrainingDataset dataset = newDataset();

        MvcResult result = mockMvc.perform(post("/trainings/datasets/" + dataset.getId() + "/delete").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getHeader("HX-Redirect")).isEqualTo("/trainings");
        assertThat(datasets.find(dataset.getId())).isEmpty();
    }

    @Test
    void aSnapshotOpensReadOnlyAndCanStillBeCloned() throws Exception {
        TrainingDataset snapshot = store.save(new TrainingDataset("snapshot", "TOKCAT", LoraType.SUBJECT, "nota", true, null, Instant.now()));
        snapshot.addImage("snap.png", "snap.png", Instant.now());
        snapshot = store.save(snapshot);

        String page = body(mockMvc.perform(get("/trainings/datasets/" + snapshot.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("snapshot di un training").contains("TOKCAT").contains("/duplicate")
                .doesNotContain("name=\"triggerWord\"").doesNotContain("training-upload-form").doesNotContain("Togli dal dataset")
                .doesNotContain("/delete\"");
        assertThat(datasets.page(0, 10).isEmpty()).as("uno snapshot non e' una bozza: fuori dall'elenco").isTrue();
    }
}
