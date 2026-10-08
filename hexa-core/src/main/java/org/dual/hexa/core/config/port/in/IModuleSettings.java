package org.dual.hexa.core.config.port.in;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dual.hexa.core.config.domain.FormState;
import org.dual.hexa.core.config.domain.ModuleValues;

/**
 * Lettura e scrittura della configurazione dei moduli registrati ({@link IConfigModule}). I moduli leggono con {@link #values(String)} a ogni uso;
 * la pagina {@code /settings} usa il resto.
 */
public interface IModuleSettings {

    List<IConfigModule> modules();

    Optional<IConfigModule> module(String id);

    /** Valori effettivi (vedi {@code ModuleValues}); id sconosciuto = {@code IllegalArgumentException}. */
    ModuleValues values(String moduleId);

    /**
     * Salva i valori inviati dal form: tutto o niente (un campo non valido, {@code ConfigException}, non ne salva nessuno). Un campo {@code BOOL}
     * assente vale spento; gli altri campi assenti restano come sono. Un {@code SECRET} vuoto lascia il segreto com'e'; le righe di una {@code COLLECTION}
     * si inviano come {@code <chiave>.<id>.<colonna>} (riga nuova: {@code <chiave>.__new.<colonna>}, da togliere: {@code <chiave>.<id>.__remove}).
     */
    void save(String moduleId, Map<String, String> submitted);

    /** Lo stato del form del modulo (valori, indizi dei segreti, righe), senza alcun segreto in chiaro. */
    FormState formState(String moduleId);

    /** Lo stato del form con sopra quanto inviato e non salvato (dopo un rifiuto), senza validare. */
    FormState preview(String moduleId, Map<String, String> submitted);

    /** Toglie tutti gli override del modulo, segreti compresi: tornano le property e i default. */
    void reset(String moduleId);
}
