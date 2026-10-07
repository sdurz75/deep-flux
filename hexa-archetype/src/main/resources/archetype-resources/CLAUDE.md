# CLAUDE.md

Guida per questa applicazione, costruita su `hexa-core` (e, se scelto, `hexa-ai`): le convenzioni vengono dalle librerie, qui solo cio' che serve per estenderla.
Il package radice e' quello scelto alla generazione; nessun Maven Wrapper; Java 21.

## Cosa e' di chi

- **hexa-core** (jar): layout e UI kit Thymeleaf, eventi di sistema (`ISystemEvents`, campanella, toast), token API (`/tokens`), storage dei binari, backup (`export`/`import`),
  push SSE, manuale. Si configura con `classpath:core.yml` (importato in `application.yml`). hexa-ai, se presente, porta chat (`/deep-chat`), ricerca semantica (`/search`),
  chiamate LLM e visione, crediti OpenRouter, con `classpath:ai.yml` e `prompts.properties` (i testi dei prompt sono vostri).
- **Questa app**: tutto sotto il package radice. Punti di estensione obbligatori del layout: `templates/fragments/app/nav.html :: links(inline)` (le voci della sidebar) e
  `templates/fragments/app/status-extras.html :: container` (a destra della barra di stato). Il menu laterale e' `app.layout.nav: sidebar` in `application.yml` (`top` = barra in alto).
- Le chiavi di un file importato (`core.yml`, `ai.yml`) vincono su `application.yml`: si cambiano da env/.env, da un profilo o da `-D`, non sovrascrivendole qui.

## Architettura (esagoni)

Un sottosistema per feature: `<package>/<feature>/{domain,application,port/in,port/out,adapter/in/web,adapter/out/persistence}`. `ArchitectureTest` fa rispettare le regole (correggere il codice,
non la regola). Interfacce SEMPRE con prefisso `I` (`IExamples` -> `ExampleService`, `IExampleStore` -> `JpaExampleStore`); repository Spring Data package-private nell'adapter.
`application` non conosce web/HTTP/Spring Data; un controller parla solo con le porte `in`; fra feature solo `port.in` e `domain`. `example/` e' la slice di riferimento: copiarla, poi cancellarla.

## Convenzioni

- **Hypermedia-first**: il server risponde HTML; stessa URL, fragment se `HX-Request`, pagina intera altrimenti. Fragment restituito come vista: parametri NOMINATI (`frag(items=${items})`).
- **URL**: ogni `href`/`src`/`action`/`hx-*` passa da `@{...}` (reverse proxy su subpath). **Bottoni**: mai `<button>` a mano, fragment di `fragments/core/button.html`. **Select**: wrapper `pinesSelect`.
- **Tema**: solo Tailwind, token (`bg-canvas dark:bg-canvas-dark`...), mai colori hardcoded; la config e' `src/main/tailwind/tailwind.config.js` (unica: la usano Play CDN e CLI).
- **i18n**: ogni testo da `MessageSource` + `#{...}`, in `messages.properties` e `messages_en.properties` con le STESSE chiavi (lo verifica un test); apostrofi raddoppiati se la chiave ha argomenti.
- **Pagine**: `layout:decorate="~{fragments/core/layout}"`, contenuto in `layout:fragment="content"`, breadcrumbs in `layout:fragment="breadcrumbs"` su ogni pagina tranne la Home.
- **Errori**: ogni errore interno o chiamata remota passa da `ISystemEvents#record`/`warn`, mai un `catch` che ingoia.
- **Migrazioni Flyway**: `src/main/resources/db/migration/app/V<AAAA>_<MM>_<GG>_<HHMM>__<descrizione>.sql`, SEMPRE con timestamp piu' recente di quelle del core (1200) e di hexa-ai (1210); mai modificare una gia' eseguita.
  Nessuna `spring.flyway.locations`: il default scansiona le sottocartelle. Un DB di sviluppo si riparte con `docker compose down && rm -rf data/postgres`.
- **Binari**: tutto passa da `IImageStorageService` (core); nomi opachi, mai derivati da input.

## Nuova pagina o feature

1. Feature nuova: porte prima (tipi di dominio, mai web/Spring Data), poi adapter; copiare `example/`. 2. Controller in `adapter/in/web` + template `templates/app/<pagina>.html`.
3. Voce in `nav.html` e breadcrumbs. 4. Testi in entrambi i bundle. 5. Entity -> migrazione. 6. `mvn test` verde (richiede Docker).

## Comandi

```
cp .env.example .env && docker compose up -d      # DB di sviluppo
mvn spring-boot:run                               # sviluppo
mvn test                                          # test (Docker)
mvn test -Dtest=ArchitectureTest                  # solo architettura
mvn -Ptailwind clean package                      # CSS compilato (opzionale)
```
