-- Vector store su H2 (ricerca semantica, vedi CLAUDE.md "Ricerca semantica" e H2VectorStore): un documento per riga,
-- l'embedding (float32 little-endian, normalizzato L2) in EMBEDDING. Nessuna entity JPA: accesso via JdbcClient.
-- EMBEDDING_MODEL e CONTENT_HASH permettono di reindicizzare solo cio' che e' cambiato (modello o testo).
CREATE TABLE VECTOR_DOC (
    ID              VARCHAR(100)   NOT NULL PRIMARY KEY,
    TYPE            VARCHAR(30)    NOT NULL,
    REF_ID          BIGINT         NOT NULL,
    CONVERSATION_ID BIGINT,
    CONTENT         CLOB           NOT NULL,
    METADATA        CLOB           NOT NULL,
    EMBEDDING       VARBINARY(16384) NOT NULL,
    EMBEDDING_MODEL VARCHAR(500)   NOT NULL,
    CONTENT_HASH    VARCHAR(64)    NOT NULL,
    UPDATED_AT      TIMESTAMP      NOT NULL
);
CREATE INDEX IDX_VECTOR_DOC_TYPE ON VECTOR_DOC (TYPE);
CREATE INDEX IDX_VECTOR_DOC_REF ON VECTOR_DOC (REF_ID);
CREATE INDEX IDX_VECTOR_DOC_CONVERSATION ON VECTOR_DOC (CONVERSATION_ID);
