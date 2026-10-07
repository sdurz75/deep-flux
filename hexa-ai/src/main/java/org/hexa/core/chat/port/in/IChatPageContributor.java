package org.hexa.core.chat.port.in;

import java.util.Map;

/**
 * SPI (zero o piu' implementazioni) con cui chi ospita la chat aggiunge al model della pagina {@code /deep-chat/{id}} cio' che i suoi
 * fragment si aspettano (per l'app: modelli e form di generazione, placeholder da ripristinare, galleria contestuale). La chat non
 * conosce ne' interpreta le chiavi; in caso di chiavi uguali vince l'ultimo contributore. Chiamato a ogni render della pagina.
 */
public interface IChatPageContributor {

    Map<String, Object> pageAttributes(Long conversationId);
}
