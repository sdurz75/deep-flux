package ${package}.example.application;

import ${package}.example.domain.ExampleItem;
import ${package}.example.port.in.IExamples;
import ${package}.example.port.out.IExampleStore;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExampleService implements IExamples {

    private final IExampleStore store;

    public ExampleService(IExampleStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExampleItem> list() {
        return store.findNewestFirst();
    }

    @Override
    @Transactional
    public ExampleItem add(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("title");
        }
        return store.save(new ExampleItem(trimmed));
    }
}
