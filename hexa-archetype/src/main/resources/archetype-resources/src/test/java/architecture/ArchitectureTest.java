package ${package}.architecture;

import java.util.stream.Stream;
import org.dual.hexa.support.HexaArchitectureRules;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Architettura esagonale per feature: {@code <feature>/{domain,application,port.in,port.out,adapter.in,adapter.out}}, le stesse regole che hexa fa
 * rispettare a se' stesso ({@code HexaArchitectureRules} di hexa-test-support). Una violazione si corregge nel codice, non allentando la regola.
 * Fra feature, e verso le librerie hexa, si usano solo {@code port.in} e {@code domain}.
 */
class ArchitectureTest {

    @TestFactory
    Stream<DynamicTest> hexagonalRules() {
        return HexaArchitectureRules.dynamicTests("${package}");
    }
}
