package org.dual.hexa.ai.chat.port.in;

import java.util.Map;

/**
 * SPI (zero o piu' implementazioni) con cui chi ospita la chat compone il contesto di ogni turno a partire dalle impostazioni opache che
 * il client manda con il messaggio (oggi: modello e parametri di generazione scelti nel pannello). La chat non le interpreta: il
 * risultato (chiavi di {@code ChatTurnContext} comprese) arriva ai tool come {@code ToolContext}. Un'eccezione fa fallire il turno prima
 * di persistere alcunche'.
 */
public interface IChatTurnContributor {

    Map<String, Object> contribute(Map<String, Object> clientSettings);
}
