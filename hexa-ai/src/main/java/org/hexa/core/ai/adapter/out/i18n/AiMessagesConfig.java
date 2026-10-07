package org.hexa.core.ai.adapter.out.i18n;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractResourceBasedMessageSource;

/**
 * Aggiunge il bundle {@code messages-ai} al {@code MessageSource} dell'host. {@code spring.messages.basename} e' una proprieta' sola
 * (quella di {@code core.yml} vince su quella dell'{@code application.yml}) e una libreria non puo' aggiungervisi: ci pensa questo
 * post-processor, cosi' chi importa hexa-ai non deve elencare il bundle.
 */
@Configuration(proxyBeanMethods = false)
class AiMessagesConfig {

    static final String BASENAME = "messages-ai";

    @Bean
    static BeanPostProcessor aiMessagesRegistrar() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if ("messageSource".equals(beanName) && bean instanceof AbstractResourceBasedMessageSource source) {
                    source.addBasenames(BASENAME);
                }
                return bean;
            }
        };
    }
}
