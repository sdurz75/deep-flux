package org.dual.replicate.app.training.adapter.out.huggingface;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.app.training.domain.HfAccount;
import org.dual.replicate.app.training.domain.HuggingFaceException;
import org.dual.replicate.app.training.port.out.IHuggingFaceRepos;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.kernel.remote.RestRemoteClient;
import org.dual.replicate.core.kernel.remote.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link IHuggingFaceRepos} su HuggingFace (REST, {@code RestClient}). Il token non e' una configurazione dell'app: arriva a ogni chiamata, scelto dall'utente fra
 * quelli salvati in /tokens, e non viene mai messo in un messaggio d'errore ne' in un log.
 */
@Component
class HuggingFaceClient extends RestRemoteClient implements IHuggingFaceRepos {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final Messages messages;

    HuggingFaceClient(RestClient.Builder restClientBuilder, @Value("${huggingface.api-base-url:https://huggingface.co/api}") String apiBaseUrl,
                      Messages messages) {
        super("huggingface", messages, HuggingFaceException::new, RetryPolicy.DEFAULT);
        this.restClient = restClientBuilder.baseUrl(apiBaseUrl).build();
        this.messages = messages;
    }

    @Override
    public HfAccount whoami(String token) {
        Map<String, Object> body = remote.call("whoami", () -> restClient.get().uri("/whoami-v2").headers(h -> h.setBearerAuth(token)).retrieve()
                .body(JSON_OBJECT));
        Object name = body == null ? null : body.get("name");
        if (!(name instanceof String username) || username.isBlank()) {
            throw new HuggingFaceException(messages.get("huggingface.error.noUsername"), null, Kind.PERMANENT);
        }
        return new HfAccount(username, roleOf(body));
    }

    /** {@code auth.accessToken.role} ({@code read}, {@code write}, {@code fineGrained}...); {@code null} se la forma e' inattesa. */
    private static String roleOf(Map<String, Object> body) {
        return body.get("auth") instanceof Map<?, ?> auth && auth.get("accessToken") instanceof Map<?, ?> accessToken
                && accessToken.get("role") instanceof String role ? role : null;
    }

    @Override
    public void createModelRepo(String token, String repoName, boolean isPrivate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "model");
        body.put("name", repoName);
        body.put("private", isPrivate);
        remote.call("createModelRepo", () -> {
            try {
                restClient.post().uri("/repos/create").headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().toBodilessEntity();
            } catch (HttpClientErrorException.Conflict e) {
                // Esiste gia': e' quello che si voleva (e un ritentativo dopo un timeout lo trova creato dal tentativo precedente).
                return false;
            }
            return true;
        });
    }

    @Override
    public boolean repoExists(String token, String repoId) {
        String[] userAndName = repoId.split("/", 2);
        if (userAndName.length != 2) {
            return false;
        }
        return remote.call("repoExists", () -> {
            try {
                restClient.get().uri("/models/{user}/{name}", userAndName[0], userAndName[1]).headers(h -> h.setBearerAuth(token)).retrieve()
                        .toBodilessEntity();
                return true;
            } catch (HttpClientErrorException.NotFound e) {
                return false;
            }
        });
    }
}
