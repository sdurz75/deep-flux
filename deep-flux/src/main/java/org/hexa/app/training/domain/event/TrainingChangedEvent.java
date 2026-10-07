package org.hexa.app.training.domain.event;

/** Un training e' stato creato o ha cambiato stato: l'elenco e il dettaglio si aggiornano (push verso le tab aperte). Pubblicato dopo il salvataggio. */
public record TrainingChangedEvent(Long trainingId) {
}
