package org.hexa.core.chat.domain;

/**
 * Vocabolario JSON di deep-chat per un allegato: {@code type} e' "image" (il solo che deep-chat riconosca per mostrare un'immagine
 * in chat invece di un link) o "video". Lo usano la risposta di una pagina con la cronologia ricostruita e il payload del push
 * asincrono ({@code ChatMessagePushEvent}).
 */
public record FileRef(String src, String name, String type) {
}
