package org.dual.hexa.core.secrets.domain;

/**
 * Un tipo di segreto registrato da chi lo usa (il core ne ha di default, i moduli hexa-* e l'app ne aggiungono con {@code ISecretTypeCatalog}).
 * {@code name} e' l'identificatore persistito (MAIUSCOLO, es. {@code HUGGINGFACE}); {@code labelKey} la chiave di bundle dell'etichetta, nel bundle di
 * chi registra il tipo. Un tipo {@code managed} e' creato e cambiato dalla UI del modulo che lo possiede (un campo {@code SECRET} di
 * {@code IConfigModule}): {@code /secrets} lo elenca ma non lo crea, non lo modifica e non lo cancella.
 */
public record SecretType(String name, String labelKey, boolean managed) {

    public SecretType {
        if (name == null || !name.matches("[A-Z][A-Z0-9_]{1,39}")) {
            throw new IllegalArgumentException("Nome di tipo di segreto non valido: " + name);
        }
    }

    public SecretType(String name, String labelKey) {
        this(name, labelKey, false);
    }
}
