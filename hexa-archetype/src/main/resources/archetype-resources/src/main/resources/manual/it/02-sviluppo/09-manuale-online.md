# Il manuale online

Questa guida è essa stessa il manuale: le pagine sono file Markdown dell'app, servite da [/manual](/manual) e convertite in HTML a ogni richiesta dal sottosistema `core.manual`. La si può usare come modello per le pagine della propria app.

## Dove stanno i file

```
src/main/resources/manual/<lingua>/<NN-gruppo>/<NN-pagina>.md
```

La lingua è il codice a due lettere (`it`); una lingua senza cartella ricade sull'italiano, quindi aggiungere `manual/en/` offre il manuale in inglese. Il gruppo è la cartella senza prefisso numerico (`02-sviluppo` è `sviluppo`) e il suo nome nell'indice è la chiave `manual.group.<gruppo>` del bundle dell'**app**. La pagina ha un prefisso di due cifre che ne decide l'ordine; lo **slug** (l'indirizzo `/manual/<slug>`) è il nome senza prefisso ed estensione e deve essere unico fra tutti i gruppi.

## Come si scrive

- Il titolo è il primo `#`, e uno solo per pagina; il riassunto nell'indice è il primo paragrafo.
- I titoli `##` sono le **sezioni**: l'unità che la ricerca trova e cita (massimo 6.000 caratteri ciascuna).
- Nessuna formattazione dentro un titolo: le ancore devono restare prevedibili. L'ancora è il titolo in minuscolo, senza accenti, con i trattini.
- Le etichette dell'interfaccia vanno in grassetto, come le vede l'utente, con gli accenti veri.
- L'HTML scritto in un `.md` non viene eseguito: è mostrato come testo.

## I link

| Cosa | Come si scrive |
|---|---|
| Un'altra pagina | il nome vero del file, per esempio `03-configurazione.md#i-file` |
| Una pagina dell'app | un percorso dalla radice, per esempio `/example` |
| Una sezione della stessa pagina | solo l'ancora, `#i-file` |
| Un sito esterno | l'indirizzo completo |

I link ai file `.md` funzionano anche su GitHub e negli IDE. Davanti ai percorsi dell'app il convertitore aggiunge il prefisso del reverse proxy, se c'è.

## Gruppi e assistente

Il gruppo `uso` è quello pensato per l'utente finale e, con hexa-ai, è anche l'unico che l'assistente di chat consulta con il proprio strumento del manuale. Gli altri gruppi, come questo, sono solo per la lettura.

## Tenerlo aggiornato

Quando cambia ciò che l'utente vede o fa, si aggiorna la pagina che lo descrive. Nessun test si accorge che un testo è diventato vecchio.

## Riferimento API

[`IManual`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/manual/port/in/IManual.html) (`contents`, `page`, `sections`, `search`) e il package [`org.dual.hexa.core.manual.port.in`](https://sdurz75.github.io/deep-flux/apidocs/org/dual/hexa/core/manual/port/in/package-summary.html).
