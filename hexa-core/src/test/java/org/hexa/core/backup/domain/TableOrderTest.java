package org.hexa.core.backup.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableOrderTest {

    @Test
    void referencedTablesComeBeforeTheOnesThatReferenceThem() {
        List<String> order = TableOrder.sort(List.of("chat_message", "generation_image", "chat_conversation", "generation"), Map.of(
                "chat_message", Set.of("chat_conversation", "generation"),
                "generation_image", Set.of("generation")));

        assertThat(order).containsExactlyInAnyOrder("chat_message", "generation_image", "chat_conversation", "generation");
        assertThat(order.indexOf("generation")).isLessThan(order.indexOf("generation_image"));
        assertThat(order.indexOf("generation")).isLessThan(order.indexOf("chat_message"));
        assertThat(order.indexOf("chat_conversation")).isLessThan(order.indexOf("chat_message"));
    }

    @Test
    void aSelfReferenceDoesNotCountAndIndependentTablesAreAlphabetical() {
        List<String> order = TableOrder.sort(List.of("b", "a", "generation"), Map.of("generation", Set.of("generation")));

        assertThat(order).containsExactly("a", "b", "generation");
    }

    @Test
    void referencesToTablesOutsideTheSetAreIgnored() {
        assertThat(TableOrder.sort(List.of("a"), Map.of("a", Set.of("flyway_schema_history")))).containsExactly("a");
    }

    @Test
    void aCycleBetweenTwoTablesCannotBeOrdered() {
        assertThatThrownBy(() -> TableOrder.sort(List.of("a", "b", "c"), Map.of("a", Set.of("b"), "b", Set.of("a"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("a").hasMessageContaining("b");
    }
}
