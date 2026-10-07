package ${package}.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architettura esagonale per feature: {@code <feature>/{domain,application,port.in,port.out,adapter.in,adapter.out}}. Una violazione si corregge nel
 * codice, non allentando la regola. Fra feature si usano solo {@code port.in} e {@code domain}: aggiungere qui la regola quando ne nasce una seconda.
 */
@AnalyzeClasses(packages = "${package}", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule portsAreInterfacesNamedWithTheIPrefix = classes()
            .that().resideInAPackage("..port..")
            .should().beInterfaces().andShould().haveSimpleNameStartingWith("I")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainHasNoFrameworkOrAdapterDependencies = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "..adapter..", "..application..", "..port..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDoesNotKnowWebOrPersistenceTechnology = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "org.springframework.web..", "org.springframework.http..",
                    "org.springframework.data..", "jakarta.servlet..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule inboundAdaptersTalkOnlyToInboundPorts = noClasses()
            .that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAnyPackage("..port.out..", "..adapter.out..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outboundAdaptersDoNotKnowInboundOnes = noClasses()
            .that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter.in..")
            .allowEmptyShould(true);
}
