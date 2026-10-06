package org.dual.replicate.core.manual.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.core.manual.domain.ManualEntry;
import org.dual.replicate.core.manual.domain.ManualHit;
import org.dual.replicate.core.manual.domain.ManualPage;
import org.dual.replicate.core.manual.domain.ManualSection;

/**
 * Il manuale online: pagine Markdown (una cartella per lingua, una sottocartella per gruppo), convertite in HTML al volo. {@code language} e' il codice
 * a due lettere della locale ({@code it}); se per quella lingua non c'e' un manuale si ricade sull'italiano. I testi sono contenuto fidato del repo.
 */
public interface IManual {

    /** Tutte le pagine nell'ordine del manuale (gruppo e numero del file). */
    List<ManualEntry> contents(String language);

    /**
     * Una pagina in HTML. {@code linkPrefix} e' il context path della richiesta (vuoto se l'app sta alla radice): serve a riscrivere i link, che
     * nell'HTML renderizzato non passano da {@code @{...}}. Vuoto se lo slug non esiste.
     */
    Optional<ManualPage> page(String slug, String language, String linkPrefix);

    /** Le sezioni di una pagina, in ordine; lista vuota se lo slug non esiste. */
    List<ManualSection> sections(String slug, String language);

    /**
     * Le sezioni piu' pertinenti a {@code query} (parole, senza distinguere accenti e maiuscole; il plurale trova il singolare), la migliore per prima.
     * {@code group} limita a un gruppo ({@code null} = tutti).
     */
    List<ManualHit> search(String query, String group, String language, int max);
}
