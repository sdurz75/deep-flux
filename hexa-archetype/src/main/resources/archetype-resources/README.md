# ${appName}

Applicazione basata su [hexa](https://github.com/sdurz75/deep-flux) (`hexa-core`#if( $useAi == "true" ) + `hexa-ai`#end#if( $usePwa == "true" ) + `hexa-pwa`#end#if( $useOauth2 == "true" ) + `hexa-oauth2`#end): Spring MVC + Thymeleaf, htmx, Alpine e Tailwind senza build frontend.

**Avvio**

```
cp .env.example .env                 # imposta HX_DB_PASSWORD
docker compose up -d                 # PostgreSQL di sviluppo
mvn spring-boot:run                  # http://localhost:7070
mvn test                             # richiede Docker (Testcontainers)
mvn -Ptailwind clean package         # opzionale: CSS compilato invece del Play CDN
```

**Dove mettere le mani**: `CLAUDE.md` descrive le convenzioni e come aggiungere una pagina. La slice `example` (pagina `/example`) e' un modello da copiare e poi cancellare.

**Backup**: `java -jar target/${artifactId}-${version}.jar export backup.dfb` e `import backup.dfb` (vedi la documentazione di hexa-core).
