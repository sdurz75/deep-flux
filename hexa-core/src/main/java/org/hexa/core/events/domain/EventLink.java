package org.hexa.core.events.domain;

/** Un link "apri" mostrato accanto a un evento: {@code path} relativo all'app (il template applica il context path), {@code label} gia' tradotta. */
public record EventLink(String path, String label) {
}
