package org.dual.hexa.app.generation.adapter.out.replicate;

import java.util.Map;
import java.util.Optional;

import org.dual.hexa.app.generation.domain.ModelVersion;
import org.dual.hexa.app.generation.domain.Prediction;
import org.dual.hexa.app.generation.port.out.IPredictionGateway;
import org.springframework.stereotype.Component;

/** {@link IPredictionGateway} su Replicate (REST via {@link ReplicateClient}). */
@Component
class ReplicatePredictionGateway implements IPredictionGateway {

    private final ReplicateClient client;

    ReplicatePredictionGateway(ReplicateClient client) {
        this.client = client;
    }

    @Override
    public Prediction createPrediction(String model, String version, Map<String, Object> input) {
        return client.createPrediction(model, version, input).toDomain();
    }

    @Override
    public Prediction getPrediction(String externalId) {
        return client.getPrediction(externalId).toDomain();
    }

    @Override
    public Optional<ModelVersion> latestVersion(String model) {
        return client.getModel(model).flatMap(ModelResponse::toLatestVersion);
    }

    @Override
    public Prediction cancelPrediction(String externalId) {
        return client.cancelPrediction(externalId).toDomain();
    }
}
