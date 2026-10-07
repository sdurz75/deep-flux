package ${package}.example.adapter.out.persistence;

import ${package}.example.domain.ExampleItem;
import ${package}.example.port.out.IExampleStore;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class JpaExampleStore implements IExampleStore {

    private final ExampleItemRepository repository;

    JpaExampleStore(ExampleItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ExampleItem> findNewestFirst() {
        return repository.findAllByOrderByCreatedAtDescIdDesc();
    }

    @Override
    public ExampleItem save(ExampleItem item) {
        return repository.save(item);
    }
}
