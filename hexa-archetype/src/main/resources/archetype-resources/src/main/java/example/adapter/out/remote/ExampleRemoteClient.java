#set( $symbol_dollar = '$' )
package ${package}.example.adapter.out.remote;

import ${package}.example.domain.ExampleRemoteException;
import ${package}.example.port.out.IExampleRemote;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.hexa.core.kernel.remote.RestRemoteClient;
import org.dual.hexa.core.kernel.remote.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Modello di client verso un servizio esterno: estende {@code RestRemoteClient} (traduzione errori HTTP -> {@code Kind}, retry, messaggi i18n con prefisso
 * {@code example.remote}: chiavi {@code .error.httpError} e {@code .error.connectionFailed} nel bundle dell'app) e avvolge OGNI chiamata in {@code remote.call}.
 * Il {@code RestClient.Builder} e' quello iniettato (timeout e proxy da {@code spring.http.clients.*}), mai {@code RestClient.create()}.
 *
 * <p>Una chiamata idempotente usa la policy di default (2 ritentativi a 500 ms sui soli guasti transitori); una NON idempotente o a pagamento passa
 * {@code RetryPolicy.NONE} esplicita: {@code remote.call("createThing", RetryPolicy.NONE, () -> ...)}. Questo client non registra nulla: lo fa chi gestisce l'errore.
 */
@Component
class ExampleRemoteClient extends RestRemoteClient implements IExampleRemote {

    private final RestClient restClient;
    private final Messages messages;
    private final boolean configured;

    ExampleRemoteClient(RestClient.Builder restClientBuilder, @Value("${symbol_dollar}{example.remote.base-url:}") String baseUrl, Messages messages) {
        super("example.remote", messages, ExampleRemoteException::new, RetryPolicy.DEFAULT);
        this.configured = baseUrl != null && !baseUrl.isBlank();
        this.restClient = configured ? restClientBuilder.baseUrl(baseUrl).build() : restClientBuilder.build();
        this.messages = messages;
    }

    @Override
    public String fetchStatus() {
        if (!configured) {
            // Credenziali/URL mancanti: CONFIGURATION, permanente, notificata (non e' colpa dell'input dell'utente).
            throw new ExampleRemoteException(messages.get("example.remote.error.notConfigured"), null, Kind.CONFIGURATION);
        }
        return remote.call("fetchStatus", () -> {
            String body = restClient.get().uri("/status").retrieve().body(String.class);
            if (body == null) {
                // Risposta illeggibile: permanente, riprovare non serve.
                throw new ExampleRemoteException(messages.get("example.remote.error.emptyBody"), null, Kind.PERMANENT);
            }
            return body.strip();
        });
    }
}
