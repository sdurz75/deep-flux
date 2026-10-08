package org.dual.hexa.core.config.port.out;

import java.util.Map;

/** Override salvati in DB, per modulo. */
public interface IModuleConfigStore {

    Map<String, String> load(String moduleId);

    void saveAll(String moduleId, Map<String, String> values);

    void deleteAll(String moduleId);
}
