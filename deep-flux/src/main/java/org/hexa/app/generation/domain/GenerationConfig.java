package org.hexa.app.generation.domain;

import java.util.Map;

/**
 * La configurazione di una generazione passata, ricostruita da quanto e' gia' salvato, per riproporla nel form ("Usa
 * configurazione"). {@code parameters} e' nel vocabolario del provider (come in {@code parametersJson}: il form-type la converte
 * in campi di form); {@code seed} e' quello che riproduce il file scelto (null se nessun seed lo riproduce); la sorgente c'e' solo
 * se e' ancora una generazione immagine valida (un upload o una generazione cancellata non si possono riproporre).
 * Non porta mai la {@code version}: il form usa quella del catalogo, perche' una version spinta con un successivo cambio di
 * modello farebbe girare (e fatturare) il modello sbagliato.
 */
public record GenerationConfig(String model, String prompt, Long seed, Map<String, Object> parameters,
                               Long sourceGenerationId, String sourceImage) {
}
