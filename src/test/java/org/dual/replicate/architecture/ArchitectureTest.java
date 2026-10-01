package org.dual.replicate.architecture;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
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
@AnalyzeClasses(packages = "org.dual.replicate", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "org.dual.replicate";

    /** {@code org.dual.replicate.<core|app>.<sottosistema>.<resto>}. */
    private static final Pattern SLICE = Pattern.compile("^" + Pattern.quote(ROOT) + "\\.(core|app)\\.([^.]+)(?:\\.(.*))?$");

    /** Sottosistemi condivisi da tutti: il kernel e il kit UI. Non sono esagoni, ci si puo' dipendere da qualunque parte. */
    private static final String[] SHARED = {"kernel", "web"};

    @ArchTest
    static final ArchRule domainStaysPure = classes()
            .that().resideInAnyPackage(ROOT + ".core..domain..", ROOT + ".app..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "javax..", "jakarta.persistence..",
                    // @JdbcTypeCode(SqlTypes.LONGVARCHAR): imposto da CLAUDE.md per il testo lungo (mai @Lob)
                    "org.hibernate.annotations..", "org.hibernate.type..",
                    "..domain..", ROOT + ".core.kernel..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDoesNotTouchInfrastructure = noClasses()
            .that().resideInAnyPackage(ROOT + ".core..application..", ROOT + ".app..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..",
                    "org.springframework.web..", "org.springframework.http..", "org.springframework.ai..",
                    "org.springframework.data..", "jakarta.servlet..", "java.net.http..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule portsDependOnlyOnDomain = classes()
            .that().resideInAnyPackage(ROOT + ".core..port..", ROOT + ".app..port..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "javax..", "..domain..", "..port..", ROOT + ".core.kernel..",
                    // unica whitelist: IClientPushStream espone un Flux
                    "reactor.core..", "org.reactivestreams..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule drivingAdaptersDoNotUseDrivenAdapters = noClasses()
            .that().resideInAnyPackage(ROOT + ".core..adapter.in..", ROOT + ".app..adapter.in..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter.out..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule drivenAdaptersDoNotUseDrivingAdapters = noClasses()
            .that().resideInAnyPackage(ROOT + ".core..adapter.out..", ROOT + ".app..adapter.out..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter.in..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule coreDoesNotKnowApp = noClasses()
            .that().resideInAPackage(ROOT + ".core..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".app..")
            .allowEmptyShould(true);

    /** Il core non deve appoggiarsi ai vecchi package per layer: quel che e' migrato non torna indietro. */
    @ArchTest
    static final ArchRule coreDoesNotUseLegacyLayerPackages = noClasses()
            .that().resideInAPackage(ROOT + ".core..")
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
            .that().resideInAnyPackage(ROOT + ".core..", ROOT + ".app..")
            .should(onlyUseOtherSubsystemsThroughPortInAndDomain())
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule coreSubsystemsHaveNoCycles = slices()
            .matching(ROOT + ".core.(*)..").should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule appFeaturesHaveNoCycles = slices()
            .matching(ROOT + ".app.(*)..").should().beFreeOfCycles()
            .allowEmptyShould(true);

    /** Regola di chiusura: nessuna classe fuori da {@code core}, {@code app}, {@code support} e {@code Application} (niente package per layer). */
    @ArchTest
    static final ArchRule nothingOutsideCoreAndApp = classes()
            .that().resideInAPackage(ROOT + "..")
            .should().resideInAnyPackage(ROOT + ".core..", ROOT + ".app..", ROOT + ".support..")
            .orShould().haveFullyQualifiedName(ROOT + ".Application")
            .allowEmptyShould(true);

    /**
     * Tra sottosistemi (anche tra core e app, e tra feature dell'app) si dipende solo da {@code port.in} e {@code domain}
     * dell'altro: mai dalla sua application ne' dai suoi adapter. Il kernel e il kit UI ({@link #SHARED}) sono eccettuati,
     * e un adapter dell'app puo' implementare le porte in uscita del core (punti di estensione).
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
                            || implementsCoreExtensionPoint(from, to, rest);
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** Un adapter dell'app puo' implementare una porta in uscita del core: e' il punto di estensione (es. {@code IEventLinkResolver}). */
    private static boolean implementsCoreExtensionPoint(Matcher from, Matcher to, String targetRest) {
        String originRest = from.group(3) == null ? "" : from.group(3);
        return from.group(1).equals("app") && to.group(1).equals("core") && originRest.startsWith("adapter.")
                && (targetRest.equals("port.out") || targetRest.startsWith("port.out."));
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
