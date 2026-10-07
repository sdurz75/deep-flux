package org.dual.hexa.app.generation.application;

import org.dual.hexa.app.generation.domain.LoraException;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.app.generation.port.out.ILoraPresetStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LoraPresetServiceTest {

    @MockitoBean
    private org.dual.hexa.app.generation.port.out.IPredictionGateway predictionGateway;

    @Autowired
    private ILoraPresetStore repository;
    @Autowired
    private LoraPresetService service;
    @Autowired
    private org.dual.hexa.core.tokens.port.in.IApiTokens tokens;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void aDefaultTokenIsStoredAndShownWithoutTheSecret() {
        var token = tokens.create("HUGGINGFACE", "hf-lora-test", "hf_secretvalue1234", null);
        try {
            var created = service.create("Privato", "https://huggingface.co/sdurz/privato", 1.0, null, null, token.id());

            assertThat(created.defaultTokenId()).isEqualTo(token.id());
            assertThat(created.defaultTokenProvider()).isEqualTo("HUGGINGFACE");
            assertThat(created.defaultTokenName()).isEqualTo("hf-lora-test");
            assertThat(created.defaultTokenHint()).isEqualTo("1234");
            assertThat(created.toString()).doesNotContain("secretvalue");
            assertThat(service.list()).extracting(LoraPresetService.LoraView::defaultTokenId).containsExactly(token.id());

            tokens.delete(token.id());
            assertThat(service.get(created.id()).defaultTokenId()).isNull();
        } finally {
            repository.deleteAll();
            try {
                tokens.delete(token.id());
            } catch (RemoteServiceException ignored) {
                // gia' cancellato dal test
            }
        }
    }

    @Test
    void aMissingOrForeignDefaultTokenIsRejected() {
        var foreign = tokens.create("ALTRO", "altro-lora-test", "secret0000", null);
        try {
            assertThatThrownBy(() -> service.create("A", "a/a", 1.0, null, null, 999_999L)).isInstanceOf(LoraException.class);
            assertThatThrownBy(() -> service.create("B", "b/b", 1.0, null, null, foreign.id())).isInstanceOf(LoraException.class);
            assertThat(repository.count()).isZero();
        } finally {
            tokens.delete(foreign.id());
        }
    }

    @Test
    void createStripsTextAndDefaultsTheScaleAndBlankOptionals() {
        var created = service.create("  Stile  ", "  owner/stile ", null, "  ", "");

        assertThat(created.name()).isEqualTo("Stile");
        assertThat(created.source()).isEqualTo("owner/stile");
        assertThat(created.scale()).isEqualTo(1.0);
        assertThat(created.triggerWords()).isNull();
        assertThat(created.note()).isNull();
    }

    @Test
    void listIsOrderedByNameAndFormOptionsExposesIt() {
        service.create("Zeta", "z/z", 1.0, null, null);
        service.create("Alfa", "a/a", 1.0, null, null);

        assertThat(service.list()).extracting(LoraPresetService.LoraView::name).containsExactly("Alfa", "Zeta");
        assertThat(service.formOptions()).containsOnlyKeys("loraPresets");
    }

    @Test
    void validationRejectsWithAnExpectedNonReportableError() {
        service.create("Stile", "owner/stile", 1.0, null, null);

        assertThatThrownBy(() -> service.create(" ", "x", 1.0, null, null)).isInstanceOf(LoraException.class);
        assertThatThrownBy(() -> service.create("x".repeat(61), "x", 1.0, null, null)).isInstanceOf(LoraException.class);
        assertThatThrownBy(() -> service.create("stile", "x", 1.0, null, null)).isInstanceOf(LoraException.class);
        assertThatThrownBy(() -> service.create("Nuovo", "", 1.0, null, null)).isInstanceOf(LoraException.class);
        assertThatThrownBy(() -> service.create("Nuovo", "x", 3.5, null, null)).isInstanceOf(LoraException.class);
        assertThatThrownBy(() -> service.create("Nuovo", "x", -1.5, null, null)).isInstanceOf(LoraException.class);
        assertThatThrownBy(() -> service.create("Nuovo", "x", 1.0, "t".repeat(501), null))
                .isInstanceOf(RemoteServiceException.class).matches(e -> !((RemoteServiceException) e).isReportable());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void updateAllowsKeepingTheSameNameButNotTakingAnotherOne() {
        var a = service.create("Alfa", "a/a", 1.0, null, null);
        service.create("Beta", "b/b", 1.0, null, null);

        service.update(a.id(), "Alfa", "a/nuovo", 0.5, "t", "n");
        assertThat(service.get(a.id()).source()).isEqualTo("a/nuovo");
        assertThatThrownBy(() -> service.update(a.id(), "beta", "a/a", 1.0, null, null)).isInstanceOf(LoraException.class);
    }

    @Test
    void deleteAndGetOfAMissingIdAreRejected() {
        var a = service.create("Alfa", "a/a", 1.0, null, null);
        service.delete(a.id());

        assertThat(repository.count()).isZero();
        assertThatThrownBy(() -> service.get(a.id())).isInstanceOf(LoraException.class);
        assertThatThrownBy(() -> service.delete(a.id())).isInstanceOf(LoraException.class);
    }
}
