package ${package}.example.port.out;

import ${package}.example.domain.ExampleItem;
import java.util.List;

/** Persistenza delle voci: tipi di dominio, mai Spring Data. */
public interface IExampleStore {

    List<ExampleItem> findNewestFirst();

    ExampleItem save(ExampleItem item);
}
