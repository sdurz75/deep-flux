-- I token API e i segreti dei moduli sono la STESSA entita': un segreto con nome e TIPO. api_token diventa secret (provider -> type, token_encrypted ->
-- value_encrypted, token_hint -> hint); i valori dei tipi (HUGGINGFACE, CIVITAI...) restano. Le FK che puntano alla tabella (es. lora_preset) seguono il rename.
ALTER TABLE api_token RENAME TO secret;
ALTER TABLE secret RENAME COLUMN provider TO type;
ALTER TABLE secret ALTER COLUMN type TYPE varchar(40);
ALTER TABLE secret RENAME COLUMN token_encrypted TO value_encrypted;
ALTER TABLE secret RENAME COLUMN token_hint TO hint;
ALTER TABLE secret RENAME CONSTRAINT pk_api_token TO pk_secret;
ALTER TABLE secret RENAME CONSTRAINT uq_api_token_provider_name TO uq_secret_type_name;

-- Registro eventi: la sorgente TOKENS diventa SECRETS e il subject 'token:<id>' diventa 'secret:<id>'.
UPDATE system_event SET source = 'SECRETS' WHERE source = 'TOKENS';
UPDATE system_event SET subject = 'secret:' || substring(subject FROM 7) WHERE subject LIKE 'token:%';
