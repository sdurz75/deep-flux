-- hexa-oauth2 passa al modulo di configurazione del core (provider, elenchi e interruttore sono campi di IConfigModule; il segreto del client e' un segreto
-- di tipo OAUTH2_CLIENT). Le tre tabelle precedenti non erano mai state rilasciate: si tolgono senza migrare i dati.
DROP TABLE IF EXISTS oauth2_allowed_user;
DROP TABLE IF EXISTS oauth2_provider;
DROP TABLE IF EXISTS oauth2_gate;

-- L'ultimo accesso riuscito di ogni email ammessa: lega issuer+subject al primo accesso e prova che un accesso e' riuscito (precondizione per accendere).
CREATE TABLE oauth2_login (
    email         varchar(255) NOT NULL,
    issuer        varchar(300) NOT NULL,
    subject       varchar(255) NOT NULL,
    last_login_at timestamptz NOT NULL,
    CONSTRAINT pk_oauth2_login PRIMARY KEY (email)
);
