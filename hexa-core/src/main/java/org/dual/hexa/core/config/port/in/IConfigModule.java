package org.dual.hexa.core.config.port.in;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.core.config.domain.ConfigChange;
import org.dual.hexa.core.config.domain.ConfigField;

/**
 * SPI di un modulo che ha bisogno di configurazione: basta un bean (si autoregistra, come {@code ILayoutContributor}) e la pagina {@code /settings} e
 * la voce del menu «Gestione» compaiono da sole. Il core non nomina mai il modulo. L'ordine fra i moduli e' quello di {@code @Order}.
 *
 * <p>I campi generati coprono i casi comuni (anche segreti, liste e liste di record: vedi {@code ConfigField}); per una UI propria il modulo indica un
 * {@link #fragment()} senza parametri, che il core inserisce sotto i campi (i dati arrivano dal model con un {@code @ControllerAdvice} del modulo). I
 * testi ({@link #titleKey()}, etichette dei campi) stanno nel bundle del modulo.
 */
public interface IConfigModule {

    /** Identificatore unico (slug minuscolo): URL {@code /settings/<id>}, tabella e property {@code app.<id>.*}. */
    String id();

    /** Chiave di bundle del titolo della sezione. */
    String titleKey();

    List<ConfigField> fields();

    /** Fragment Thymeleaf facoltativo, senza parametri (es. {@code fragments/core/pwa-settings :: extra}). */
    default Optional<String> fragment() {
        return Optional.empty();
    }

    /**
     * Controllo FRA campi, chiamato da {@code save} dopo la validazione di ogni campo e prima di scrivere qualunque cosa. Lancia
     * {@code ConfigException} (rifiuto atteso, messaggio gia' tradotto) per annullare l'intero salvataggio.
     */
    default void validate(ConfigChange change) {
    }
}
