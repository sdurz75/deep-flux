package ${package}.example.adapter.out.tokens;

import java.util.List;
import org.hexa.core.tokens.port.out.ITokenProviderCatalog;
import org.springframework.stereotype.Component;

/**
 * Punto di estensione del core: i servizi per cui l'app salva token API in {@code /tokens}. Il nome e' il valore persistito in {@code api_token.provider},
 * l'etichetta e' {@code tokens.provider.<NOME>} nel bundle. Senza questo bean la pagina funziona ma non offre nessun servizio. Il plaintext di un token si
 * ottiene solo dentro il servizio che lo usa (mai in log, eventi o Model), tramite {@code IApiTokens.resolve(id, provider)} (porta del core).
 */
@Component
public class ExampleTokenProviders implements ITokenProviderCatalog {

    @Override
    public List<String> providers() {
        return List.of("EXAMPLE");
    }
}
