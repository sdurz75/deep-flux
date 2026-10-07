package org.dual.hexa.core.manual.port.out;

import java.util.List;

import org.dual.hexa.core.manual.domain.ManualDocument;

/** Da dove vengono i testi del manuale (oggi il classpath: {@code manual/<lingua>/<NN-gruppo>/<NN-pagina>.md}). */
public interface IManualSource {

    /** I sorgenti di una lingua nell'ordine del manuale; se la lingua non ha un manuale, quelli dell'italiano. */
    List<ManualDocument> documents(String language);
}
