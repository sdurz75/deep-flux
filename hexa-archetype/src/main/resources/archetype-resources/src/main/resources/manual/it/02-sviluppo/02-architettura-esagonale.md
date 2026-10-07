# Architettura esagonale

Ogni sottosistema, delle librerie e dell'app, è un esagono con la stessa struttura. Un test di architettura impone le regole: una violazione si corregge nel codice, non allentando il test.

## Struttura di un sottosistema

```
<sottosistema>/ domain | application | port/in | port/out | adapter/in/<tecnologia> | adapter/out/<tecnologia>
```

- `domain`: entity ed eventi; solo JDK, `jakarta.persistence` e il kernel.
- `application`: i servizi; parlano solo con le porte, mai con adapter, Spring web, Spring Data o IO di file.
- `port.in`: le capability offerte (`I<Capability>`, per esempio `IExamples`); `port.out`: ciò che serve al servizio (`I<Cosa>Store`, `I<Cosa>Gateway`).
- `adapter.in`: controller e simili, che parlano solo con porte `in`; `adapter.out`: persistenza, client remoti, storage.

## Naming

Le interfacce hanno sempre il prefisso `I`, anche le porte; le implementazioni no e portano il ruolo o il backend: `IExampleStore` è realizzata da `JpaExampleStore`. I repository Spring Data sono package-private in `adapter.out.persistence`.

## Dipendenze fra sottosistemi

Fra sottosistemi si dipende solo da `port.in` e `domain`, senza cicli. Se un sottosistema ha bisogno di dati di un altro che non lo conosce, definisce una SPI nella propria `port.in` e l'altro la implementa. Le librerie non conoscono l'app: l'app dipende dalle librerie, mai il contrario.

## ArchitectureTest

L'`ArchitectureTest` dell'app usa `HexaArchitectureRules` di `hexa-test-support`: le stesse regole delle librerie, valide per un package radice qualsiasi. Si lancia senza Docker con `mvn test -Dtest=ArchitectureTest`.

## Nei commenti

Per citare classi di altri strati si usa `{@code Nome}`, mai `{@link}`: un link creerebbe una dipendenza che la regola vieta.

## Riferimento API

Esempi di porte `in` delle librerie: [`ISystemEvents`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/events/port/in/ISystemEvents.html), [`IImageStorageService`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/storage/port/in/IImageStorageService.html), [`IManual`](https://sdurz75.github.io/deep-flux/apidocs/org/hexa/core/manual/port/in/IManual.html).
