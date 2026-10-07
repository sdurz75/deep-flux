package org.hexa.app.training.adapter.out.replicate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.hexa.app.generation.domain.ReplicateConfigurationException;
import org.hexa.app.generation.domain.ReplicateException;
import org.hexa.app.training.domain.DatasetArchive;
import org.hexa.app.training.domain.TrainerJob;
import org.hexa.app.training.domain.TrainingStatus;
import org.hexa.app.training.domain.WeightsFile;
import org.hexa.app.training.port.out.ITrainerGateway;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.kernel.remote.RemoteServiceException;
import org.hexa.core.kernel.remote.RestRemoteClient;
import org.hexa.core.kernel.remote.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link ITrainerGateway} su Replicate: il trainer ({@code replicate/fast-flux-trainer}) e il modello di destinazione. Stesso token, stesso indirizzo, stesse chiavi
 * di errore ({@code replicate.error.*}) e stessa {@link ReplicateException} del client delle generazioni, ma un client a parte: quello e' interno a
 * {@code generation}, e qui servono chiamate che li' non esistono (caricare un file, creare un modello, i training).
 *
 * <p><b>Mai l'{@code input} di una risposta</b> ne' dentro un'eccezione: nel training porta {@code hf_token}, un segreto. Le risposte si leggono solo per id, stato,
 * errore, log e metriche; e un errore HTTP della creazione del training, che riporta il corpo della risposta, ha il token oscurato e nessuna causa (che lo
 * conterrebbe nel suo messaggio, e finirebbe nel registro con lo stack).
 */
