package org.dual.replicate.service;

import org.dual.replicate.remote.RemoteServiceException;
import org.dual.replicate.repository.LoraPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LoraPresetServiceTest {

    @Autowired
    private LoraPresetRepository repository;
    @Autowired
    private LoraPresetService service;

    @BeforeEach
    void clean() {
        repository.deleteAll();
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
