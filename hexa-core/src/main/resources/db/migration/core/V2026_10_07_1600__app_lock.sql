-- Blocco con PIN (hexa-pwa, sottosistema lock): UNA riga (id = 1) quando il PIN e' impostato, nessuna riga = blocco spento.
-- L'hash e' PBKDF2-HMAC-SHA256 (mai il PIN); failed_attempts/locked_until implementano il rallentamento dei tentativi sbagliati.
CREATE TABLE app_lock (
    id                   bigint NOT NULL,
    pin_hash             varchar(100) NOT NULL,
    pin_salt             varchar(40) NOT NULL,
    iterations           integer NOT NULL,
    idle_timeout_seconds integer NOT NULL,
    failed_attempts      integer NOT NULL,
    locked_until         timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT pk_app_lock PRIMARY KEY (id),
    CONSTRAINT ck_app_lock_single_row CHECK (id = 1)
);
