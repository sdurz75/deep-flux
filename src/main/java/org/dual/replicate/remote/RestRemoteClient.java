package org.dual.replicate.remote;

import org.dual.replicate.i18n.Messages;

/**
 * Base di un client HTTP verso un servizio esterno: cabla in un colpo solo traduzione degli errori, retry e i18n.
 * Un nuovo servizio = una sottoclasse che passa il prefisso delle chiavi ({@code foo.error.httpError|connectionFailed}) e la
 * propria eccezione, e avvolge ogni chiamata in {@code remote.call("operazione", () -> ...)}.
 */
public abstract class RestRemoteClient {

    /** Esecutore con retry: {@code remote.call(op, ...)} / {@code remote.call(op, RetryPolicy.NONE, ...)}. */
    protected final RemoteCaller remote;
    /** Per gli errori HTTP notati dentro un {@code exchange}: {@code errors.httpStatus(status, dettaglio)}. */
    protected final RestClientTranslator errors;

    @SafeVarargs
    protected RestRemoteClient(String i18nPrefix, Messages messages, RestClientTranslator.ExceptionFactory exceptionFactory,
                               RetryPolicy defaultPolicy, Class<? extends Throwable>... passThrough) {
        this.errors = new RestClientTranslator(i18nPrefix, messages, exceptionFactory);
        this.remote = RemoteCaller.builder(errors).retry(defaultPolicy).passThrough(passThrough).build();
    }
}
