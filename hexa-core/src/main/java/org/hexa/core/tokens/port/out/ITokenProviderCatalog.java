package org.hexa.core.tokens.port.out;

import java.util.List;

/**
 * Punto di estensione: i servizi (provider) per cui l'app salva token API, es. {@code HUGGINGFACE}. Il nome e' il valore
 * persistito in {@code api_token.provider} e la chiave {@code tokens.provider.<NAME>} del bundle per l'etichetta. Senza
 * implementazione la pagina /tokens funziona ma non offre nessun servizio.
 */
public interface ITokenProviderCatalog {

    /** I nomi dei provider, nell'ordine in cui si mostrano. */
    List<String> providers();
}
