package org.hexa.app.training.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Che cosa insegna il LoRA: un soggetto (persona, animale, oggetto) o uno stile. E' il {@code lora_type} del trainer
 * ({@link #trainerValue()}), e decide la guida con cui si scrivono le didascalie.
 */
public enum LoraType {
    SUBJECT,
    STYLE;

    /** Il valore che il trainer si aspetta in {@code lora_type}. */
    public String trainerValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Dal valore di un campo di form ({@code subject}/{@code style}, qualunque maiuscola); vuoto se non e' uno dei due. */
    public static Optional<LoraType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (LoraType type : values()) {
            if (type.name().equalsIgnoreCase(value.strip())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
