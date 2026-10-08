package org.dual.hexa.architecture;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Fa rispettare l'architettura esagonale (ports &amp; adapters) per sottosistema, divisa in {@code core} (generico) e
 * {@code app} (specifico). Vedi "Architettura" in CLAUDE.md.
 * <p>
 * Ogni regola e' limitata ai package NUOVI ({@code ..core..}, {@code ..app..}) e ammette l'insieme vuoto: fa rispettare solo
 * cio' che sta in quei package; la regola di chiusura ({@link #nothingOutsideCoreAndApp}) vieta che ne esistano altri
 * (niente package per layer).
 */
@AnalyzeClasses(packages = "org.dual.hexa", importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.DoNotIncludeTestJars.class})
class ArchitectureTest {

    /** I test di {@code hexa-core} arrivano all'app come test-jar ({@code *-tests.jar}), che {@code DoNotIncludeTests} non riconosce. */
    static final class DoNotIncludeTestJars implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("-tests.jar");
        }
    }

    private static final String ROOT = "org.dual.hexa";

    /**
     * I moduli librerie: {@code core} (hexa-core), {@code ai} (hexa-ai), {@code pwa} (hexa-pwa), {@code oauth2} (hexa-oauth2); {@code app} e' l'host. Ogni modulo ha i suoi sottosistemi:
     * {@code org.dual.hexa.<modulo>.<sottosistema>.<resto>}.
     */
    private static final String[] LIBRARIES = {"core", "ai", "pwa", "oauth2"};

    private static final Pattern SLICE = Pattern.compile("^" + Pattern.quote(ROOT) + "\\.(core|ai|pwa|oauth2|app)\\.([^.]+)(?:\\.(.*))?$");

    /** Il suffisso (es. {@code ..domain..}) sotto ogni libreria: {@code org.dual.hexa.<libreria>..domain..}. */
    private static String[] libraries(String suffix) {
        return java.util.Arrays.stream(LIBRARIES).map(layer -> ROOT + "." + layer + suffix).toArray(String[]::new);
    }

    /** Come {@link #libraries} piu' l'host ({@code app}). */
    private static String[] layers(String suffix) {
        return java.util.stream.Stream.concat(java.util.Arrays.stream(libraries(suffix)), java.util.stream.Stream.of(ROOT + ".app" + suffix)).toArray(String[]::new);
    }

    /** Sottosistemi condivisi da tutti: il kernel e il kit UI. Non sono esagoni, ci si puo' dipendere da qualunque parte. */
    private static final String[] SHARED = {"kernel", "web"};

    /**
     * Le uniche porte in uscita del core che l'app puo' implementare (punti di estensione, FQN). Qualunque altra {@code port.out} del
     * core (store, backend blob...) resta invisibile all'app: per i binari c'e' {@code IImageStorageService}, non {@code IBlobBackend}.
     */
    static final Set<String> CORE_EXTENSION_POINTS = Set.of(
            ROOT + ".core.events.port.out.IEventLinkResolver",
            ROOT + ".core.secrets.port.out.ISecretTypeCatalog");

    /**
     * Le SPI che l'host (l'app) implementa per innestarsi in un sottosistema del core che non e' un adattatore in uscita: stanno in
     * {@code port.in} (pubbliche), cosi' l'app le implementa nel rispetto della regola "fra sottosistemi solo port.in e domain". Elenco
     * chiuso: una SPI nuova si aggiunge qui e in "Punti di estensione" di CLAUDE.md.
     */
    static final Set<String> HOST_SPIS = Set.of(
            ROOT + ".core.backup.port.in.IBlobReferences",
            ROOT + ".ai.search.port.in.ISearchableSource",
            ROOT + ".ai.chat.port.in.IChatToolkit",
            ROOT + ".ai.chat.port.in.IChatTurnContributor",
            ROOT + ".ai.chat.port.in.IChatPageContributor",
            ROOT + ".ai.chat.port.in.IChatOutcomeResolver",
            ROOT + ".ai.credits.port.in.ICreditSource");

    /** Ogni SPI dell'elenco esiste davvero (un rename non deve svuotare in silenzio la regola sotto). */
    @ArchTest
    static void everyHostSpiExists(com.tngtech.archunit.core.domain.JavaClasses classes) {
        HOST_SPIS.forEach(name -> org.junit.jupiter.api.Assertions.assertTrue(classes.contain(name), "SPI inesistente: " + name));
    }

    @ArchTest
    static final ArchRule hostSpisAreInterfacesInPortIn = classes()
            .that(new DescribedPredicate<JavaClass>("are host extension SPIs") {
                @Override
                public boolean test(JavaClass input) {
                    return HOST_SPIS.contains(input.getName());
                }
            })
            .should().beInterfaces().andShould().bePublic().andShould().resideInAPackage("..port.in");

    @ArchTest
    static final ArchRule domainStaysPure = classes()
            .that().resideInAnyPackage(layers("..domain.."))
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "javax..", "jakarta.persistence..",
                    // @JdbcTypeCode(SqlTypes.LONGVARCHAR): imposto da CLAUDE.md per il testo lungo (mai @Lob)
                    "org.hibernate.annotations..", "org.hibernate.type..",
                    "..domain..", ROOT + ".core.kernel..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDoesNotTouchInfrastructure = noClasses()
            .that().resideInAnyPackage(layers("..application.."))
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..",
                    "org.springframework.web..", "org.springframework.http..", "org.springframework.ai..",
                    "org.springframework.data..", "org.springframework.dao..", "org.springframework.jdbc..", "java.sql..", "jakarta.servlet..", "java.net.http..")
            .allowEmptyShould(true);

    /** I use case non fanno I/O su file ne' elaborazione di immagini: stanno dietro una porta (es. {@code IBlobImportSource}, {@code ISourceImageScaler}). */
    @ArchTest
    static final ArchRule applicationDoesNotDoFileOrImageIo = noClasses()
            .that().resideInAnyPackage(layers("..application.."))
            .should().dependOnClassesThat().resideInAnyPackage("javax.imageio..", "java.awt..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Files")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Paths")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.FileSystems")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileInputStream")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileOutputStream")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule portsDependOnlyOnDomain = classes()
            .that().resideInAnyPackage(layers("..port.."))
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "javax..", "..domain..", "..port..", ROOT + ".core.kernel..",
                    // unica whitelist: IClientPushStream espone un Flux
                    "reactor.core..", "org.reactivestreams..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule drivingAdaptersDoNotUseDrivenAdapters = noClasses()
            .that().resideInAnyPackage(layers("..adapter.in.."))
            .should().dependOnClassesThat().resideInAnyPackage("..adapter.out..")
            .allowEmptyShould(true);

    /** Un adapter che pilota il sottosistema parla con le sue porte {@code in}: le {@code port.out} sono dell'esagono, non del web. */
    @ArchTest
    static final ArchRule drivingAdaptersDoNotUsePortsOut = noClasses()
            .that().resideInAnyPackage(layers("..adapter.in.."))
            .should().dependOnClassesThat().resideInAnyPackage("..port.out..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule drivenAdaptersDoNotUseDrivingAdapters = noClasses()
            .that().resideInAnyPackage(layers("..adapter.out.."))
            .should().dependOnClassesThat().resideInAnyPackage("..adapter.in..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule coreDoesNotKnowApp = noClasses()
            .that().resideInAnyPackage(libraries(".."))
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".app..")
            .allowEmptyShould(true);

    /** hexa-core non conosce le librerie opzionali (hexa-ai, hexa-pwa, hexa-oauth2): sono loro a dipendere dal core, mai il contrario (lo impone anche Maven). */
    @ArchTest
    static final ArchRule coreDoesNotKnowOptionalLibraries = noClasses()
            .that().resideInAPackage(ROOT + ".core..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".ai..", ROOT + ".pwa..", ROOT + ".oauth2..")
            .allowEmptyShould(true);

    /** Le librerie opzionali non si conoscono fra loro: ognuna dipende solo dal core. */
    @ArchTest
    static final ArchRule aiDoesNotKnowPwa = noClasses()
            .that().resideInAPackage(ROOT + ".ai..").should().dependOnClassesThat().resideInAPackage(ROOT + ".pwa..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule pwaDoesNotKnowAi = noClasses()
            .that().resideInAPackage(ROOT + ".pwa..").should().dependOnClassesThat().resideInAPackage(ROOT + ".ai..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule oauth2DoesNotKnowAiNorPwa = noClasses()
            .that().resideInAPackage(ROOT + ".oauth2..").should().dependOnClassesThat().resideInAnyPackage(ROOT + ".ai..", ROOT + ".pwa..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule aiAndPwaDoNotKnowOauth2 = noClasses()
            .that().resideInAnyPackage(ROOT + ".ai..", ROOT + ".pwa..").should().dependOnClassesThat().resideInAPackage(ROOT + ".oauth2..")
            .allowEmptyShould(true);

    /** Il core non deve appoggiarsi ai vecchi package per layer: quel che e' migrato non torna indietro. */
    @ArchTest
    static final ArchRule coreDoesNotUseLegacyLayerPackages = noClasses()
            .that().resideInAnyPackage(libraries(".."))
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".controller..", ROOT + ".domain..", ROOT + ".service..", ROOT + ".repository..", ROOT + ".remote..",
                    ROOT + ".i18n..", ROOT + ".config..", ROOT + ".replicate..", ROOT + ".search..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule kernelDependsOnNoSubsystem = noClasses()
            .that().resideInAPackage(ROOT + ".core.kernel..")
            .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe("a core subsystem other than the kernel",
                            (JavaClass c) -> c.getPackageName().startsWith(ROOT + ".core.")
                                    && !c.getPackageName().startsWith(ROOT + ".core.kernel")))
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule subsystemsOnlyUseEachOthersPortsIn = classes()
            .that().resideInAnyPackage(layers(".."))
            // Il wiring Spring Boot delle librerie (HexaCoreAutoConfiguration, HexaAiAutoConfiguration) non e' un sottosistema.
            .and().resideOutsideOfPackages("..autoconfigure..")
            .should(onlyUseOtherSubsystemsThroughPortInAndDomain())
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule coreSubsystemsHaveNoCycles = slices()
            .matching(ROOT + ".core.(*)..").should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule aiSubsystemsHaveNoCycles = slices()
            .matching(ROOT + ".ai.(*)..").should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule appFeaturesHaveNoCycles = slices()
            .matching(ROOT + ".app.(*)..").should().beFreeOfCycles()
            .allowEmptyShould(true);

    /** Regola di chiusura: nessuna classe fuori da {@code core}, {@code ai}, {@code pwa}, {@code oauth2}, {@code app}, {@code support} e {@code Application} (niente package per layer). */
    @ArchTest
    static final ArchRule nothingOutsideCoreAndApp = classes()
            .that().resideInAPackage(ROOT + "..")
            .should().resideInAnyPackage(java.util.stream.Stream.concat(java.util.Arrays.stream(layers("..")), java.util.stream.Stream.of(ROOT + ".support..")).toArray(String[]::new))
            .orShould().haveFullyQualifiedName(ROOT + ".Application")
            .allowEmptyShould(true);

    /**
     * Tra sottosistemi (anche tra core e app, e tra feature dell'app) si dipende solo da {@code port.in} e {@code domain}
     * dell'altro: mai dalla sua application ne' dai suoi adapter. Il kernel e il kit UI ({@link #SHARED}) sono eccettuati,
     * e un adapter dell'app puo' implementare le sole porte in uscita del core elencate in {@link #CORE_EXTENSION_POINTS}.
     */
    private static ArchCondition<JavaClass> onlyUseOtherSubsystemsThroughPortInAndDomain() {
        return new ArchCondition<>("use other subsystems only through their port.in or domain") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Matcher from = SLICE.matcher(origin.getPackageName());
                if (!from.matches()) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    Matcher to = SLICE.matcher(dependency.getTargetClass().getPackageName());
                    if (!to.matches()) {
                        continue;
                    }
                    boolean sameSubsystem = from.group(1).equals(to.group(1)) && from.group(2).equals(to.group(2));
                    if (sameSubsystem || isShared(to.group(1), to.group(2))) {
                        continue;
                    }
                    String rest = to.group(3) == null ? "" : to.group(3);
                    boolean allowed = rest.equals("domain") || rest.startsWith("domain.")
                            || rest.equals("port.in") || rest.startsWith("port.in.")
                            || implementsCoreExtensionPoint(from, to, dependency.getTargetClass());
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * Un adapter dell'app, o di una libreria opzionale (hexa-oauth2 aggiunge un servizio a {@code /secrets}), puo' implementare SOLO le porte in uscita del
     * core elencate in {@link #CORE_EXTENSION_POINTS}.
     */
    private static boolean implementsCoreExtensionPoint(Matcher from, Matcher to, JavaClass target) {
        String originRest = from.group(3) == null ? "" : from.group(3);
        return !from.group(1).equals("core") && to.group(1).equals("core") && originRest.startsWith("adapter.")
                && CORE_EXTENSION_POINTS.contains(target.getName());
    }

    private static boolean isShared(String layer, String slice) {
        if (!layer.equals("core")) {
            return false;
        }
        for (String shared : SHARED) {
            if (shared.equals(slice)) {
                return true;
            }
        }
        return false;
    }
}
