package ${package}.example.adapter.out.persistence;

import ${package}.example.domain.ExampleItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private: solo {@code JpaExampleStore} lo vede. */
interface ExampleItemRepository extends JpaRepository<ExampleItem, Long> {

    List<ExampleItem> findAllByOrderByCreatedAtDescIdDesc();
}
