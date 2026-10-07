package ${package}.example.port.in;

import ${package}.example.domain.ExampleItem;
import java.util.List;

/** Cio' che la feature offre al mondo esterno (i controller parlano solo con le porte {@code in}). */
public interface IExamples {

    /** Le voci, dalla piu' recente. */
    List<ExampleItem> list();

    /** @throws IllegalArgumentException se il titolo e' vuoto */
    ExampleItem add(String title);
}
