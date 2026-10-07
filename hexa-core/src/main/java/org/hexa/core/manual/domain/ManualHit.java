package org.hexa.core.manual.domain;

/** Una sezione trovata da una ricerca, con la pagina a cui appartiene e il punteggio (piu' alto = piu' pertinente). */
public record ManualHit(ManualSection section, String pageTitle, int score) {
}
