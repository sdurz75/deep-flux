package org.dual.hexa.pwa.shell.adapter.out.i18n;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractResourceBasedMessageSource;

/** Aggiunge il bundle {@code messages-pwa} al {@code MessageSource} dell'host (stessa logica di {@code AiMessagesConfig} in hexa-ai). */
@Configuration(proxyBeanMethods = false)
class PwaMessagesConfig {

    static final String BASENAME = "messages-pwa";

    @Bean
    static BeanPostProcessor pwaMessagesRegistrar() {
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
