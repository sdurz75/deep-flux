package ${package}.example.port.out;

import ${package}.example.domain.ExampleItem;
import java.util.List;
import java.util.Optional;

/** Persistenza delle voci: tipi di dominio, mai Spring Data. */
public interface IExampleStore {

    List<ExampleItem> findNewestFirst();

    Optional<ExampleItem> findById(Long id);

    ExampleItem save(ExampleItem item);

    void delete(ExampleItem item);
}
