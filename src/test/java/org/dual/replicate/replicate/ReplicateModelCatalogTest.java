package org.dual.replicate.replicate;

import org.dual.replicate.domain.GenerationFormType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica il catalogo DB-backed (migrazione V6) contro il DB reale
 * dell'app (H2 file-based, stesso usato da TemplateRenderingTests):
 * niente mock di ReplicateModelRepository, ReplicateModel e' un'entity
 * JPA senza un costruttore pubblico adatto a costruire fixture a mano
 * (censita solo via migrazione, mai dall'applicazione), quindi il modo
 * piu' diretto di verificare ReplicateModelCatalog e' contro la riga
 * seed reale.
 */
@SpringBootTest
class ReplicateModelCatalogTest {

    @Autowired
    private ReplicateModelCatalog catalog;

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
}
