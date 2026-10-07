# Esempio

Questa pagina e' un segnaposto del manuale online di hexa-core: si cancella insieme alla feature di esempio e si sostituisce con le pagine della tua app.

## Come si scrive

Le pagine stanno in `src/main/resources/manual/<lingua>/<NN-gruppo>/<NN-pagina>.md`. Il titolo e' il primo `#`; ogni sezione (`##`) e' l'unita' che il manuale cerca e cita. Uno slug (il nome del file senza prefisso numerico) e' unico fra tutti i gruppi. Una lingua senza cartella ripiega sull'italiano.

## Dove si vede

La pagina e' servita da [/manual](/manual); l'etichetta del gruppo e' la chiave `manual.group.uso` del bundle dell'app. Per l'elenco delle voci vedi l'[esempio](/example).
