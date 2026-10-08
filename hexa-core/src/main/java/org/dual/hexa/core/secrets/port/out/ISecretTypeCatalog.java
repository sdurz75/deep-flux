package org.dual.hexa.core.secrets.port.out;

import java.util.List;

import org.dual.hexa.core.secrets.domain.SecretType;

/**
 * Punto di estensione: i tipi di segreto che un'app o un modulo hexa-* aggiunge a quelli di default del core (es. l'app {@code HUGGINGFACE}, hexa-oauth2
 * {@code OAUTH2_CLIENT}). Piu' bean si SOMMANO; l'etichetta di un tipo sta nel bundle di chi lo registra (chiave {@code SecretType#labelKey}).
 * Senza nessuna implementazione restano i tipi di default del core.
 */
public interface ISecretTypeCatalog {

    /** I tipi, nell'ordine in cui si mostrano. */
    List<SecretType> types();
}
