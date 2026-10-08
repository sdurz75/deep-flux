package org.dual.hexa.core.config.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Un override di configurazione: (modulo, chiave) -> valore. Dettaglio di persistenza dell'adapter. */
@Entity
@Table(name = "module_config")
@IdClass(ModuleConfigEntry.Key.class)
class ModuleConfigEntry {

    @Id
    @Column(name = "module", length = 64)
    private String module;

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String configValue;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ModuleConfigEntry() {
        // richiesto da JPA
    }

    ModuleConfigEntry(String module, String configKey, String configValue, Instant now) {
        this.module = module;
        this.configKey = configKey;
        this.configValue = configValue;
        this.updatedAt = now;
    }

    String getConfigKey() {
        return configKey;
    }

    String getConfigValue() {
        return configValue;
    }

    static class Key implements Serializable {
        private String module;
        private String configKey;

        Key() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(module, other.module) && Objects.equals(configKey, other.configKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(module, configKey);
        }
    }
}