@Component
class ReplicateTrainerGateway extends RestRemoteClient implements ITrainerGateway {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };
    private static final String WEIGHTS_SUFFIX = ".safetensors";

    private final RestClient restClient;
    private final String apiToken;
    private final Messages messages;
    private final String trainerModel;
    private final String configuredVersion;
    private final String destinationHardware;

    ReplicateTrainerGateway(RestClient.Builder restClientBuilder, @Value("${replicate.api-base-url}") String apiBaseUrl,
                            @Value("${replicate.api-token}") String apiToken, Messages messages,
                            @Value("${app.training.trainer-model:replicate/fast-flux-trainer}") String trainerModel,
                            @Value("${app.training.trainer-version:}") String configuredVersion,
                            @Value("${app.training.destination-hardware:gpu-t4}") String destinationHardware) {
        super("replicate", messages, ReplicateException::new, RetryPolicy.DEFAULT);
        this.restClient = restClientBuilder.baseUrl(apiBaseUrl).build();
        this.apiToken = apiToken;
        this.messages = messages;
        this.trainerModel = trainerModel;
        this.configuredVersion = configuredVersion == null ? "" : configuredVersion.strip();
        this.destinationHardware = destinationHardware;
    }

    @Override
    public String uploadFile(String filename, DatasetArchive archive) {
        requireToken();
        // Lo stream si riapre a OGNI tentativo (dentro la chiamata): un caricamento ritentato rilegge lo zip dall'inizio.
        return remote.call("uploadTrainingFile", () -> {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("content", new ArchiveResource(filename, archive));
            FileResponse file = restClient.post().uri("/files").headers(this::authHeaders).contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                    .retrieve().body(FileResponse.class);
            if (file == null || file.urls() == null || file.urls().get() == null || file.urls().get().isBlank()) {
                throw emptyResponse();
            }
            return file.urls().get();
        });
    }

    @Override
    public String trainerVersion() {
        if (!configuredVersion.isBlank()) {
            return configuredVersion;
        }
        requireToken();
        String[] ownerAndName = split(trainerModel);
        LatestVersionResponse model = remote.call("getTrainerModel", () -> restClient.get().uri("/models/{owner}/{name}", ownerAndName[0], ownerAndName[1])
                .headers(this::authHeaders).retrieve().body(LatestVersionResponse.class));
        if (model == null || model.latestVersion() == null || model.latestVersion().id() == null || model.latestVersion().id().isBlank()) {
            throw new ReplicateException(messages.get("replicate.error.modelNotFound", trainerModel), null, RemoteServiceException.Kind.PERMANENT);
        }
        return model.latestVersion().id();
    }

    @Override
    public String ensureDestination(String name) {
        requireToken();
        String owner = accountUsername();
        boolean exists = remote.call("getDestinationModel", () -> {
            try {
                restClient.get().uri("/models/{owner}/{name}", owner, name).headers(this::authHeaders).retrieve().toBodilessEntity();
                return true;
            } catch (HttpClientErrorException.NotFound e) {
                return false;
            }
        });
        if (!exists) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("owner", owner);
            body.put("name", name);
            body.put("visibility", "private");
            body.put("hardware", destinationHardware);
            body.put("description", "LoRA addestrato con " + trainerModel);
            // Scrive sull'account: non si ritenta (un ritentativo dopo un timeout troverebbe il modello gia' creato e fallirebbe per un motivo fuorviante).
            remote.call("createDestinationModel", RetryPolicy.NONE, () -> {
                restClient.post().uri("/models").headers(this::authHeaders).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
                return true;
            });
        }
        return owner + "/" + name;
    }

    @Override
    public TrainerJob createTraining(String trainerVersion, String destination, Map<String, Object> input) {
        requireToken();
        String[] ownerAndName = split(trainerModel);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destination", destination);
        body.put("input", input);
        String secret = input.get("hf_token") instanceof String s && !s.isBlank() ? s : null;
        try {
            // NON idempotente e a pagamento: un ritentativo dopo un timeout potrebbe avviare un secondo training.
            return remote.call("createTraining", RetryPolicy.NONE, () -> requireBody(restClient.post()
                    .uri("/models/{owner}/{name}/versions/{version}/trainings", ownerAndName[0], ownerAndName[1], trainerVersion)
                    .headers(this::authHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TrainingResponse.class))).toJob();
        } catch (RemoteServiceException e) {
            if (secret == null) {
                throw e;
            }
            // Il corpo dell'errore puo' riportare l'input (con il token): lo si oscura e si scarta la causa, che lo contiene nel proprio messaggio.
            throw new ReplicateException(e.getMessage() == null ? "" : e.getMessage().replace(secret, "***"), null, e.kind());
        }
    }

    @Override
    public TrainerJob getTraining(String externalId) {
        requireToken();
        return remote.call("getTraining", () -> requireBody(restClient.get().uri("/trainings/{id}", externalId).headers(this::authHeaders).retrieve()
                .body(TrainingResponse.class))).toJob();
    }

    @Override
    public TrainerJob cancelTraining(String externalId) {
        requireToken();
        try {
            return remote.call("cancelTraining", () -> requireBody(restClient.post().uri("/trainings/{id}/cancel", externalId).headers(this::authHeaders)
                    .retrieve().body(TrainingResponse.class))).toJob();
        } catch (RemoteServiceException e) {
            if (e.kind() == RemoteServiceException.Kind.PERMANENT && isGoneOrAlreadyFinished(e)) {
                // Nulla da fermare (training sparito o gia' terminale): un esito ATTESO, non un guasto da registrare e notificare.
                throw new ReplicateException(e.getMessage(), e, RemoteServiceException.Kind.REJECTED);
            }
            throw e;
        }
    }

    /** 404 (non esiste piu') o 409 (non e' piu' annullabile): gli unici rifiuti di un annullamento che non sono un problema di chi lo chiede. */
    private static boolean isGoneOrAlreadyFinished(RemoteServiceException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpClientErrorException http) {
                return http.getStatusCode().value() == 404 || http.getStatusCode().value() == 409;
            }
        }
        return false;
    }

    @Override
    public Optional<Set<String>> trainerInputFields(String trainerVersion) {
        requireToken();
        String[] ownerAndName = split(trainerModel);
        Map<String, Object> version = remote.call("getTrainerVersion", () -> restClient.get()
                .uri("/models/{owner}/{name}/versions/{version}", ownerAndName[0], ownerAndName[1], trainerVersion)
                .headers(this::authHeaders).retrieve().body(JSON_OBJECT));
        return inputFieldsOf(version);
    }

    /** {@code openapi_schema.components.schemas.TrainingInput.properties}: i nomi dei campi; vuoto se la forma e' inattesa (meglio non sapere che sbagliare). */
    static Optional<Set<String>> inputFieldsOf(Map<String, Object> version) {
        Object node = version;
        for (String key : new String[] {"openapi_schema", "components", "schemas", "TrainingInput", "properties"}) {
            node = node instanceof Map<?, ?> map ? map.get(key) : null;
        }
        if (!(node instanceof Map<?, ?> properties) || properties.isEmpty()) {
            return Optional.empty();
        }
        Set<String> fields = new TreeSet<>();
        properties.keySet().forEach(k -> fields.add(String.valueOf(k)));
        return Optional.of(fields);
    }

    @Override
    public Optional<WeightsFile> weights(String externalId) {
        requireToken();
        // Solo l'output: la risposta porta anche l'input, con il token HuggingFace, che non si legge (il record non lo ha).
        TrainingOutputResponse response = remote.call("getTrainingOutput", () -> restClient.get().uri("/trainings/{id}", externalId).headers(this::authHeaders)
                .retrieve().body(TrainingOutputResponse.class));
        String url = response == null || response.output() == null ? null : response.output().weights();
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        URI uri = URI.create(url.strip());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new ReplicateException(messages.get("replicate.error.weightsUrl"));
        }
        // Solo le intestazioni dell'archivio, con richieste Range da 512 byte: il contenuto si scarica quando lo si legge.
        TarScanner.Entry entry;
        try {
            entry = TarScanner.find((offset, length) -> download(uri, RetryPolicy.DEFAULT, offset, length, in -> in.readNBytes(length)), WEIGHTS_SUFFIX).orElse(null);
        } catch (IOException e) {
            throw new ReplicateException(messages.get("replicate.error.connectionFailed", e.getMessage()), e, RemoteServiceException.Kind.TRANSIENT);
        }
        return entry == null ? Optional.empty() : Optional.of(new TarWeights(uri, entry));
    }

    /**
     * Scarica {@code length} byte di {@code uri} da {@code offset} (un indirizzo pubblico di replicate.delivery: NIENTE token) e li passa al lettore dentro la
     * callback, cosi' la risposta si chiude da sola. Sempre con un Range: una risposta che lo ignora (200) comincerebbe dal primo byte dell'archivio e il file
     * sarebbe corrotto, quindi e' un errore. Con {@code RetryPolicy.NONE} per la lettura vera: uno stream gia' consumato non si riavvolge.
     */
    private <T> T download(URI uri, RetryPolicy policy, long offset, long length, WeightsFile.Reader<T> reader) {
        return remote.call("downloadTrainingWeights", policy, () -> restClient.get().uri(uri)
                .header(HttpHeaders.RANGE, "bytes=" + offset + "-" + (offset + length - 1)).exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw errors.httpStatus(response.getStatusCode().value(), uri.getHost());
                    }
                    if (response.getStatusCode().value() != 206) {
                        throw new ReplicateException(messages.get("replicate.error.rangeUnsupported"), null, RemoteServiceException.Kind.PERMANENT);
                    }
                    return reader.read(response.getBody());
                }));
    }

    /** Il {@code .safetensors} dentro l'archivio dell'output: ogni lettura scarica, con un Range, i soli byte del file. */
    private final class TarWeights implements WeightsFile {

        private final URI uri;
        private final TarScanner.Entry entry;

        TarWeights(URI uri, TarScanner.Entry entry) {
            this.uri = uri;
            this.entry = entry;
        }

        @Override
        public String name() {
            return entry.name();
        }

        @Override
        public long size() {
            return entry.size();
        }

        @Override
        public <T> T read(Reader<T> reader) {
            return download(uri, RetryPolicy.NONE, entry.dataOffset(), entry.size(), reader);
        }
    }

    private String accountUsername() {
        AccountResponse account = remote.call("getAccount", () -> restClient.get().uri("/account").headers(this::authHeaders).retrieve()
                .body(AccountResponse.class));
        if (account == null || account.username() == null || account.username().isBlank()) {
            throw emptyResponse();
        }
        return account.username();
    }

    private String[] split(String model) {
        String[] ownerAndName = model.split("/", 2);
        if (ownerAndName.length != 2) {
            throw new ReplicateConfigurationException(messages.get("replicate.error.invalidModelFormat"));
        }
        return ownerAndName;
    }

    private TrainingResponse requireBody(TrainingResponse response) {
        if (response == null) {
            throw emptyResponse();
        }
        return response;
    }

    private ReplicateException emptyResponse() {
        return new ReplicateException(messages.get("replicate.error.emptyResponse"), null, true);
    }

    private void authHeaders(HttpHeaders headers) {
        headers.setBearerAuth(apiToken);
    }

    private void requireToken() {
        if (apiToken == null || apiToken.isBlank()) {
            throw new ReplicateConfigurationException(messages.get("replicate.error.tokenMissing"));
        }
    }

    /** Lo zip come parte multipart: il nome del file e la lunghezza noti (senza, il converter leggerebbe tutto lo stream solo per misurarlo). */
    private static final class ArchiveResource extends AbstractResource {

        private final String filename;
        private final DatasetArchive archive;

        ArchiveResource(String filename, DatasetArchive archive) {
            this.filename = filename;
            this.archive = archive;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long contentLength() {
            return archive.size();
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public String getDescription() {
            return "dataset di addestramento " + filename;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return archive.open();
        }
    }

    /** Sottoinsieme di GET /trainings/{id} per i pesi: SOLO l'output (l'input porta il token HuggingFace). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrainingOutputResponse(Output output) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Output(String weights) {
        }
    }

    /** Sottoinsieme di POST /files: l'URL da cui il trainer legge lo zip. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FileResponse(Urls urls) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Urls(String get) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountResponse(String username) {
    }

    /** Sottoinsieme di GET /models/{owner}/{name}: l'id dell'ultima versione. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LatestVersionResponse(@JsonProperty("latest_version") Version latestVersion) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Version(String id) {
        }
    }

    /**
     * Sottoinsieme della risposta di un training. NIENTE {@code input}: porta il token HuggingFace, e non lo leggiamo ne' lo teniamo mai. {@code error} e'
     * {@code Object} perche' non si deve rompere la lettura per una forma inattesa.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TrainingResponse(String id, String status, Object error, String logs, Map<String, Object> metrics, String version) {

        TrainerJob toJob() {
            Double predictTime = metrics != null && metrics.get("predict_time") instanceof Number n ? n.doubleValue() : null;
            return new TrainerJob(id, statusOf(status), error == null ? null : String.valueOf(error), logs, predictTime, version);
        }

        /** I termini di Replicate; uno sconosciuto non terminale vale "in corso" (non si chiude una riga per una parola mai vista). */
        static TrainingStatus statusOf(String status) {
            if (status == null) {
                return TrainingStatus.PROCESSING;
            }
            return switch (status) {
                case "starting" -> TrainingStatus.PENDING;
                case "succeeded" -> TrainingStatus.SUCCEEDED;
                case "failed" -> TrainingStatus.FAILED;
                case "canceled" -> TrainingStatus.CANCELED;
                default -> TrainingStatus.PROCESSING;
            };
        }
    }
}
