package ${package}.example.adapter.out.secrets;

import java.util.List;
import org.dual.hexa.core.secrets.domain.SecretType;
import org.dual.hexa.core.secrets.port.out.ISecretTypeCatalog;
import org.springframework.stereotype.Component;

/**
 * Punto di estensione del core: i TIPI di segreto che l'app salva in {@code /secrets} (oltre a quelli di default del core: token API, password, generico).
 * Il nome e' il valore persistito in {@code secret.type}, l'etichetta e' {@code secrets.type.<NOME>} nel bundle. Senza questo bean la pagina offre solo i
 * tipi del core. Il plaintext di un segreto si ottiene solo dentro il servizio che lo usa (mai in log, eventi o Model), tramite
 * {@code ISecrets.resolve(id, type)} (porta del core).
 */
@Component
public class ExampleSecretTypes implements ISecretTypeCatalog {

    @Override
    public List<SecretType> types() {
        return List.of(new SecretType("EXAMPLE", "secrets.type.EXAMPLE"));
    }
}
