package org.dual.hexa.ai.credits.port.in;

import java.util.List;

import org.dual.hexa.ai.credits.domain.CreditLine;

/**
 * SPI dell'host: una sorgente di righe di credito (per l'app la stima del saldo Replicate). Pubblica come le altre SPI del core; l'ordine
 * delle righe e' quello di {@code @Order}. Non deve far fallire il chiamante: un guasto diventa una riga {@code UNAVAILABLE}.
 */
public interface ICreditSource {

    /** Le righe di questa sorgente (anche nessuna, se non e' configurata). */
    List<CreditLine> lines();
}
