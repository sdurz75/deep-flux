package org.dual.hexa.app.training.adapter.out.persistence;

import java.time.Instant;

import org.dual.hexa.app.training.domain.CaptionSource;
import org.dual.hexa.app.training.domain.CaptionStatus;
import org.dual.hexa.app.training.domain.LoraType;
import org.dual.hexa.app.training.domain.PendingCaption;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.domain.TrainingImage;
import org.dual.hexa.app.training.port.out.ITrainingDatasetStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.dual.hexa.app.training.domain.DatasetConflictException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Il blocco ottimistico sul DB VERO (gli use case lo provano su uno store in memoria, che ne ricalca solo il contratto): e' qui che si verifica che anche una
 * modifica alle sole immagini faccia salire la versione del dataset, perche' e' su quello che si regge la tenuta delle didascalie scritte in background.
 */
@SpringBootTest
class JpaTrainingDatasetStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Autowired
    private ITrainingDatasetStore store;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        store.deleteAll();
    }

    private TrainingDataset savedDraftWithOnePendingImage() {
        TrainingDataset dataset = new TrainingDataset("gatto", "TOK", LoraType.SUBJECT, null, NOW);
        dataset.addImage("a.png", "a.png", NOW).requestAutoCaption(false);
        return store.save(dataset);
    }

    private long versionOf(TrainingDataset dataset) {
        return jdbc.queryForObject("SELECT version FROM training_dataset WHERE id = ?", Long.class, dataset.getId());
    }

    @Test
    void aStaleCopyCannotRevertAChangeThatTouchedOnlyAnImage() {
        TrainingDataset saved = savedDraftWithOnePendingImage();
        Long imageId = saved.getImages().get(0).getId();
        TrainingDataset copyForTheCaptionJob = store.findById(saved.getId()).orElseThrow();
        TrainingDataset copyForTheUser = store.findById(saved.getId()).orElseThrow();

        // La didascalia automatica arriva: cambia SOLO l'immagine, nessun campo del dataset.
        copyForTheCaptionJob.findImage(imageId).orElseThrow().applyAutoCaption("a.png", "TOK, un gatto");
        store.save(copyForTheCaptionJob);

        // L'utente, con la copia letta prima, salva una modifica anch'essa alle sole immagini: senza il salto di versione riporterebbe la didascalia a PENDING.
        copyForTheUser.findImage(imageId).orElseThrow().writeCaption("scritta a mano");
        assertThatThrownBy(() -> store.save(copyForTheUser)).isInstanceOf(DatasetConflictException.class);

        TrainingImage image = store.findById(saved.getId()).orElseThrow().findImage(imageId).orElseThrow();
        assertThat(image.getCaption()).isEqualTo("TOK, un gatto");
        assertThat(image.getCaptionSource()).isEqualTo(CaptionSource.AUTO);
        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
    }

    @Test
    void savingAnExistingDatasetAlwaysRaisesItsVersionEvenWhenOnlyAnImageChanged() {
        TrainingDataset saved = savedDraftWithOnePendingImage();
        long before = versionOf(saved);
        TrainingDataset copy = store.findById(saved.getId()).orElseThrow();
        copy.getImages().get(0).applyAutoCaption("a.png", "TOK, un gatto"); // nessun campo del dataset cambia

        store.save(copy);

        assertThat(versionOf(saved)).isGreaterThan(before);
    }

    @Test
    void aSecondWriteOnAFreshCopyAfterTheFirstSucceeds() {
        TrainingDataset saved = savedDraftWithOnePendingImage();
        TrainingDataset first = store.findById(saved.getId()).orElseThrow();
        first.getImages().get(0).applyAutoCaption("a.png", "TOK, un gatto");
        store.save(first);

        TrainingDataset second = store.findById(saved.getId()).orElseThrow();
        second.update("rinominato", "TOK", LoraType.SUBJECT, null, NOW);
        store.save(second);

        TrainingDataset reread = store.findById(saved.getId()).orElseThrow();
        assertThat(reread.getName()).isEqualTo("rinominato");
        assertThat(reread.getImages().get(0).getCaption()).isEqualTo("TOK, un gatto");
    }

    @Test
    void pendingCaptionsAreOnlyThoseOfDrafts() {
        TrainingDataset draft = savedDraftWithOnePendingImage();
        Long pendingImage = draft.getImages().get(0).getId();
        TrainingDataset snapshot = new TrainingDataset("snap", "TOK", LoraType.SUBJECT, null, true, null, NOW);
        snapshot.addImage("s.png", "s.png", NOW).requestAutoCaption(false);
        store.save(snapshot);
        TrainingDataset done = new TrainingDataset("fatta", "TOK", LoraType.SUBJECT, null, NOW);
        done.addImage("d.png", "d.png", NOW);
        store.save(done);

        assertThat(store.findPendingCaptions()).containsExactly(new PendingCaption(draft.getId(), pendingImage));
    }
}
