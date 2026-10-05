package org.dual.replicate.app.prompt.domain;

/**
 * Che cosa deve imparare il LoRA a cui si didascalizza un'immagine: cambia COSA la didascalia deve descrivere e cosa lasciare fuori. Tipo del lato prompt
 * (la feature e' una foglia: non conosce i tipi di chi la usa).
 */
public enum CaptionStyle {

    /** Un soggetto (persona, animale, oggetto): si descrive tutto TRANNE cio' che lo identifica, che deve imparare dalle immagini. */
    SUBJECT,

    /** Uno stile visivo: si descrive il CONTENUTO, mai lo stile stesso. */
    STYLE
}
