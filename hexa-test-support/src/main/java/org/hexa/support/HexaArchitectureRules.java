package org.hexa.support;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Le regole di architettura esagonale di hexa per un'app host con package radice qualunque. Sono le stesse che il framework fa rispettare a se'
 * stesso (core/app), applicate alle feature dell'host: {@code <radice>.<feature>.{domain,application,port.in,port.out,adapter.in,adapter.out}}.
 * Fuori dalle feature restano {@code Application} e {@code shared} (trasversale).
 * <p>
 * Uso, in un test dell'host:
 * <pre>{@code
 * class ArchitectureTest {
 *     @TestFactory
 *     Stream<DynamicTest> hexagonalRules() {
 *         return HexaArchitectureRules.dynamicTests("com.example.myapp");
 *     }
 * }
 * }</pre>
 * Una violazione si corregge nel codice, non allentando la regola. Le regole ammettono l'insieme vuoto (un host appena nato non ha ancora
 * porte ne' adapter).
 */
public final class HexaArchitectureRules {

    private static final String LIBRARY = "org.hexa.core";

    /** Sottosistemi condivisi delle librerie: kernel e kit web, ci si puo' dipendere da qualunque parte. */
    private static final Set<String> SHARED_LIBRARY_SUBSYSTEMS = Set.of("kernel", "web");

    /**
     * Le sole porte in uscita delle librerie che un adapter dell'host puo' implementare (punti di estensione). Qualunque altra {@code port.out}
     * delle librerie resta invisibile all'host: per i binari c'e' {@code IImageStorageService}, non il backend.
     */
    public static final Set<String> LIBRARY_EXTENSION_POINTS = Set.of(
            LIBRARY + ".events.port.out.IEventLinkResolver",
            LIBRARY + ".tokens.port.out.ITokenProviderCatalog");

    private HexaArchitectureRules() {
    }

    /** Le classi dell'host (test esclusi) da dare in pasto a {@link #forHost}. */
    public static JavaClasses importHost(String rootPackage) {
        return new ClassFileImporter().withImportOption(new WithoutTestClasses()).importPackages(rootPackage);
    }

    /**
     * Esclude le classi di test guardando SOLO l'ultimo {@code /target/} del path: {@code ImportOption.DoNotIncludeTests} scarta qualunque path con
     * {@code /test-classes/}, e un host che vive sotto una cartella cosi' (es. un progetto generato dall'archetype nei suoi test) risulterebbe vuoto, con
     * tutte le regole verdi per vacuita'.
     */
    private static final class WithoutTestClasses implements ImportOption {
        @Override
        public boolean includes(Location location) {
            String uri = location.asURI().toString();
            int target = uri.lastIndexOf("/target/");
            if (target >= 0 && uri.startsWith("test-classes/", target + "/target/".length())) {
                return false;
            }
            return !uri.contains("/build/classes/java/test/") && !uri.contains("-tests.jar");
        }
    }

    /** Un test JUnit dinamico per regola, sull'host importato da {@code rootPackage}. */
    public static Stream<DynamicTest> dynamicTests(String rootPackage) {
        JavaClasses host = importHost(rootPackage);
        return forHost(rootPackage).entrySet().stream()
                .map(rule -> DynamicTest.dynamicTest(rule.getKey(), () -> rule.getValue().check(host)));
    }

    /** Le regole per un host con questo package radice, per nome. */
    public static Map<String, ArchRule> forHost(String rootPackage) {
        Pattern feature = Pattern.compile("^" + Pattern.quote(rootPackage) + "\\.([^.]+)(?:\\.(.*))?$");
        Map<String, ArchRule> rules = new LinkedHashMap<>();

        rules.put("portsAreInterfacesNamedWithTheIPrefix", classes()
                .that().resideInAPackage("..port..")
                .should().beInterfaces().andShould().haveSimpleNameStartingWith("I")
                .allowEmptyShould(true));

        rules.put("domainStaysPure", classes()
                .that().resideInAPackage("..domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "javax..", "jakarta.persistence..",
                        // @JdbcTypeCode(SqlTypes.LONGVARCHAR): il testo lungo si dichiara cosi', mai con @Lob
                        "org.hibernate.annotations..", "org.hibernate.type..",
                        "..domain..", LIBRARY + ".kernel..")
                .allowEmptyShould(true));

        rules.put("applicationDoesNotTouchInfrastructure", noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter..",
                        "org.springframework.web..", "org.springframework.http..", "org.springframework.ai..",
                        "org.springframework.data..", "org.springframework.dao..", "org.springframework.jdbc..",
                        "java.sql..", "jakarta.servlet..", "java.net.http..")
                .allowEmptyShould(true));

