-- Configurazione dei moduli (sottosistema config): un override per (modulo, chiave). Senza riga valgono la property app.<modulo>.<chiave> e il default
-- dichiarato dal modulo. Nessuna FK: i moduli sono estensioni, il core non li conosce.
CREATE TABLE module_config (
    module       varchar(64) NOT NULL,
    config_key   varchar(64) NOT NULL,
    config_value text NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT pk_module_config PRIMARY KEY (module, config_key)
);
