package org.dual.hexa.core.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Punto d'innesto del layout per le librerie opzionali ({@code hexa-pwa}, ...): la dipendenza Maven va dall'estensione al core, quindi e' l'estensione a
 * implementare questa porta (un bean) e il core a raccoglierla, senza conoscerla. Ogni metodo e' facoltativo; l'ordine fra contributor e' quello di
 * {@code @Order}. I fragment sono nomi Thymeleaf completi (es. {@code fragments/core/pwa-head :: head}), senza parametri: i dati arrivano dal model.
 */
public interface ILayoutContributor {

    /** Una voce del menu «Gestione»: il path dell'app (senza context path) e la chiave di bundle dell'etichetta. */
    record NavEntry(String path, String messageKey) {
    }

    /** Fragment da inserire nel {@code <head>} di ogni pagina intera (meta, link, script di boot). */
    default List<String> head(HttpServletRequest request) {
        return List.of();
    }

    /** Fragment da inserire in fondo al {@code <body>} (script di pagina, overlay). */
    default List<String> bodyEnd(HttpServletRequest request) {
        return List.of();
    }

    /** Voci aggiunte al menu «Gestione» accanto a Eventi e Token. */
    default List<NavEntry> manageMenu() {
        return List.of();
    }
}
