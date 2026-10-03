-- Saldo Replicate inserito a mano (Replicate non espone il credito via API): riga UNICA (id = 1). Il credito mostrato nella barra in
-- basso e' balance_usd meno il costo stimato (generation.cost_usd) delle generazioni create da as_of in poi.
CREATE TABLE replicate_balance_anchor (
    id          bigint         PRIMARY KEY,
    balance_usd numeric(12, 4) NOT NULL,
    as_of       timestamptz    NOT NULL
);
