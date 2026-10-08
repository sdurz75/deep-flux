package org.dual.hexa.core.config.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code IModuleConfigStore}. */
interface ModuleConfigRepository extends JpaRepository<ModuleConfigEntry, ModuleConfigEntry.Key> {

    @Query("select e from ModuleConfigEntry e where e.module = :module")
    List<ModuleConfigEntry> findByModule(String module);

    @Modifying
    @Query("delete from ModuleConfigEntry e where e.module = :module")
    void deleteByModule(String module);
}
