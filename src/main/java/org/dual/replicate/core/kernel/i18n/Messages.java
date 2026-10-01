package org.dual.replicate.core.kernel.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.stereotype.Component;

/**
 * Wrapper ergonomico su MessageSourceAccessor (classe nativa di
 * spring-context, non un framework custom): senza una defaultLocale
 * esplicita in costruzione, MessageSourceAccessor risolve ogni
 * chiamata con LocaleContextHolder.getLocale() al momento della
 * chiamata (non quello dell'iniezione) - la locale della richiesta
 * HTTP corrente, risolta da AcceptHeaderLocaleResolver (vedi
 * application.yml, spring.messages/spring.web.locale). Iniettato
 * anche in service/client fuori dai controller (ReplicateClient,
 * SearxngClient, GenerationService, IImageStorageService,
 * DeepChatService): tutti girano sullo stesso thread servlet della
 * richiesta che li ha invocati, quindi LocaleContextHolder resta
 * valido.
 */
@Component
public class Messages {

    private final MessageSourceAccessor accessor;

    public Messages(MessageSource messageSource) {
        this.accessor = new MessageSourceAccessor(messageSource);
    }

    public String get(String code, Object... args) {
        return accessor.getMessage(code, args);
    }
}