        // I use case non fanno I/O su file ne' elaborazione di immagini: stanno dietro una porta.
        rules.put("applicationDoesNotDoFileOrImageIo", noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.imageio..", "java.awt..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Files")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Paths")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.FileSystems")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileInputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileOutputStream")
                .allowEmptyShould(true));

        rules.put("portsDependOnlyOnDomain", classes()
                .that().resideInAPackage("..port..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "javax..", "..domain..", "..port..", LIBRARY + ".kernel..")
                .allowEmptyShould(true));

        rules.put("drivingAdaptersDoNotUseDrivenAdapters", noClasses()
                .that().resideInAPackage("..adapter.in..")
                .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                .allowEmptyShould(true));

        // Un adapter che pilota la feature parla con le sue porte in: le port.out sono dell'esagono, non del web.
        rules.put("drivingAdaptersDoNotUsePortsOut", noClasses()
                .that().resideInAPackage("..adapter.in..")
                .should().dependOnClassesThat().resideInAPackage("..port.out..")
                .allowEmptyShould(true));

        rules.put("drivenAdaptersDoNotUseDrivingAdapters", noClasses()
                .that().resideInAPackage("..adapter.out..")
                .should().dependOnClassesThat().resideInAPackage("..adapter.in..")
                .allowEmptyShould(true));

        rules.put("hostUsesLibrariesOnlyThroughPortInAndDomain", classes()
                .that().resideInAPackage(rootPackage + "..")
                .should(useLibrariesOnlyThroughPortInAndDomain())
                .allowEmptyShould(true));

        rules.put("featuresUseEachOthersPortsInAndDomainOnly", classes()
                .that().resideInAPackage(rootPackage + "..")
                .should(useOtherFeaturesOnlyThroughPortInAndDomain(feature))
                .allowEmptyShould(true));

        rules.put("featuresHaveNoCycles", slices()
                .matching(rootPackage + ".(*)..").should().beFreeOfCycles()
                .allowEmptyShould(true));

        // Niente package per layer alla radice: tutto vive in una feature (o in shared).
        rules.put("noLayerPackagesAtTheRoot", noClasses()
                .that().resideInAPackage(rootPackage + "..")
                .should().resideInAnyPackage(
                        rootPackage + ".controller..", rootPackage + ".service..", rootPackage + ".repository..",
                        rootPackage + ".domain..", rootPackage + ".application..", rootPackage + ".adapter..",
                        rootPackage + ".port..", rootPackage + ".config..")
                .allowEmptyShould(true));

        return rules;
    }

    /** Delle librerie hexa si usano solo {@code port.in}, {@code domain}, kernel e kit web, piu' i punti di estensione elencati. */
    private static ArchCondition<JavaClass> useLibrariesOnlyThroughPortInAndDomain() {
        return new ArchCondition<>("use hexa libraries only through their port.in, domain, kernel or web") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String name = target.getName();
                    if (!name.startsWith(LIBRARY + ".") || LIBRARY_EXTENSION_POINTS.contains(name)) {
                        continue;
                    }
                    String[] parts = name.substring(LIBRARY.length() + 1).split("\\.", 2);
                    if (SHARED_LIBRARY_SUBSYSTEMS.contains(parts[0]) || parts.length < 2) {
                        continue;
                    }
                    String rest = parts[1];
                    boolean allowed = rest.startsWith("domain.") || rest.startsWith("port.in.");
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** Fra feature solo {@code port.in} e {@code domain}; {@code shared} e' usabile da tutte e non dipende da nessuna. */
    private static ArchCondition<JavaClass> useOtherFeaturesOnlyThroughPortInAndDomain(Pattern feature) {
        return new ArchCondition<>("use other features only through their port.in or domain") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Matcher from = feature.matcher(origin.getPackageName());
                if (!from.matches()) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    Matcher to = feature.matcher(dependency.getTargetClass().getPackageName());
                    if (!to.matches() || to.group(1).equals(from.group(1))) {
                        continue;
                    }
                    boolean fromShared = from.group(1).equals("shared");
                    boolean toShared = to.group(1).equals("shared");
                    if (toShared && !fromShared) {
                        continue;
                    }
                    String rest = to.group(2) == null ? "" : to.group(2);
                    boolean allowed = !fromShared
                            && (rest.equals("domain") || rest.startsWith("domain.") || rest.equals("port.in") || rest.startsWith("port.in."));
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }
}
