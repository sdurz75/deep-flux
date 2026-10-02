package org.dual.replicate.app.generation.domain;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** I metrics sono quelli reali osservati da GET /v1/predictions (2026-09-29). */
class ReplicatePricingTest {

    private static BigDecimal cost(String model, Map<String, Object> metrics) {
        return ReplicatePricing.estimate(model, metrics).orElseThrow();
    }

    @Test
    void pVideoDraft720pIsPricedPerOutputSecond() {
        Map<String, Object> metrics = Map.of("model_variant", "draft", "resolution_target", "720p",
                "video_output_count", 1, "video_output_duration_seconds", 5, "predict_time", 7.44);

        assertThat(cost("prunaai/p-video", metrics)).isEqualByComparingTo("0.025");
    }

    @Test
    void pVideoStandard1080pIsTheMostExpensiveTier() {
        Map<String, Object> metrics = Map.of("model_variant", "regular", "resolution_target", "1080p",
                "video_output_duration_seconds", 5);

        assertThat(cost("prunaai/p-video", metrics)).isEqualByComparingTo("0.20");
    }

    @Test
    void kreaDevAndKleinAreChargedPerOutputImage() {
        assertThat(cost("black-forest-labs/flux-krea-dev", Map.of("image_output_count", 2))).isEqualByComparingTo("0.12");
        assertThat(cost("black-forest-labs/flux-2-klein-9b", Map.of("image_output_count", 1))).isEqualByComparingTo("0.02");
    }

    @Test
    void devLoraIsChargedPerOutputImageLikeFluxDev() {
        assertThat(cost("black-forest-labs/flux-dev-lora", Map.of("image_output_count", 2))).isEqualByComparingTo("0.05");
        assertThat(ReplicatePricing.estimate("black-forest-labs/flux-dev-lora", Map.of())).isEmpty();
    }

    @Test
    void fluxLoraFf3IsChargedByH100ComputeTime() {
        assertThat(cost("sdurz75/flux-lora-ff3", Map.of("predict_time", 6.009804301)))
                .isEqualByComparingTo("0.009165");
    }

    @Test
    void unknownModelOrMissingMetricsGiveNoEstimate() {
        assertThat(ReplicatePricing.estimate("owner/other", Map.of("predict_time", 1.0))).isEmpty();
        assertThat(ReplicatePricing.estimate("prunaai/p-video", Map.of("resolution_target", "720p"))).isEmpty();
        assertThat(ReplicatePricing.estimate("black-forest-labs/flux-krea-dev", Map.of())).isEmpty();
        assertThat(ReplicatePricing.estimate("black-forest-labs/flux-krea-dev", null)).isEmpty();
        assertThat(ReplicatePricing.estimate(null, Map.of("image_output_count", 1))).isEmpty();
    }

    @Test
    void kontextDevIsChargedPerOutputImage() {
        assertThat(cost("black-forest-labs/flux-kontext-dev", Map.of("image_output_count", 2))).isEqualByComparingTo("0.05");
    }

    @Test
    void fillDevIsChargedPerOutputImage() {
        assertThat(cost("black-forest-labs/flux-fill-dev", Map.of("image_output_count", 2))).isEqualByComparingTo("0.05");
    }
}
