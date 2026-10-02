package org.dual.replicate.app.shared.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormFieldsTest {

    @Test
    void numbersAreParsedLeniently() {
        assertThat(FormFields.asInteger("7")).isEqualTo(7);
        assertThat(FormFields.asLong("42")).isEqualTo(42L);
        assertThat(FormFields.asDouble("2.5")).isEqualTo(2.5);
        for (String bad : new String[]{null, "", "  ", "abc", "1,5"}) {
            assertThat(FormFields.asInteger(bad)).isNull();
            assertThat(FormFields.asLong(bad)).isNull();
            assertThat(FormFields.asDouble(bad)).isNull();
        }
    }

    @Test
    void textIsStrippedAndBlankIsAbsent() {
        assertThat(FormFields.asText("  hello ")).isEqualTo("hello");
        assertThat(FormFields.asText("   ")).isNull();
        assertThat(FormFields.asText(null)).isNull();
    }

    @Test
    void oneOfKeepsOnlyAllowedValues() {
        Set<String> allowed = Set.of("1:1", "16:9");
        assertThat(FormFields.asOneOf("16:9", allowed)).isEqualTo("16:9");
        assertThat(FormFields.asOneOf("custom", allowed)).isNull();
        assertThat(FormFields.asOneOf(null, allowed)).isNull();
    }

    @Test
    void putIfPresentSkipsNulls() {
        Map<String, Object> params = new LinkedHashMap<>();
        FormFields.putIfPresent(params, "a", 1);
        FormFields.putIfPresent(params, "b", null);

        assertThat(params).containsOnlyKeys("a");
    }

    @Test
    void aCheckboxIsCheckedOnlyWhenItsKeyIsSubmitted() {
        assertThat(FormFields.isChecked(Map.of("go_fast", "on"), "go_fast")).isTrue();
        assertThat(FormFields.isChecked(Map.of(), "go_fast")).isFalse();
    }
}
