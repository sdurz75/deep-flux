package org.dual.hexa.app.generation.adapter.in.web;

import org.dual.hexa.app.generation.domain.GenerationFormType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationFormFragmentsTest {

    @Test
    void everyFormTypeHasItsFragmentTemplate() {
        for (GenerationFormType formType : GenerationFormType.values()) {
            assertThat(new ClassPathResource(GenerationFormFragments.resourceOf(formType)).exists())
                    .as("template del fragment per %s", formType).isTrue();
        }
    }

    @Test
    void fragmentNamesFollowTheKebabCaseConvention() {
        assertThat(GenerationFormFragments.fragmentOf(GenerationFormType.FLUX_KREA_DEV))
                .isEqualTo("fragments/app/generation-params-flux-krea-dev :: fields");
        assertThat(GenerationFormFragments.fragmentOf(GenerationFormType.P_VIDEO))
                .isEqualTo("fragments/app/generation-params-p-video :: fields");
        assertThat(GenerationFormFragments.fragmentOf(GenerationFormType.FLUX_2_KLEIN_9B))
                .isEqualTo("fragments/app/generation-params-flux-2-klein-9b :: fields");
    }
}
