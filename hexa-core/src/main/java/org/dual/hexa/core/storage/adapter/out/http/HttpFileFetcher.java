package org.dual.hexa.core.storage.adapter.out.http;

import java.net.URI;

import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteCaller;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.hexa.core.kernel.remote.RestClientTranslator;
import org.dual.hexa.core.kernel.remote.RetryPolicy;
import org.dual.hexa.core.storage.domain.StorageException;
import org.dual.hexa.core.storage.port.out.IRemoteFileFetcher;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Download in streaming via {@code RestClient}. Il RestClient.Builder auto-configurato (non {@code RestClient.create()})
 * porta i timeout globali di {@code spring.http.clients.*}: senza, un download appeso bloccherebbe il thread per sempre.
 */
@Component
public class HttpFileFetcher implements IRemoteFileFetcher {

    private final RestClient restClient;
    private final Messages messages;

    public HttpFileFetcher(Messages messages, RestClient.Builder restClientBuilder) {
        this.messages = messages;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public void fetch(String sourceUrl, String targetName, Sink sink) {
        RemoteCaller.builder(e -> new StorageException(messages.get("imagestorage.error.saveImage", targetName), e,
                        downloadFailureKind(e)))
                .retry(RetryPolicy.DEFAULT).build()
                .call("downloadOutput", () -> restClient.get()
                        .uri(URI.create(sourceUrl))
                        .exchange((request, response) -> {
                            if (response.getStatusCode().isError()) {
                                int status = response.getStatusCode().value();
                                throw new StorageException("HTTP " + status + " da " + sourceUrl, null,
                                        RestClientTranslator.kindOfStatus(status));
                            }
                            sink.accept(response.getBody());
                            return null;
                        }));
    }

    private static Kind downloadFailureKind(Throwable e) {
        if (e instanceof RestClientResponseException response) {
            return RestClientTranslator.kindOfStatus(response.getStatusCode().value());
        }
        return e instanceof ResourceAccessException ? Kind.TRANSIENT : Kind.PERMANENT;
    }
}
