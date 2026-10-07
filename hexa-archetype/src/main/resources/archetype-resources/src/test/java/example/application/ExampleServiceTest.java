package ${package}.example.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ${package}.example.domain.ExampleItem;
import ${package}.example.port.out.IExampleStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Test senza contesto Spring: lo store e' una porta, qui un finto in memoria. */
class ExampleServiceTest {

    private final List<ExampleItem> saved = new ArrayList<>();
    private final IExampleStore store = new IExampleStore() {
        @Override
        public List<ExampleItem> findNewestFirst() {
            return saved.reversed();
        }

        @Override
        public ExampleItem save(ExampleItem item) {
            saved.add(item);
            return item;
        }
    };
    private final ExampleService service = new ExampleService(store);

    @Test
    void addTrimsTheTitleAndListIsNewestFirst() {
        service.add("  primo ");
        service.add("secondo");

        assertThat(service.list()).extracting(ExampleItem::getTitle).containsExactly("secondo", "primo");
    }

    @Test
    void aBlankTitleIsRejectedAndNothingIsSaved() {
        assertThatThrownBy(() -> service.add("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(saved).isEmpty();
    }
}
