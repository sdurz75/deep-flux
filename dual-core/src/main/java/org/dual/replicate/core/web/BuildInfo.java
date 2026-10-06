package org.dual.replicate.core.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Ora e commit della build in esecuzione, mostrati da fragments/core/status-bar.html.
 * <p>
 * Da jar (mvn package) li porta {@link BuildProperties} (goal build-info del pom; il commit solo se si passa {@code -Dbuild.commit=...}). Da una directory di
 * classi (IntelliJ, spring-boot:run) NON ci si fida di quei file: IntelliJ non esegue i plugin Maven e in {@code target/classes}
 * ne resterebbe una copia vecchia, cioe' proprio l'informazione ingannevole che il badge deve evitare. Li' l'ora e' la modifica
 * piu' recente fra i file compilati/copiati e il commit viene da {@code git describe} (con {@code -dirty} se ci sono modifiche
 * non committate).
 */
@Component("buildInfo")
public class BuildInfo {

    private static final Logger log = LoggerFactory.getLogger(BuildInfo.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final String time;
    private final String commit;

    public BuildInfo(ObjectProvider<BuildProperties> buildProperties) {
        Path classes = classesDirectory();
        Instant builtAt;
        String commitId;
        if (classes != null) {
            builtAt = newestModification(classes);
            commitId = gitDescribe();
        } else {
            BuildProperties build = buildProperties.getIfAvailable();
            builtAt = build == null ? null : build.getTime();
            String declared = build == null ? null : build.get("commit");
            commitId = declared == null || declared.isBlank() || "unknown".equals(declared) ? null : declared;
        }
        this.time = builtAt == null ? null : TIME_FORMAT.format(builtAt.atZone(ZoneId.systemDefault()));
        this.commit = commitId;
    }

    /** Ora della build, gia' formattata nel fuso del server; {@code null} se ignota. */
    public String getTime() {
        return time;
    }

    /** Commit abbreviato (+ {@code -dirty}); {@code null} se ignoto. */
    public String getCommit() {
        return commit;
    }

    public boolean isAvailable() {
        return time != null || commit != null;
    }

    /** La directory delle classi se l'app gira da classi sciolte, {@code null} da jar. */
    private static Path classesDirectory() {
        try {
            var source = BuildInfo.class.getProtectionDomain().getCodeSource();
            if (source == null || !"file".equals(source.getLocation().getProtocol())) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isDirectory(path) ? path : null;
        } catch (URISyntaxException | RuntimeException e) {
            log.debug("Percorso delle classi non determinabile", e);
            return null;
        }
    }

    private static Instant newestModification(Path root) {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> f.toFile().lastModified())
                    .max(Long::compare)
                    .map(Instant::ofEpochMilli)
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            log.debug("Ora di build non determinabile", e);
            return null;
        }
    }

    private static String gitDescribe() {
        try {
            // --exclude=* : ignora i tag, resta solo l'hash abbreviato
            Process process = new ProcessBuilder("git", "describe", "--always", "--dirty", "--abbrev=7", "--exclude=*")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes()).trim();
            return process.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            log.debug("Commit git non determinabile", e);
            return null;
        }
    }
}
