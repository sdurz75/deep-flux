# Persistenza e migrazioni

Lo schema è gestito solo da Flyway; Hibernate si limita a validarlo (`ddl-auto: validate`).

## Dove stanno le migrazioni

Le librerie portano le proprie sotto `db/migration/core` (e `db/migration/ai`); l'app le sue sotto `db/migration/app`. Flyway non ha una location esplicita: il default `classpath:db/migration` scansiona le sottocartelle.

## Come si scrive una migrazione

Il nome è `V<AAAA>_<MM>_<GG>_<HHMM>__<descrizione>.sql` e deve essere **successivo** a tutte le migrazioni esistenti di tutte le location, comprese quelle delle librerie. Un file già eseguito non si modifica mai: il checksum farebbe fallire l'avvio.

Dialetto: PostgreSQL, identificatori minuscoli non quotati, testo lungo `text` mappato con `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` (mai `@Lob`), date `timestamptz`, binari `bytea`, enum Java come `varchar` senza vincoli di tipo.

## Collegamenti fra feature

Le colonne che collegano feature diverse sono semplici `Long`: niente `@ManyToOne` verso un altro sottosistema. La chiave esterna esiste solo nella direzione delle dipendenze, e **mai dalle librerie verso l'app**.

## In sviluppo

Per ripartire da un database pulito: `docker compose down` e cancellare `data/postgres`. La slice `example/` mostra una migrazione completa con la sua entity.

## Riferimento API

Il kernel con tipi condivisi come `Paged` è in [`org.dual.hexa.core.kernel`](https://git.trenolab.com/a.duca/hexa/src/branch/main/hexa-core/src/main/java/org/dual/hexa/core/kernel).
