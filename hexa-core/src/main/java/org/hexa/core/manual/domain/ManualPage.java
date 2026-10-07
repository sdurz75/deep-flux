package org.hexa.core.manual.domain;

import java.util.List;

/** Una pagina gia' convertita in HTML (con i link riscritti per il prefisso della richiesta) e i suoi titoli, per l'indice "In questa pagina". */
public record ManualPage(ManualEntry entry, String html, List<ManualHeading> headings) {
}
