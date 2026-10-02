package org.dual.replicate.app.generation.application;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.ReplicateModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica il catalogo DB-backed (seed del baseline V1) contro il Postgres
 * di test (Testcontainers, schema Flyway V1 con il seed del catalogo):
 * niente mock di ReplicateModelRepository, ReplicateModel e' un'entity
 * JPA senza un costruttore pubblico adatto a costruire fixture a mano
 * (censita solo via migrazione, mai dall'applicazione), quindi il modo
 * piu' diretto di verificare ModelCatalogService e' contro la riga
 * seed reale.
 */
@SpringBootTest
class ModelCatalogServiceTest {

    @Autowired
    private ModelCatalogService catalog;

    @Test
    void seededModelIsInCatalogWithItsFormType() {
        assertThat(catalog.contains("sdurz75/flux-lora-ff3")).isTrue();
        assertThat(catalog.formTypeOf("sdurz75/flux-lora-ff3")).contains(GenerationFormType.FLUX_LORA_FF3);
        assertThat(catalog.versionOf("sdurz75/flux-lora-ff3")).isPresent();
    }

    @Test
    void secondSeededModelIsInCatalogWithItsFormTypeAndNoVersion() {
        assertThat(catalog.contains("black-forest-labs/flux-2-klein-9b")).isTrue();
        assertThat(catalog.formTypeOf("black-forest-labs/flux-2-klein-9b")).contains(GenerationFormType.FLUX_2_KLEIN_9B);
        assertThat(catalog.versionOf("black-forest-labs/flux-2-klein-9b")).isEmpty();
    }

    @Test
    void thirdSeededModelIsInCatalogWithItsFormTypeAndNoVersion() {
        assertThat(catalog.contains("black-forest-labs/flux-krea-dev")).isTrue();
        assertThat(catalog.formTypeOf("black-forest-labs/flux-krea-dev")).contains(GenerationFormType.FLUX_KREA_DEV);
        assertThat(catalog.versionOf("black-forest-labs/flux-krea-dev")).isEmpty();
    }

    @Test
    void devLoraIsAnImageModelWithOptionalSourceAndNoVersion() {
        assertThat(catalog.formTypeOf("black-forest-labs/flux-dev-lora")).contains(GenerationFormType.FLUX_DEV_LORA);
        assertThat(catalog.versionOf("black-forest-labs/flux-dev-lora")).isEmpty();
        assertThat(catalog.contains("black-forest-labs/flux-dev-lora", GenerationKind.IMAGE)).isTrue();
        assertThat(catalog.containsEdit("black-forest-labs/flux-dev-lora")).isFalse();
        assertThat(catalog.models(GenerationKind.IMAGE)).extracting(ReplicateModel::getIdentifier)
                .contains("black-forest-labs/flux-dev-lora");
        assertThat(catalog.editModels()).extracting(ReplicateModel::getIdentifier)
                .doesNotContain("black-forest-labs/flux-dev-lora");
        assertThat(catalog.defaultModel().orElseThrow().getIdentifier()).isNotEqualTo("black-forest-labs/flux-dev-lora");
    }

    @Test
    void videoModelIsInCatalogButNotTheDefaultNorAnImageModel() {
        assertThat(catalog.formTypeOf("prunaai/p-video")).contains(GenerationFormType.P_VIDEO);
        assertThat(catalog.versionOf("prunaai/p-video")).isEmpty();
        assertThat(catalog.defaultModel().orElseThrow().getIdentifier()).isNotEqualTo("prunaai/p-video");
        assertThat(catalog.contains("prunaai/p-video", GenerationKind.VIDEO)).isTrue();
        assertThat(catalog.contains("prunaai/p-video", GenerationKind.IMAGE)).isFalse();
        assertThat(catalog.models(GenerationKind.IMAGE)).extracting(ReplicateModel::getIdentifier)
                .doesNotContain("prunaai/p-video");
        assertThat(catalog.models(GenerationKind.VIDEO)).extracting(ReplicateModel::getIdentifier)
                .containsExactly("prunaai/p-video");
    }

    @Test
    void unknownModelIsNotInCatalog() {
        assertThat(catalog.contains("owner/does-not-exist")).isFalse();
        assertThat(catalog.formTypeOf("owner/does-not-exist")).isEmpty();
        assertThat(catalog.versionOf("owner/does-not-exist")).isEmpty();
    }

    @Test
    void defaultModelIsTheFirstActiveModel() {
        assertThat(catalog.defaultModel()).isPresent();
        assertThat(catalog.defaultModel().get().getIdentifier()).isEqualTo(catalog.models().get(0).getIdentifier());
    }

    @Test
    void idsAsCsvListsAllActiveModels() {
        assertThat(catalog.idsAsCsv()).contains("sdurz75/flux-lora-ff3");
    }

    @Test
    void editModelIsSeparatedFromImageAndVideoModels() {
        assertThat(catalog.formTypeOf("black-forest-labs/flux-kontext-dev")).contains(GenerationFormType.FLUX_KONTEXT_DEV);
        assertThat(catalog.versionOf("black-forest-labs/flux-kontext-dev")).isEmpty();
        assertThat(catalog.containsEdit("black-forest-labs/flux-kontext-dev")).isTrue();
        assertThat(catalog.containsEdit("black-forest-labs/flux-krea-dev")).isFalse();
        assertThat(catalog.editModels()).extracting(ReplicateModel::getIdentifier)
                .containsExactly("black-forest-labs/flux-kontext-dev", "black-forest-labs/flux-fill-dev");
        assertThat(catalog.formTypeOf("black-forest-labs/flux-fill-dev")).contains(GenerationFormType.FLUX_FILL_DEV);
        assertThat(catalog.containsEdit("black-forest-labs/flux-fill-dev")).isTrue();
        assertThat(catalog.models(GenerationKind.IMAGE)).extracting(ReplicateModel::getIdentifier)
                .doesNotContain("black-forest-labs/flux-fill-dev");
        // Ne' i modelli immagine (chat, /generations/new) ne' il default lo includono.
        assertThat(catalog.models(GenerationKind.IMAGE)).extracting(ReplicateModel::getIdentifier)
                .doesNotContain("black-forest-labs/flux-kontext-dev");
        assertThat(catalog.contains("black-forest-labs/flux-kontext-dev", GenerationKind.IMAGE)).isFalse();
        assertThat(catalog.defaultModel().orElseThrow().getIdentifier()).isNotEqualTo("black-forest-labs/flux-kontext-dev");
    }
}
