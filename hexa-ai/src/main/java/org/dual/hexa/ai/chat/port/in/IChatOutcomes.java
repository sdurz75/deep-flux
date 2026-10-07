package org.dual.hexa.ai.chat.port.in;

import java.util.Collection;
import java.util.List;

import org.dual.hexa.ai.chat.domain.ChatOutcome;

/** Scrittura dell'esito asincrono di qualcosa che la chat ha avviato (oggi una generazione) come nuovo turno della conversazione. */
public interface IChatOutcomes {

    /**
     * Scrive il turno dell'esito nella conversazione, ne aggiorna l'ultima modifica e lo notifica a chi la ha aperta. Idempotente per
     * {@code ChatOutcome#ref} (chi attende e il recupero possono incrociarsi). Un guasto di persistenza risale a chi chiama.
     *
     * @return {@code true} se ha scritto un turno; {@code false} se ne esiste gia' uno per quel riferimento o la conversazione non c'e' piu'
     */
    boolean append(Long conversationId, ChatOutcome outcome);

    /** Fra i riferimenti dati, quelli che hanno gia' un turno di esito in chat (lo sweep di recupero scrive solo i mancanti). */
    List<Long> refsWithOutcome(Collection<Long> refs);
}
