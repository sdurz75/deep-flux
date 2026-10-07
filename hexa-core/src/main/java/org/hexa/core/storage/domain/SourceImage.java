package org.hexa.core.storage.domain;

/** Un'immagine letta dallo storage (o appena caricata e validata): i byte e il tipo MIME ({@code image/png}, ...). */
public record SourceImage(byte[] bytes, String mimeType) {
}
