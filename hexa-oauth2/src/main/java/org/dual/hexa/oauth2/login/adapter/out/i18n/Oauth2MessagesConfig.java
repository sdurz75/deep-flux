package org.dual.hexa.oauth2.login.adapter.out.i18n;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractResourceBasedMessageSource;

/** Aggiunge il bundle {@code messages-oauth2} al {@code MessageSource} dell'host (stessa logica di {@code PwaMessagesConfig} in hexa-pwa). */
@Configuration(proxyBeanMethods = false)
class Oauth2MessagesConfig {

    static final String BASENAME = "messages-oauth2";

    @Bean
    static BeanPostProcessor oauth2MessagesRegistrar() {
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
