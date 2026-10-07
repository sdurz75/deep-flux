package org.dual.hexa.pwa.shell.adapter.out.classpath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.dual.hexa.pwa.shell.port.out.IServiceWorkerSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Legge {@code pwa/sw.js} dal classpath una volta sola. Il file NON sta in {@code static/}: lo serve solo il controller, con lo scope giusto. */
@Component
class ClasspathServiceWorkerSource implements IServiceWorkerSource {

    private final String template;

    ClasspathServiceWorkerSource() {
        try (var in = new ClassPathResource("pwa/sw.js").getInputStream()) {
            this.template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("pwa/sw.js mancante dal classpath", e);
        }
    }

    @Override
    public String template() {
        return template;
    }
}
