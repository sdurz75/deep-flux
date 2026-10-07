package org.dual.hexa.core.autoconfigure;

import java.util.List;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Aggiunge i package dei sottosistemi alla lista di {@code AutoConfigurationPackages} (da cui prendono l'entity scan JPA e i repository di
 * Spring Data), SALTANDO quelli gia' coperti da un package registrato (tipicamente quello dell'host, se sta sopra: un host in {@code org.dual.hexa}
 * registra gia' tutto {@code org.dual.hexa.*} (core, ai, pwa), e due base package annidati farebbero registrare due volte lo stesso repository).
 * L'host e' registrato prima (la sua configurazione e' elaborata prima delle autoconfigurazioni).
 */
public abstract class SubsystemPackagesRegistrar implements ImportBeanDefinitionRegistrar {

    protected abstract List<String> packages();

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        List<String> existing = registered(registry);
        for (String candidate : packages()) {
            boolean covered = existing.stream().anyMatch(base -> candidate.equals(base) || candidate.startsWith(base + "."));
            if (!covered) {
                AutoConfigurationPackages.register(registry, candidate);
            }
        }
    }

    /**
     * I package gia' registrati, letti dalla DEFINIZIONE del bean (argomento del costruttore): {@code AutoConfigurationPackages.get} istanzierebbe
     * il bean e congelerebbe la lista, e i package aggiunti dopo non verrebbero piu' visti da entity scan e repository.
     */
    private static List<String> registered(BeanDefinitionRegistry registry) {
        String bean = AutoConfigurationPackages.class.getName();
        if (!registry.containsBeanDefinition(bean)) {
            return List.of();
        }
        ConstructorArgumentValues.ValueHolder holder = registry.getBeanDefinition(bean).getConstructorArgumentValues()
                .getIndexedArgumentValue(0, String[].class);
        return holder != null && holder.getValue() instanceof String[] names ? List.of(names) : List.of();
    }
}
