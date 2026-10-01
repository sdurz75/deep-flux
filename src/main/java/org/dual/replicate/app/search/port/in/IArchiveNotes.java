package org.dual.replicate.app.search.port.in;

/** Note manuali: l'unico tipo di documento creabile/modificabile/eliminabile dall'utente (gli altri sono derivati). */
public interface IArchiveNotes {

    /** Crea una nota e ne ritorna l'id. */
    String create(String title, String text);

    /** @throws IllegalArgumentException se {@code id} non e' una nota o non esiste */
    void update(String id, String title, String text);

    /** @throws IllegalArgumentException se {@code id} non e' una nota */
    void delete(String id);
}
