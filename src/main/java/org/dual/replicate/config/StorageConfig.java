package org.dual.replicate.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Le immagini scaricate da Replicate vivono in storage.images-dir, fuori
 * da src/main/resources/static perche' sono stato applicativo prodotto a
 * runtime, non asset del progetto. Questa configurazione le rende
 * comunque raggiungibili come file statici sotto /images/**.
 */
@Configuration
public class StorageConfig implements WebMvcConfigurer {

    private final String imagesDir;

    public StorageConfig(@Value("${storage.images-dir}") String imagesDir) {
        this.imagesDir = imagesDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + Path.of(imagesDir).toAbsolutePath() + "/";
        registry.addResourceHandler("/images/**")
                .addResourceLocations(location)
                .setCachePeriod(0);
    }
}
