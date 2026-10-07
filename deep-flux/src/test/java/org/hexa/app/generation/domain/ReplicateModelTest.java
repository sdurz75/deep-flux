package org.hexa.app.generation.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicateModelTest {

    @Test
    void anOwnerNameSourceIsAReplicateModelIdentifier() {
        assertThat(ReplicateModel.identifierOfSource("sdurz75/flux-lora-ff3")).contains("sdurz75/flux-lora-ff3");
        assertThat(ReplicateModel.identifierOfSource("  my-owner/my_lora.v2 ")).contains("my-owner/my_lora.v2");
    }

    @Test
    void urlsVersionsAndWeightFilesAreNot() {
        assertThat(ReplicateModel.identifierOfSource("https://huggingface.co/owner/model")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("huggingface.co/owner/model")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("huggingface.co/owner")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("HuggingFace.co/owner")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("hf.co/owner/model")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("civitai.com/models")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("civitai.com/models/123")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("civitai:123")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("owner/name:abc123")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("owner/weights.safetensors")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("owner/name/extra")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("single")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource("  ")).isEmpty();
        assertThat(ReplicateModel.identifierOfSource(null)).isEmpty();
    }
}
