# CLAUDE.md

Guida di riferimento per questo repository. Leggerla prima di aggiungere pagine, endpoint o dipendenze: le scelte
sono vincoli deliberati per mantenere il progetto snello. Package radice: `org.dual.replicate`. Java 21, Maven, UN solo modulo.

Il codice e' diviso in un **`core`** generico e riusabile (layout/fragments, remote+retry, eventi di sistema, push SSE, secrets/token,
storage dei binari, manuale online) e un'**`app`** specifica (generazione immagini, galleria, chat, ricerca semantica, addestramento di LoRA), entrambi organizzati in esagoni
(ports & adapters) uno per sottosistema: vedi "Architettura". Il repo e' pensato anche come template per una nuova webapp (si tiene il
`core`, si sostituisce `app`): vedi `docs/TEMPLATE.md`.

## Scopo

L'app serve a quattro cose (single-user: `Generation` non ha owner, solo multi-conversazione):

### 1. Generare immagini con l'ausilio di un chatbot

- `/deep-chat`: l'assistente puo' cercare sul web (`WebSearchTool`, SearXNG) e generare su Replicate
  (`ImageGenerationTool`) SEMPRE col modello scelto nel combobox UI (`ImageGenerationTool.MODEL_CONTEXT_KEY` via
  `ToolContext`, non un parametro scelto dall'LLM; solo `SpringAiAssistant` costruisce il `ToolContext`). `/generations/new` e'
  la via diretta (form, senza chatbot).
- `ImageGenerationTool` avvia e torna subito, con l'id della generazione (`Generation #12 started ...`: il bot puo' citarla, annullarla, taggarla); il polling continua in background (`ChatGenerationWatcher`, `@Async`)
  e il risultato arriva come nuovo turno di chat via SSE (`GET /events`, `EventStreamController`, `IClientPush`; il lato chat e'
  `ChatPushNotifier`): nessun polling client-side per la chat. E' l'UNICO tool a pagamento, con un tetto per turno (`app.chat.max-generations-per-turn`, 3).
- **Placeholder** mentre una generazione e' in corso (chat e `/generations/{id}`): `fragments/app/generation-placeholder.html`
  (immagine dummy + "Interrompi", stili INLINE perche' finisce anche nello shadow DOM di `<deep-chat>`). In `/generations/{id}` i riquadri sono tanti quanti i file richiesti (`Generation#getRequestedOutputs`, da `num_outputs` in
  `parametersJson`; 1 se assente) con UN solo "Interrompi" in una riga sotto, centrato (anche con un file); tutto il blocco e' centrato nella pagina (wrapper `#generation-status` con `flex justify-center`). In chat resta un riquadro (il template non conosce il numero). Il bottone chiede
  conferma e fa `POST /generations/{id}/cancel` (`IGenerations#cancel`): esito `FAILED` "annullata"; se il cancel
  fallisce il bottone si disabilita e si attende la fine naturale. In chat i placeholder viaggiano nella risposta del
  turno (`generationIds`) e al reload si ripristinano via `Generation.conversationId`. A fine generazione il
  risultato (`fragments/app/generation-result.html`, stili inline, toggle `button-gen :: galleryToggle`, handler `gen-toggle` in
  `templates/app/deep-chat.html`) rimpiazza il placeholder nello stesso messaggio `html`; la cronologia ricaricata usa lo stesso markup.
- `/generations/{id}` (form diretto) fa polling htmx ogni 2s finche' non e' terminale; a stato terminale quella stessa
  pagina (`fragments/app/generation.html :: status`) e' anche l'UNICO dettaglio: prompt/modello/seed/parametri, tutte le
  immagini in griglia con lightbox (`fragments/app/generation-images.html`) cancellabili singolarmente, cancellazione
  dell'intera generazione.
- **Video (img2video/text2video)**: stessa pipeline. `Generation.kind` (`GenerationKind` IMAGE/VIDEO) deriva da
  `GenerationFormType#kind()`; unico modello video `prunaai/p-video` (`P_VIDEO`, `PVideoParameterHandler`). L'mp4 sta in
  `imageFilenames`; cambiano solo rendering (`<video>`, niente lightbox) e timeout (15 min invece di 5, `GenerationService`).
  - Ingresso: icona overlay "Anima" (`button-gen :: animateOverlay`) su OGNI thumbnail (`/gallery`, galleria di chat,
    griglia dettaglio) → `/generations/new?source={id}&sourceImage={filename}` (la sorgente e' quel file preciso; filename
    non della generazione → sorgente ignorata). Preseleziona p-video, hidden `sourceGenerationId`+`sourceImage`;
    `GenerationService#create` la invia come data-URI (`IImageStorageService#readAsDataUri`,
    `Generation.sourceGenerationId`, FK `ON DELETE SET NULL`); e' valida solo un'immagine RIUSCITA (altrimenti "sorgente mancante", mai un text-to-video
    silenzioso: regola in `GenerationService`, la UI la interroga con `IGenerations#findAnimatableSource`). Senza sorgente p-video e' text-to-video.
  - **Upload stand-alone**: link "Genera video" (`/generations/new?kind=video`); il fragment p-video ha
    `<input type=file name=sourceUpload>` (form `hx-encoding=multipart`). `IImageStorageService#storeUpload` valida magic
    bytes (png/jpeg/webp, max `IImageStorageService.MAX_UPLOAD_BYTES` = 10 MB), salva con un nome nuovo (NON una `Generation`), lo traccia in
    `Generation.sourceUploadFilename`; ha precedenza sulla sorgente "Anima"; eliminato con la generazione (o se la
    creazione fallisce). Il controller costruisce un `UploadedFile` (tipo di dominio dello storage: la porta non vede `MultipartFile`) e lo mette in `CreateCommand#sourceUpload`:
    lo salva `GenerationService#create` (solo se il modello prende una sorgente), decide la precedenza e lo elimina se la creazione fallisce.
  - `/deep-chat` propone SOLO modelli immagine (`IModelCatalog#models(GenerationKind)`). `disable_safety_checker`
    forzato solo per le immagini. Fuori scope: audio-to-video, video in chat.
- **Modelli a sorgente obbligatoria (flux-kontext-dev, flux-fill-dev/pro)**: NON esiste piu' una pagina o un overlay "Modifica" a parte: per l'utente la
  domanda e' "parto da un'immagine o da zero?", e kontext/fill sono semplicemente modelli del combobox immagini di `/generations/new`, segnati
  "richiede un'immagine" (`generationParams.option.needsImage`; la `<select>` Pines non ha optgroup). Il discriminatore e'
  `GenerationFormType#sourceRequired()` (ex `isEdit`): senza sorgente il modello non ha senso e l'output ne eredita dimensione e composizione
  (`match_input_image`/`match_input`, niente `prompt_strength`: la preservazione e' strutturale, non una forza come nell'img2img di ff3/dev-lora);
  `maskRequired()` = `takesMask() && sourceRequired`, `isInstructionEdit()` = `sourceRequired && !takesMask()` (kontext: il prompt e' un'istruzione, `enhanceEdit`).
  `IModelCatalog`: `models(kind)`/`contains(id, kind)`/`defaultModel()` = i modelli che funzionano SENZA sorgente (chat, default, "Reimposta ai default" ed
  `ImageGenerationTool`: la chat non puo' darne una); `formModels(kind)` = TUTTI i modelli del tipo (solo il combobox del form). I vecchi link `?kind=edit`
  valgono `kind=image`. Kontext: produce un'IMMAGINE (kind IMAGE, pipeline invariata), sorgente sotto `GenerationFormType#sourceImageParam()` (`input_image`;
  p-video usa `image`): upload stand-alone (`sourceUpload`, fragment `generation-params-source-upload.html`) o overlay "Usa come sorgente"
  (`button-gen :: sourceOverlay`, sceglie per default ff3/dev-lora: kontext e fill si scelgono dal combobox); senza sorgente
  `GenerationService#create` fallisce prima di chiamare Replicate. "AI enhance" usa
  `IPromptEnhancer#enhanceEdit` (visione sulla sorgente, guida `generateForm.edit-prompt-enhancement-guide` in `prompts.properties`;
  serve una bozza). Un'immagine modificata e' una normale immagine (ri-modificabile/animabile).
  **Sorgente da generazione e cambio modello**: la sorgente "Usa come sorgente"/"Anima" sta in hidden (`sourceGenerationId`, `sourceImage`) FUORI da `#generation-params-fields`, e il fragment dell'upload (`generation-params-source-upload.html`, `th:unless="${sourceGeneration}"`) la riconosce solo se `sourceGeneration` e' nel Model. `GET /generations/params` (swap dei campi) la rivalida (`animatableSource`) e la rimette nel Model, ma deve riceverla: tutti e TRE i percorsi che lo chiamano la portano (select modello: `hx-include` con i due hidden; restore di `generation-settings-persist.html`: `sourceQuery(form)`; "Reimposta ai default": URL con i due parametri). Senza, passando a un modello con maschera (fill-dev/pro) l'upload obbligatorio ricompariva e il submit chiedeva un file nonostante la sorgente; la maschera si applica a quell'immagine (`GenerationService#doCreate` manda `image` dalla generazione + `mask`). Nello swap fra modelli `guidance`, `guidance_scale`, `num_inference_steps` e `steps` NON si ereditano (`GenerationController#MODEL_SCALED_FIELDS`: scala e `step` propri per modello, un valore di kontext invalidava il campo di fill-dev e bloccava il submit); gli altri campi comuni si'. Non vale per "Usa configurazione" e per il re-render dopo un create rifiutato (stesso modello).
- **LoRA al volo (flux-dev-lora)**: `black-forest-labs/flux-dev-lora` (`GenerationFormType#FLUX_DEV_LORA`,
  `FluxDevLoraParameterHandler`) e' un normale modello IMAGE (compare nel combobox e in `/deep-chat`) con `lora_weights`/
  `extra_lora` (+ scale: Replicate `owner/nome`, URL HuggingFace/CivitAI o `.safetensors`; vuoti = FLUX dev puro) e
  img2img OPZIONALE da upload (`sourceUpload`, `sourceImageParam()` = `image`, + `prompt_strength`; il blocco upload e'
  nascosto nel pannello di `/deep-chat`; con un upload "AI enhance" usa `IPromptEnhancer#enhanceImg2Img`: guida `generateForm.img2img-prompt-enhancement-guide`, descrive il risultato finale, guarda la sorgente e legge `prompt_strength` (accodata alla bozza, inclusa via `#param-prompt-strength`: bassa = solo cio' che cambia, alta = prompt completo; senza upload resta la guida text-to-image). NON ha overlay sui thumbnail: "Anima"/"Modifica" non portano a questo modello.
  **Token** per i LoRA privati: NON si digitano nel form ma si scelgono PER NOME (select) fra quelli salvati in `/tokens`
  (vedi "Token API"). Al server arriva l'ID (`hf_token_id`/`civitai_token_id`, in `PARAMETERS_JSON` resta l'ID): il token in
  chiaro esiste solo in `GenerationService#doCreate`, che con `TokenInputResolver#resolveInto` (generation.application, sopra
  `IApiTokens#resolve`) lo mette in `hf_api_token`/`civitai_api_token` dell'input per Replicate, PRIMA di chiamarlo (un token
  inesistente o scaduto e' un rifiuto, nessuna prediction).
  **LoRA anagrafati**: CRUD in `/loras` (`LoraController`, `ILoraPresets`/`LoraPresetService`, `LoraPreset`, `fragments/app/loras.html`,
  voce nel menu Sistema), solo per comodita': nome, sorgente, intensita' predefinita, trigger words, nota. Sopra i due slot LoRA
  della form (`generation-params-flux-dev-lora.html`, select nel fragment condiviso `generation-params-lora-preset.html :: field`, `loraPresets` nel Model dove si mettono gia' i token) una select Pines SENZA
  `name` compila testo e scala (restano modificabili, "testo libero" non tocca nulla) e mostra le trigger words con "Aggiungi al prompt"
  (`button-gen :: addToPrompt`, solo se c'e' `#prompt`: non nel pannello di `/deep-chat`). La select e' DERIVATA, mai persistita: dopo un restore (localStorage/server-state) o "Usa configurazione" l'`x-init` del fragment la riposiziona sul primo preset con la stessa sorgente del campo (nessuno = "testo libero"; senza `change` sintetico, che ricopierebbe la scala). E' un aiuto lato client: al server arrivano
  sempre testo e scala, nessuna FK dalla `Generation`, cancellare/modificare un preset non tocca le generazioni passate.
  **Preset = modello**: una sorgente nella forma `owner/nome` (NON URL, versioni `:hash` o `.safetensors`, ne' riferimenti con host come `huggingface.co/...`, `hf.co/...`, `civitai.com/models/N`, `civitai:N`: l'owner Replicate non ha mai un punto, e questa e' la regola che li esclude tutti, `ReplicateModel#identifierOfSource`) e' implicitamente un LoRA addestrato su Replicate, quindi creare/modificare il preset lo censisce anche in `replicate_model` come `FLUX_LORA_FINETUNE` (`LoraPresetService` → `IModelCatalog#registerLoraFinetune`): l'ultima versione e lo schema d'input li legge `IPredictionGateway#latestVersion` (GET `/models/{owner}/{name}`, sola lettura, nessuna prediction) e la versione si pinna; da quel momento compare ovunque compaia ff3 (combobox immagini, `/deep-chat`, `LibraryTool`, costo, "Usa configurazione"). Idempotente (gia' censito, anche disattivato = intatto). Un modello inesistente o con schema noto senza `lora_scale` e' un `REJECTED` silenzioso (la sorgente puo' essere altro), un guasto del servizio un evento di sistema (`registerLoraModel`): il preset resta salvato in ogni caso. Cancellare il preset NON toglie il modello (le generazioni passate lo referenziano; per disattivarlo `UPDATE replicate_model SET active=false`). I preset salvati prima di questa regola si censiscono risalvandoli. Nei `@SpringBootTest` che creano preset serve `@MockitoBean IPredictionGateway` (mai la rete vera).
- **LoRA addestrati su Replicate (`FLUX_LORA_FINETUNE`, es. `sdurz75/flux-lora-ff3`)**: UN solo form-type GENERICO (`FluxLoraFinetuneParameterHandler`, `generation-params-flux-lora-finetune.html`) per tutti i modelli di questo tipo: stesso schema di input, stesso funzionamento. Un altro fine-tune si censisce con un `INSERT` in `replicate_model` (`version` = hash pinnato, `form_type` = `FLUX_LORA_FINETUNE`), senza codice (il costo e' stimato dal form-type, vedi "Costo"). Schema della versione pinnata di ff3 (letto da Replicate il 2026-10-03, coincide con l'ultima): ha `image`, `mask` e `prompt_strength`, quindi il fine-tune si puo' usare anche per img2img/inpainting. Resta un normale text-to-image (`sourceRequired` falso: compare nel combobox immagini e in `/deep-chat`; `(owner,name)` e' UNIQUE in `replicate_model`, quindi non si puo' censirlo due volte con un form-type a sorgente obbligatoria): `GenerationFormType.FLUX_LORA_FINETUNE` ha `sourceImageParam()` = `image` e `maskParam()` = `mask` ma `maskRequired()` falso (`maskRequired()` = `takesMask() && sourceRequired()`: solo flux-fill-* rifiutano la generazione senza maschera). Regola in `GenerationService#doCreate`: nessuna maschera = generazione normale; maschera + sorgente = `image` e `mask` a Replicate; maschera senza sorgente = `generation.error.maskNeedsSource` PRIMA di chiamare Replicate (file salvati ripuliti). Il fragment (`generation-params-flux-lora-finetune.html`) ha upload sorgente opzionale, editor maschera (`mask-editor :: field`) e `prompt_strength` (default 0.8; per ridipingere del tutto la zona dipinta serve un valore alto) in un blocco nascosto nel pannello di `/deep-chat` come per flux-dev-lora; l'editor sta in un `<template x-if>` perche' il componente `maskEditor` e' registrato solo da `generate.html`. Con un'immagine il modello ignora `aspect_ratio`/`width`/`height` e la dimensione la decide `megapixels` (select `0.25`/`1`, nel blocco sorgente: e' un enum dello schema, niente step da 0,5; per oltre 1 MP serve flux-fill-dev con `match_input`, fino a 1440x1440). L'enum di `megapixels` di OGNI form-type e' una costante `MEGAPIXELS` dell'handler (`asOneOf`) e `GenerationFormFieldsCompletenessTest#megapixelOptionsOfEveryFormMatchTheHandlerEnum` ne verifica l'allineamento con le opzioni del fragment. "AI enhance": `hx-include` manda anche `maskUpload` e `enhancePrompt` usa `enhanceInpaint` solo se la maschera c'e' (o il modello la richiede), `enhanceImg2Img` con la sola sorgente, altrimenti text-to-image. La maschera e' il PNG dipinto nell'editor, come per flux-fill (stesso `Generation.maskUploadFilename`, stessa anteprima nel dettaglio). **LoRA extra**: lo schema (di ff3) ha anche `extra_lora`/`extra_lora_scale` (letto il 2026-10-03; NON ha `lora_weights` ne' i token HF/CivitAI, quindi solo sorgenti pubbliche): il fragment ha la select dei preset `/loras` (`GenerationFormService#extraFormOptions` mette `loraPresets` come per flux-fill-dev), il testo e la scala, come lo slot "aggiuntivo" di flux-dev-lora. Nota: e' l'inpainting img2img di flux-dev (non di Fill): si appoggia a `prompt_strength` e puo' dare cuciture piu' evidenti di flux-fill-dev, che resta la scelta per i bordi puliti.
- **Inpainting (flux-fill-dev, flux-fill-pro)**: `black-forest-labs/flux-fill-dev` (`GenerationFormType#FLUX_FILL_DEV`, `FluxFillDevParameterHandler`) e' un
  secondo modello a sorgente obbligatoria (`sourceRequired`, nel combobox immagini, `sort_order` 6): sorgente
  E maschera OBBLIGATORIE (`GenerationFormType#maskParam()` = `mask`, `takesMask()`; `image` per la sorgente). `black-forest-labs/flux-fill-pro` (`FLUX_FILL_PRO`, `FluxFillProParameterHandler`, `sort_order` 7) e' lo stesso flusso a qualita' massima ma SENZA LoRA ne'
  `num_outputs` (una prediction = un'immagine, ~0,05 $, `ReplicatePricing` a prezzo fisso); non ha `disable_safety_checker` (`GenerationFormType#hasDisableSafetyChecker()`) ma
  `safety_tolerance` (`safetyToleranceParam()`), che `GenerationService` forza SEMPRE a 6 (il piu' permissivo) e che non e' un campo del form. Il flux-dev-lora NON ha `mask` (schema
  letto da Replicate): per l'inpainting col proprio LoRA si usa Fill, che ha UN solo `lora_weights`/`lora_scale` (niente `extra_lora`, niente token:
  solo LoRA pubblici) e la select dei preset `/loras` come per dev-lora. La maschera (PNG, BIANCO = da ridipingere, NERO = da preservare) si DIPINGE nel
  browser con l'editor `fragments/app/mask-editor.html` (componente Alpine `maskEditor`, registrato nello `:: script` incluso da `generate.html` e
  NON nel fragment dei campi, che si sostituisce al cambio modello; markup in `:: field`): canvas alla dimensione naturale della sorgente (tetto 4096 px),
  pennello/gomma/ellisse come lista di operazioni, dialog Pines, slider "Sfumatura" con i valori in PIXEL accanto a "Dimensione" e "Sfumatura" e la dimensione dell'immagine (e della maschera se ridotta) sopra la barra degli strumenti (raggio in pixel del canvas, 0-25, default 8: oltre non ha senso; bordi SFUMATI nel PNG esportato, grigi: tre passate di box blur
  sul solo alpha, `featherAlpha` nello script, indipendente dal browser e con la stessa sigma mostrata nel canvas dal CSS blur; la maschera sfumata e' quella che parte per Replicate); `Applica` esporta il PNG nell'`<input type=file name=maskUpload>` via `DataTransfer`
  (multipart come la sorgente: niente base64 in campi di testo, che finirebbe in localStorage). La sorgente la legge dal DOM all'apertura
  (`sourceUpload` scelto, altrimenti `#generate-source-preview`); se cambia la maschera si azzera. Lato server `CreateCommand#maskUpload`
  (`UploadedFile`) la salva `GenerationService#create` solo se `takesMask()`, la manda come `mask` data-URI e la traccia in `Generation.maskUploadFilename`
  (`generation.mask_upload_filename`, mostrata nel dettaglio); si elimina con la generazione o se la creazione fallisce, come l'upload sorgente. Senza
  maschera `generation.error.maskRequired` PRIMA di chiamare Replicate. Nessun controllo lato server che maschera e sorgente abbiano le stesse dimensioni
  (l'editor le garantisce). **Anteprima**: la maschera si vede SOPRA l'immagine originale (50%, trasparente fuori area), mai come miniatura bianco/nero: `fragments/app/mask-overlay.html` (`layer` per il dettaglio, `layerBound` per il form) converte al volo il PNG bianco/nero in colore `favourite` con alpha = luminanza con un filtro SVG (`feColorMatrix luminanceToAlpha`; NON `mask-mode: luminance`, rotto su Safari, ne' `mix-blend-mode`, che sparisce su immagini chiare/scure). Nel form l'editor annuncia l'URL del PNG con l'evento window `mask-changed` (null = nessuna maschera, anche da `destroy()` allo swap del fragment) e le due anteprime della sorgente (`generate-form.html` per una generazione, `generation-params-source-upload.html` per un upload) ci sovrappongono l'overlay; nel dettaglio serve `Generation.sourceImageFilename` (`generation.source_image_filename`: il file scelto fra quelli della generazione sorgente, null con un upload o per le righe precedenti, senza cui non si mostra la maschera). "AI enhance" per i modelli con maschera usa `IPromptEnhancer#enhanceInpaint` (guida `generateForm.inpaint-prompt-enhancement-guide` in `prompts.properties`,
  `PromptGuidesTest#inpaintGuideKeepsTheInpaintingPractices`): il prompt descrive SOLO cio' che compare nella zona dipinta (mai scena, sfondo o inquadratura, che creano
  cuciture e segnali in conflitto con i bordi), in prosa breve (~15-40 parole), senza negazioni, con la trigger word del LoRA intatta; il modello di visione guarda la
  sorgente solo per adattare luce, prospettiva, orientamento del volto e stile, NON vede la maschera (la deduce dalla bozza). Ne' `enhanceEdit` (istruzioni di Kontext) ne'
  la guida generica text-to-image, che chiederebbe ambientazione, luce e inquadratura. Fonti: guide della community su FLUX Fill (la documentazione BFL parla solo della maschera).
  Fuori scope per ora: ritaglio+ricomposizione attorno alla maschera (per volti piccoli in figure intere
  l'inpainting a immagine intera rigenera alla stessa risoluzione), overlay "Inpaint" sui thumbnail, espansione automatica della maschera (la sfumatura c'e': una pennellata sottile sfumata perde intensita', il blob di un volto no).
- **Costo**: il dettaglio mostra il costo *stimato* (Replicate espone solo `metrics`). `ReplicatePricing` (statica, in
  `generation.domain`, una regola per modello censito — un nuovo modello richiede anche la sua regola, tranne i fine-tune `FLUX_LORA_FINETUNE`, stimati dal form-type a tempo di calcolo H100: ipotesi, l'hardware e' per modello e uno su altra GPU vuole una regola esplicita, che ha la precedenza) lo calcola da
  `PredictionResponse.metrics`; `IGenerations#refresh` lo salva in `generation.cost_usd`; assente per generazioni vecchie, fallite o senza regola.

### 2. Indicizzare le immagini in un archivio

- Ogni generazione (chat o form) e' una riga `Generation`. `/gallery` = solo SUCCEEDED, paginata, cancellazione in blocco,
  si aggiorna via SSE (`GalleryPushNotifier`, evento `gallery-update`); le card linkano a `/generations/{id}` (nessun dettaglio proprio).
  `/generations` = listato paginato di TUTTE le generazioni, selezione multipla (shift-click), cancellazione selezione/intero
  archivio (conferma testuale rinforzata, `fragments/app/generations.html`).
- Cancellare una generazione elimina i file; cancellare l'ultima immagine elimina a cascata la generazione
  (`IGenerations#deleteImage`).
- **Preferiti (star)**: ogni file (immagine/video) puo' avere la star (overlay rosa, token `favourite`,
  `button-gen :: starOverlay`, `POST /generations/{id}/favourite`, `IGenerations#toggleFavourite`,
  `Generation.favouriteFilenames`) su `/gallery`, galleria di chat, dettaglio. `/gallery` ha tab `?tab=all|favourites`:
  Tutte (una card per generazione) e Preferiti (una card per file, `GalleryItem`, senza checkbox/cancellazione in
  blocco). `deleteImage` toglie anche la star.
- **Tag utente** (immagini importate, generazioni, singoli file, conversazioni di `/deep-chat`; NON sono i tag AI `analysisTags` delle importate, che `applyAnalysis`
  riscrive al "Riprova", ne' le tag d'indice `#tags:` di `GenerationSearchText`): per generazione (`Generation.tags`, tabella `generation_tag`) E per file
  (`Generation.fileTags`, `FileTag`, `generation_file_tag`, sottoinsieme di `imageFilenames`: `removeFiles` li toglie col file); per conversazione
  `ChatConversation.tags` (`chat_conversation_tag`). Normalizzazione UNICA `app.shared.domain.Tags` (minuscolo, spazi collassati, niente virgole/virgolette/backslash
  perche' finiscono in un filtro jsonpath, max 40 caratteri, `MAX_PER_ENTITY` 20): il match nell'indice e' esatto. Casi d'uso: `IGenerations#addTag/removeTag(id, filename|null, tag)`
  (stessa guardia anti path-traversal di `toggleFavourite`; `allTags` per i suggerimenti), `IChatConversations#addTag/removeTag` (come `rename`: niente `touch()`).
  Eventi `GenerationTagsChangedEvent` e `ChatConversationChangedEvent` (pubblicato anche da `rename`: il titolo e' il testo indicizzato) → riconciliazione dell'indice
  subito (`GenerationSearchSource`, `ChatSearchSource`). UI: `fragments/app/tag-editor.html` (`generationEditor` per generazione e per file, `conversationEditor` nella sidebar:
  chip con "x" e un campo che aggiunge con Invio, form htmx `data-busy="off"`, endpoint `POST /generations/{id}/tags/add|remove` e `POST /deep-chat/{id}/tags/add|remove`;
  UNA `<datalist id="known-tags">` per pagina con `@generationService.allTags()`, quindi un solo editor per pagina passa `suggestions=true`). Nella sidebar l'editor c'e' per la
  conversazione aperta e per quelle gia' taggate. Chip di sola lettura nelle card di `/gallery` e nelle righe di `/search`. Ricerca: `/gallery?tag=` (tutte le tab; tag della
  generazione O di un suo file; nei preferiti, della generazione o di QUEL file), campo `tag` in `/search`, parametro `tag` di `ArchiveSearchTool`. Un test che renderizza un
  editor in una transazione con righe incoerenti (FK) fallisce al flush della query dei suggerimenti: nei test le righe devono essere valide.
- **Immagini esterne (import)**: l'archivio riceve anche immagini NON generate. Un'immagine importata e' una normale `Generation` con
  `origin=IMPORTED` (`GenerationOrigin`; `model`/`externalId` NULL, kind IMAGE, SUCCEEDED subito): galleria, star, cancellazione, lightbox,
  "Anima"/"Usa come sorgente", dettaglio e indice sono quelli di sempre. Per le importate `Generation#prompt` e' la DESCRIZIONE prodotta dall'analisi.
  - **Ingresso**: pagina `/import` (`ImportController`, voce `Crea ▾`): form multipart (`images`, `multiple`, funziona SENZA JS: un submit nativo
    risponde con la pagina intera) + dropzone Alpine `importDropzone` (`fragments/app/import-dropzone.html`: drag&drop, click, incolla,
    anteprime, validazione client, riscrive l'`<input type=file>` via `DataTransfer`). Con htmx risponde il solo fragment `import-result.html`
    (esito PER FILE: un rifiuto non ferma gli altri). Tetti: `IImageStorageService.MAX_UPLOAD_BYTES` per file, `app.import.max-files` (20, `IImportedImages#maxFiles`),
    `spring.servlet.multipart.max-request-size` (`core.yml`, 250 MB). L'helper `UploadedFiles` (adapter web) costruisce `UploadedFile` da `MultipartFile`
    (lo usa anche `GenerationController`). "Ultime importate" (`GET /import/recent`, si ricarica a `gallery-update`).
    **Dettaglio**: pagina PROPRIA `GET /import/{id}` (`ImportController#detail`, `templates/app/import-detail.html`, titolo "Immagine importata #n", breadcrumbs Crea › Importa immagini › pagina): immagine (stessa griglia `generation-images :: grid`, quindi star, lightbox, "Anima"/"Modifica"/"Usa come sorgente"), analisi (descrizione, tag, "Riprova"), "Usa la descrizione come prompt" e data di importazione; NIENTE prompt/modello/costo/seed/"Usa configurazione" (non c'e' stata una prediction). `/generations/{id}` di una importata reindirizza qui (`GenerationController#renderStatus`: i link di chat, ricerca ed eventi restano validi) e `/import/{id}` di una non importata a `/generations/{id}`; i link diretti (galleria, elenco, ricerca, import) puntano gia' a `/import/{id}`. `fragments/app/generation.html` non ha piu' rami per le importate.
  - **Caso d'uso** (`ImportedImageService`, impl di `IImportedImages`): `storeUpload` per file, riga `Generation.imported`, poi `GenerationCompletedEvent`
    (la miniatura compare subito in `/gallery` via SSE, stesso canale delle generazioni) e `ImageImportedEvent`. Nessun dedup: lo stesso file due volte = due righe.
  - **Analisi del contenuto** (`Generation.analysisStatus` PENDING/DONE/FAILED, `analysisTags`): `ImportAnalysisListener` (`adapter.in.async`,
    `@Async("importAnalysisExecutor")` a 2 thread + `@TransactionalEventListener(fallbackExecution = true)`) chiama `IImportedImages#analyze`, che e' IDEMPOTENTE
    (esce se non PENDING) e usa `IImageDescriber` (esagono `prompt`: `ImageDescriptionService`, stesso modello di visione e stessa logica fallback/rifiuto
    dell'enhancer, estratta in `VisionRunner`; guida `imageAnalysis.guide` in `prompts.properties`, SENZA `prompts.creative-context` (i suoi limiti riguardano la generazione e fanno rifiutare le foto di persone: l'immagine c'e' gia', si descrive tutto senza filtri; `PromptGuidesTest#imageAnalysisGuideDoesNotImportGenerationLimitsAndNeverAsksToRefuse`); risposta
    `DESCRIPTION:`/`TAGS:`). Un rifiuto o una risposta illeggibile (`ImageAnalysisException`, REJECTED) = `FAILED` senza toast; un guasto del servizio = `FAILED` +
    `ISystemEvents#record("analyzeImportedImage", ...)`. L'immagine resta usabile come sorgente anche con l'analisi fallita. `POST /import/{id}/retry`
    ("Riprova l'analisi") la riporta a PENDING; `GET /import/{id}/analysis` e' il riquadro che si ricarica da solo ogni 3 s finche' e' PENDING
    (`fragments/app/import-analysis.html :: box`, usato anche dal dettaglio). Nessuno stato indefinito: `GenerationRecoveryService` (avvio e sweep)
    chiama `IImportedImages#recoverPendingAnalyses` per le PENDING piu' vecchie di 5 min.
  - **Indice**: SOLO le importate con analisi `DONE` entrano nell'indice (`GenerationSearchSource`): testo = descrizione + tag
    (`GenerationSearchText`: "immagine importata, imported image" + tag AI), `model` solo se non null (i metadata di `Document` non
    accettano null). Tipo d'indice PROPRIO `imported` (`DocumentTypes.IMPORTED`, id `imported:<id>`, stessa sorgente dati `GenerationSearchSource`, che dichiara entrambi i `types()`): `/search` ha la voce "Importate" nel filtro tipo, link a `/import/{id}`; media e preferiti valgono per `generation` e `imported` (gli altri tipi non hanno quei metadata). `ArchiveSearchTool` accetta il filtro `imported`.
  - **Archivio come sorgente img2img**: overlay `button-gen :: sourceOverlay` ("Usa come sorgente") sulle miniature (`/gallery`, dettaglio) →
    `/generations/new?kind=image&source={id}&sourceImage={file}` (`GenerationController#form`: con `kind=image` il modello di default e' il primo IMAGE che
    `takesSourceImage()` e funziona senza sorgente, cioe' ff3/dev-lora); in tutti i form con sorgente il bottone "Scegli dall'archivio" (`fragments/app/archive-picker.html`, dialog) carica
    `GET /gallery/picker?tab=all|imported&kind=...` (`gallery-picker.html`, miniature con link alla stessa URL, il prompt corrente si conserva).
    `reuseConfig` esclude le importate (nessun modello/parametri da riprodurre).

### 3. Storia delle conversazioni

- `/deep-chat` e' multi-conversazione: `ChatConversation` raggruppa i turni (`ChatMessage`; porta `IChatConversations`,
  `ChatConversationService`). Colonna sinistra fissa: bottone "Nuova conversazione" (`POST /deep-chat/new`) + rail NON
  collassabile (`fragments/app/accordion.html :: staticPanels`) con lista conversazioni (`fragments/app/conversation-list.html`,
  piu' recente attiva prima, ricarica cronologia completa, rinomina/cancella inline) e impostazioni di generazione
  (`fragments/app/generation-params.html`).
- Sotto la chat, accordion collassabile (`fragments/app/accordion.html :: panels`, Pines UI) con la galleria "contestuale"
  (`fragments/app/gallery.html :: grid` riusata) con TUTTI i file (immagini e video) di quella conversazione, una card per file
  (`IGenerations#succeededItemsForConversation`, `GalleryItem.allOf`), non solo il primo di ogni generazione. La selezione e'
  PER FILE (`selectionByFile`: checkbox `files` = `<idGenerazione>:<filename>`, `POST /gallery/delete-selected-files`,
  `IGenerations#deleteImages`: una generazione che perde tutti i file e' eliminata a cascata); in `/gallery` resta una card e una
  selezione per generazione. `/gallery` resta indipendente.
- La chat conosce una generazione solo per id (`ChatMessage.generationId`, FK `ON DELETE SET NULL`): per gli allegati della
  cronologia legge `IGenerations#findAllById` in blocco.
- **Cronologia del modello lato SERVER**: il client manda solo l'ultimo messaggio (`requestBodyLimits` `maxMessages: 1` in `deep-chat.html`) e la cronologia che
  alimenta l'LLM la costruisce `ChatHistoryBuilder` (`chat.application`) da `IChatMessageStore#findByConversation`: niente turni d'errore, ultimi
  `app.chat.history-max-turns` (40) turni entro `app.chat.history-max-chars` (60000; l'ultimo c'e' sempre), finestra che parte da un turno utente, turni
  consecutivi dello stesso ruolo fusi (alcuni provider vogliono ruoli alternati). Un turno di esito (quello col `generationId` che scrive
  `ChatGenerationWatcher#persistOutcome`) arriva al modello come nota `ChatTurn` di ruolo `system` ("Generation #12 finished: files a.png." / "failed: ..." /
  "is still in progress"), MAI col testo localizzato mostrato all'utente; `SpringAiAssistant#buildMessages` la mappa su `SystemMessage`. Cosi' il modello vede gli
  esiti, la vista e' la stessa dal vivo e dopo un reload e il contesto non cresce senza limiti. (Un provider che rifiutasse messaggi di sistema a meta'
  conversazione richiederebbe di trasformare la nota in un turno `ai` "[app note] ..."; non verificato con un modello reale.)

### 4. Addestrare LoRA propri (`app.training`)

`/trainings` (voce `Crea ▾ → Addestra un LoRA`, `header.nav.training`, `index.link.training.suffix`), pagina a due schede (`?tab=datasets|history`, 12 per pagina) per addestrare un LoRA con `replicate/fast-flux-trainer`
(schema letto da Replicate: `input_images` zip, `trigger_word`, `lora_type` subject|style, `training_steps`, `seed`, `hf_repo_id`, `hf_token`; `destination` e' un parametro della richiesta, fuori da `input`). NON e' un tool della chat
(resta: la chat avvia solo `generateImage`) e `/trainings` NON sta in `appmap` ne' in `linkAppPaths`: il system prompt e' al tetto di `ChatPromptTest` (10.000 caratteri) e il bot non porta a un'azione a pagamento di questo peso. `LibraryTool#listLoraPresets` vede da solo il preset che nasce da un training.
Feature `training` → `generation` (`ILoraPresets`, `IModelCatalog`, `ApiTokenProvider`, `ReplicateException`) e `prompt` (`IImageCaptioner`); nessuno dipende da `training`.

- **Dataset = bozza persistente lato SERVER** (`TrainingDataset`, `TrainingImage`; salvataggio automatico con htmx `data-busy="off"`, niente localStorage: i `File` non ci stanno). Riprendi, modifica, clona (`ITrainingDatasets#clone`: copia anche i blob con
  `IImageStorageService#copy`, nuovo nome casuale, senza i controlli da upload; `TrainingDatasetService#copyOf` e' condiviso con lo snapshot), cancella. Impostazioni di lancio con la bozza (`LaunchSettings`: nome del modello, passi 1000, seed, `hfPublish` **true**, `hfTokenId`,
  `hfRepoName`, `hfPrivate` true). Tetti: `app.training.max-images` 25 (x 10 MB deve stare in `spring.servlet.multipart.max-request-size`), upload png/jpeg/webp da `IImageStorageService#storeUpload`, esito PER FILE (`UploadReport`). Concorrenza sulla bozza: `DatasetEditor#mutate`
  (un `OptimisticLockingFailureException` e' `training.error.conflict`: "ricarica la pagina e riprova").
- **Ritaglio solo client** (`fragments/app/crop-editor.html`, componente Alpine `cropEditor` sul modello di `mask-editor.html`; nessuna libreria immagini sul server). Parte dall'ORIGINALE, esporta JPEG con lato lungo <= `app.training.max-image-side` (1536) e fa `POST .../images/{id}/crop`; il server
  salva il nuovo blob ed elimina il ritaglio precedente (`TrainingImage#applyCrop`, `clearCrop` = "Ripristina l'originale"); la didascalia NON manuale si rifa' (`requestAutoCaption(false)`), quella a mano resta. Zoom/pan: fuori scope.
- **Didascalie** (`app.prompt`: `IImageCaptioner`, `ImageCaptionService` su `VisionRunner`, guide `trainingCaption.*` in `prompts.properties`, come `imageAnalysis.guide` SENZA `prompts.creative-context`: lo farebbe rifiutare le foto di persone). `CaptionSource` NONE|AUTO|MANUAL e `CaptionStatus`
  PENDING|DONE|FAILED: una modifica a mano e' `MANUAL`, mai sovrascritta se non da "Rigenera" esplicito. Esecuzione in background: `CaptionRequestedEvent` → `CaptionListener` (`@Async("trainingCaptionExecutor")`, 2 thread) → `ICaptionJobs`/`CaptionJobService`, IDEMPOTENTE
  (ricorda le immagini in corso). La card si ricarica da sola ogni 3 s finche' PENDING. Un rifiuto o una risposta illeggibile = `FAILED` senza toast, un guasto = `ISystemEvents#record`. Recupero all'avvio e ogni `app.training.caption-sweep-interval` (5m).
- **Lancio** (`ITrainings#start`, `TrainingService`): A PAGAMENTO, quindi l'ordine delle scritture esterne e' fisso: `trainerVersion` (letta, pinnata) → zip (`IDatasetArchiver`, adapter `out/archive`: `img_NNN.<ext>` + `.txt`) e upload (`POST /files`) → repo HuggingFace → `ensureDestination` (modello privato NUOVO per
  ogni lancio, nome `slug(nome)-yyyyMMdd-HHmmss`, `hardware` = `app.training.destination-hardware`) → `createTraining` con **`RetryPolicy.NONE`**. Prima di spendere, `check` (`LaunchCheck`: blocchi = `min-images` 4, didascalie PENDING o vuote, passi fra `min-steps` e `max-steps`, token HF scelto, esistente e non scaduto (il permesso di
  scrittura lo verifica `start` con `IHuggingFaceRepos#whoami`: un token di sola lettura e' `training.error.hfReadOnly`, prima di spendere); avvisi = sotto `warn-images` 10, trigger word assente da alcune didascalie, repo HF scelto a mano). Nessun doppio lancio: lock a strisce per bozza (`START_LOCKS`) + un training in corso della stessa bozza e' un blocco (`runningTrainingsOf`). Lo **snapshot** (dataset congelato con i file COPIATI, `frozen`)
  vive col training e si elimina con lui; un fallimento qualunque lo ripulisce; se il salvataggio della riga fallisce dopo la creazione remota si annulla la training remota (`cancelQuietly`). Il corpo d'errore di `createTraining` e' redatto e la causa scartata (il token HF puo' stare nell'eco della richiesta).
  Il **token HF in chiaro** esiste SOLO dentro `start` (`IApiTokens#resolve`), parte come `hf_token` (un SEGRETO del trainer: va a Replicate, e il pannello e il manuale lo dicono), mai salvato, loggato, in un evento o nel Model.
- **Avanzamento**: `ITrainings#refresh` con lock a strisce per training (`TrainingLocks`, condiviso con cancel, delete e completamento del risultato: la riga non ha un numero di versione). Un errore di poll TRANSITORIO o `CONFIGURATION` NON fallisce il training (registrato); solo `Kind.PERMANENT` lo fallisce e annulla
  da remoto. `app.training.timeout` (2h) annulla da remoto. `applyTerminal` e' comune a poll e cancel (un cancel che trova il training gia' SUCCEEDED/FAILED lo lascia cosi'). Poller proprio (`TrainingRecoveryService`, adapter in scheduling: ogni `app.training.poll-interval`, 30s, + all'avvio, `@Order(AppStartupOrder.TRAINING_RECOVERY)`,
  dietro `app.recovery.enabled`): lo sweep delle generazioni non basta per un'attesa lunga a tab chiusa. UI: `/trainings/{id}` fa polling htmx ogni 5 s sull'elemento stesso (assente a stato terminale e a risultato completo, o oltre `app.training.result-retry-window`: `polling` lo decide il controller); la scheda Storico
  si rinfresca con l'evento SSE `training-update` (`TrainingPushNotifier`; va in `app.push.client-events` E `reconnect-events`, e in `TemplateRenderingTests.liveEventsBridgeTakesTheAppEventNamesFromConfiguration`). Il form di avvio ha un `hx-request` con timeout di 15 minuti: l'upload dello zip supera i 180 s globali di htmx
  e il training parte comunque (il secondo lancio lo bloccherebbe il server, ma il toast di timeout sarebbe fuorviante).
- **Risultato** (`TrainingCompletedEvent`, pubblicato UNA volta alla transizione a SUCCEEDED da `saveAndNotify`, qualunque strada porti li') → `TrainingResultListener` (`@Async("trainingResultExecutor")`, 1 thread, coda 50 con `DiscardPolicy`: un lavoro scartato lo riprende lo sweep) → `ITrainingResults#complete`
  (`TrainingResultService`): TRE passi indipendenti e idempotenti, ognuno salva appena fatto e un guasto in uno non blocca gli altri ne' fa mai fallire il training (i pesi esistono): (1) **preset** in `/loras` col nome del dataset (suffisso data, poi data e ora, poi `#id` se preso; se un tentativo precedente lo aveva creato lo adotta per
  sorgente), sorgente `owner/nome` del modello Replicate (NON l'URL HF: HF e' la copia); (2) **modello utilizzabile** (`Training.modelStatus` PENDING|REGISTERED|REJECTED): la regola "preset = modello" lo censisce gia' ma un suo rifiuto e' silenzioso, quindi si controlla `IModelCatalog#contains` e si riprova `registerLoraFinetune`;
  un rifiuto entro `app.training.result-grace` (10m: la versione puo' non essere ancora visibile) si ritenta, dopo e' definitivo (`REJECTED`, avviso); (3) **copia HF** (`HfStatus` NONE|PENDING|VERIFIED|NOT_FOUND|UNVERIFIED): `IHuggingFaceRepos#repoFiles` cerca un `.safetensors` (il repo lo crea l'app PRIMA, quindi la sola esistenza non prova nulla);
  il token si risolve di nuovo: se non c'e' piu' = `UNVERIFIED`, senza errore. Sweep (`app.training.result-sweep-interval` 5m + avvio) per i risultati incompleti entro `result-retry-window` (6h); oltre, un risultato ancora PENDING resta cosi' (limite noto: il preset si crea a mano da `/loras`, che censisce il modello).
- **Storico**: ogni lancio ha il PROPRIO snapshot; "Riprendi da questo" = clone dello snapshot in una bozza nuova. Eliminare una bozza non tocca i training; eliminare un training elimina snapshot e file, NON il modello Replicate, il repo HF ne' il preset.
- **Eventi**: `AppEventSource.TRAINING` (guasti non remoti: zip, captioning, risultato) e `HUGGINGFACE` (`HuggingFaceClient`, chiavi `huggingface.error.*`); gli errori del trainer restano `REPLICATE`. Subject `training:<id>` (`AppEventSubjects#ofTraining`), link "apri" in `AppEventLinks` (`/trainings/{id}`).
- **Backup**: `TrainingBlobReferences` (SPI `IBlobReferences`) dichiara `training_image.filename`/`original_filename`.
- **Non verificato con una prediction vera** (forma delle richieste e risposte NON provata contro i servizi reali: nessun training e' mai partito): `POST /files` (limite di dimensione, `.webp`), `POST /models` con `hardware`, `POST /models/{owner}/{name}/versions/{v}/trainings`, `GET /account`, se un destination gia' addestrato accetta un secondo training,
  `siblings[].rfilename` nella scheda di un repo HF (se manca, ogni training riuscito finisce `NOT_FOUND` con un avviso falso), i nomi veri dei file dei pesi, se i log del trainer riportano `hf_token`, l'orientamento EXIF di foto non ritagliate nello zip. La prima prova reale va chiesta all'utente (costo, repo HF privato usa-e-getta).
- **Test**: gateway e store finti (`InMemoryTrainingStore`), `MockRestServiceServer` per Replicate e HuggingFace, `@SpringBootTest` con `@MockitoBean` per `IPredictionGateway`, `ITrainerGateway`, `IHuggingFaceRepos`, `IImageCaptioner`, `ICaptionJobs`, `ITrainingResults` (dove un training puo' arrivare a SUCCEEDED: l'evento creerebbe davvero preset e modelli nel DB condiviso) e `IImageDescriber`:
  mai la rete vera. `TrainingServiceTest`, `TrainingResultServiceTest`, `TrainingLaunchIntegrationTest` (DB vero), `TrainingResultIntegrationTest` (preset e catalogo veri), `TrainingRunControllerTest`, `TrainingControllerTest`.

**Perimetro**: (i crediti nella barra in basso servono a sapere quanto resta da spendere per generare e conversare.) Non aggiungere feature (pagine demo, integrazioni, pattern) che non servano a generare, archiviare o
conversare sulle immagini (l'output puo' essere anche un video). Per dimostrare un pattern htmx/Alpine nuovo, aggiungerlo a
una feature vera. Le pagine demo starter e la chat di rifinitura prompt sono state rimosse; l'icona "AI enhance"
(`IPromptEnhancer`) non ne e' una riedizione: e' un'azione puntuale sulla form reale che riscrive il prompt. La chat resta uno strumento sull'archivio, non solo sulla
generazione: puo' cercare (anche per tag e filtri), etichettare, leggere i crediti, guardare un'immagine, e PROPORRE azioni con un bottone; non avvia mai altro che `generateImage`.

## Filosofia

Hypermedia-first, non SPA: il server e' la fonte di verita' e restituisce HTML, non JSON; il client arricchisce.

- Navigazione → Spring MVC + Thymeleaf. Aggiornamenti parziali → htmx. Micro-interattivita' locale → Alpine.js.
- Componenti davvero complessi → Web Component isolato su un singolo `<div>`, mai un framework SPA.
- Theming → Tailwind (Play CDN) per l'intero sito: nessun `theme.css`, utility inline (vedi "Convenzione: theming").
- Zero build frontend (niente npm/webpack/vite/esbuild): htmx, Alpine, Tailwind da CDN in `fragments/core/layout.html`.

### Cosa NON introdurre senza una ragione concreta

- **Spring WebFlux come modello del server**: restare su `spring-boot-starter-webmvc` (Tomcat), mai
  `spring-boot-starter-webflux` (Boot sceglie UN application-type; mischiare richiederebbe un secondo server o la
  migrazione dell'app). Due eccezioni deliberate, solo **tipi Reactor**:
  1. lo starter Spring AI porta Reactor/WebFlux per il *client* HTTP verso gli LLM;
  2. `EventStreamController` (`GET /events`) ritorna `Flux<ServerSentEvent<?>>` (sorgente `Sinks.Many` in
     `PushService`, esposto dalla porta `IClientPushStream`), supportato nativamente da spring-webmvc (`ReactiveTypeHandler`) sullo
     stesso Tomcat, al posto di un registro di `SseEmitter` a mano. E' l'unica whitelist Reactor delle porte (vedi `ArchitectureTest`).
  Non sono un'apertura generale: un controller reattivo senza un bisogno concreto di streaming e' fuori scope.
- **React/Vue/Angular** come framework applicativo: duplicherebbe routing/stato del server.
- **Un build step Tailwind obbligatorio**: default = Play CDN (`mvn spring-boot:run`/`mvn test` non compilano nulla).
  Unica eccezione opt-in: profilo `tailwind` (`mvn -Ptailwind clean package`), che scarica via `curl` il binario
  *standalone* Tailwind 3.4 (niente Node, cache `target/tailwind/`, solo macOS/Linux) e compila
  `target/classes/static/css/tailwind.css` minificato; `core.web.TailwindAssets` (bean `tailwindAssets`) rileva l'asset e
  `fragments/core/layout.html` serve `<link>` invece del CDN. Config in UN solo file `src/main/tailwind/tailwind.config.js` (CommonJS;
  il CDN lo carica come `/js/tailwind.config.js`, copiato via `<resources>` del pom, con uno shim `module` in `layout.html`). Il blocco
  `@layer base` e' duplicato tra `src/main/tailwind/input.css` e `<style type="text/tailwindcss">` di `layout.html`:
  tenerli allineati. Le classi devono restare stringhe letterali (la CLI scansiona staticamente): niente concatenazione.

## Stack

Spring Boot 4.x + Spring MVC; Thymeleaf + thymeleaf-layout-dialect (`layout:decorate`/`layout:fragment`); htmx e
Alpine.js via CDN; Pines UI (componenti Alpine+Tailwind da copiare, `preflight` attivo, stessa base di stile del sito);
Spring Data JPA + PostgreSQL con l'estensione pgvector (un'istanza sola per dati e vettori; `compose.yaml` per lo sviluppo); Flyway (`spring-boot-starter-flyway`, `ddl-auto: validate`); `RestClient`
(`spring-boot-starter-restclient`) verso Replicate; Spring AI (`spring-ai-starter-model-openai`, `ChatClient`, `base-url`
`https://openrouter.ai/api/v1`, richiede Boot 4.x / Spring AI 2.0.x); embedding locali ONNX (`spring-ai-starter-model-transformers`) e
`spring-ai-vector-store` + `spring-ai-pgvector-store` (`PgVectorStore`) per la ricerca semantica; `commonmark` + `commonmark-ext-gfm-tables` 0.24 (versione esplicita in `pom.xml`: non e' nelle BOM) per il manuale; ArchUnit (`archunit-junit5`, solo scope test) per far
rispettare l'architettura; Maven; Java 21.

## Architettura (core/app, esagoni)

Ogni sottosistema e' un **esagono** (ports & adapters *pragmatico*) e sta o in `org.dual.replicate.core.<sottosistema>` (generico,
riusabile da ogni webapp costruita su questo template) o in `org.dual.replicate.app.<sottosistema>` (specifico di questa app).
Dipendenza solo `app → core`, mai il contrario. `Application` (root, nessuna configurazione: `@EnableAsync`/`@EnableScheduling` stanno in `core.kernel.ExecutionConfig`) e `support` (solo test: `PostgresTestContainerInitializer`) sono
le uniche classi fuori da `core`/`app`.

```
<core|app>/<sottosistema>/
  domain/            entity JPA, value object, enum, eccezioni, eventi di dominio, regole pure (es. ReplicatePricing)
  application/       use case: implementano le porte in (<Capability>Service), @Transactional; usano SOLO port.out e altre port.in
  port/in/           interfacce I<Capability>: l'API del sottosistema, per gli adapter in e per gli ALTRI sottosistemi
  port/out/          interfacce I<Thing>Store | Gateway | Backend | Notifier implementate dagli adapter out
  adapter/in/<tech>/ web (controller), scheduling, async, ai... (chi pilota il sottosistema)
  adapter/out/<tech>/ persistence (Spring Data + Jpa<..>Store), replicate/searxng/http/webdav/push/search... (cio' che il sottosistema usa)
```

- **Naming**: porta in `IGenerations` → impl `GenerationService`; porta out `IGenerationStore` → impl `JpaGenerationStore`. I repository
  Spring Data sono **package-private** nell'adapter `persistence` (un controller o un use case non vede mai un repository).
  Le interfacce iniziano SEMPRE con `I`, le implementazioni no (vedi anche "Convenzione: interfacce").
- **Cosa e' ammesso dove** (le regole sono nei test, vedi sotto): `domain` e' JDK + `jakarta.persistence` + `org.hibernate.annotations/type`
  (per `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`) + altro `domain` + kernel; le entity JPA e le annotazioni Spring (`@Service`,
  `@Transactional`, `@ConditionalOnProperty`...) sono ammesse in `domain`/`application`. `application` NON usa `adapter..`, ne'
  `org.springframework.web|http|ai|data`, `jakarta.servlet`, `java.net.http`. `port..` dipende solo da `domain`, altre `port` e
  kernel (unica whitelist: Reactor in `IClientPushStream`). `adapter.in` non dipende da `adapter.out` e viceversa. L'adapter
  `adapter.ai` (ne' `in` ne' `out`: ChatClient + tool, bidirezionale) e' l'unico nome che sfugge a quest'ultima regola.
- **Fra sottosistemi** (anche core↔app e feature↔feature) si dipende SOLO da `port.in` e `domain` dell'altro, mai da `application` o
  `adapter`. Eccezioni: il **kernel** (`core.kernel`: `RemoteServiceException`/`RemoteCaller`/`RetryPolicy`, `Messages`, `EventSource`,
  `Paged`, `ToastMessage`, `ChunkedAesGcmCipher`) e il **kit UI** (`core.web`: `HtmxEvents`, `PaginationSupport`, `TailwindAssets`, `BuildInfo`) sono
  condivisi, non esagoni. Una classe nel package radice `app` (`OpenRouterCalls`, `AppStartupOrder`) non appartiene a nessuna slice
  ed e' condivisa fra feature. Il kernel non dipende da nessun sottosistema. Nessun ciclo fra sottosistemi.
- **Punti di estensione**: un'implementazione dell'app puo' implementare SOLO queste `port.out` del core (elenco chiuso, `ArchitectureTest.CORE_EXTENSION_POINTS`): `IEventLinkResolver` (`AppEventLinks`),
  `ITokenProviderCatalog` (`AppTokenProviders`); ogni altra `port.out` del core (store, `IBlobBackend`...) per l'app non esiste; `EventSource` (kernel) e' implementata da `CoreEventSource` e `AppEventSource`.
- **Grafo delle feature dell'app**: `prompt` e `search` sono foglie; `generation` → `prompt`, `search`; `chat` → `generation`, `search`, `credits`, `prompt`; `training` → `generation`, `prompt` (nessuno dipende da `training`); `credits` → `generation`
  (solo `chat` dipende da `credits`: `CreditsTool`; `prompt`: `VisionTool` via `IImageDescriber`);
  `generation` NON conosce `chat` (solo `Generation.conversationId`, un `Long`). `search` NON conosce `generation` ne' `chat`: legge i
  loro dati tramite la SPI `ISearchableSource` (in `search.port.in`), implementata da `GenerationSearchSource` (generation,
  `adapter.out.search`: ascolta anche `GenerationCompletedEvent` e chiama `IArchiveIndex#reindexAsync`) e da `ChatSearchSource` (chat).
  `app.shared` (`AppEventSource`, `AppEventSubjects`, `OpenRouterException`, `FormFields`, `HomeController`, `AppEventLinks`) e' il dominio comune dell'app
  (il "kernel" specifico dell'app: puro JDK, ci si dipende da `shared.domain` come da ogni `domain`).
- **`ArchitectureTest`** (`src/test/.../architecture`, ArchUnit, `DoNotIncludeTests`, 14 regole `@ArchTest`): `domainStaysPure`,
  `applicationDoesNotTouchInfrastructure`, `portsDependOnlyOnDomain`, `drivingAdaptersDoNotUseDrivenAdapters`, `drivingAdaptersDoNotUsePortsOut`,
  `drivenAdaptersDoNotUseDrivingAdapters`, `coreDoesNotKnowApp`, `coreDoesNotUseLegacyLayerPackages`, `kernelDependsOnNoSubsystem`,
  `subsystemsOnlyUseEachOthersPortsIn`, `coreSubsystemsHaveNoCycles`, `appFeaturesHaveNoCycles` e la regola di **chiusura**
  `nothingOutsideCoreAndApp` (ATTIVA: nessuna classe fuori da `core..`, `app..`, `support..` e `Application`: niente package per layer
  `controller/service/repository...`). Le regole ammettono package vuoti (`allowEmptyShould`). Una violazione si corregge nel codice,
  non allentando la regola. `applicationDoesNotTouchInfrastructure` vieta anche `org.springframework.jdbc` e `java.sql`; `applicationDoesNotDoFileOrImageIo` vieta
  `Files`/`Paths`/`File*Stream`, `javax.imageio` e `java.awt` (il file e l'immagine stanno dietro una porta).
  **`SourceImportsTest`** (stessa cartella) applica le stesse regole di strato al SORGENTE: ArchUnit lavora sul bytecode e non vede gli import usati solo in
  Javadoc (javac li scarta), che pero' restano una dipendenza dichiarata. Vale per import E nomi qualificati nei commenti: per citare una classe di un altro
  strato o sottosistema in un commento usare `{@code Nome}` senza import, mai `{@link}` (e nemmeno il nome qualificato).

### Dove sta cosa

| Sottosistema | Responsabilita' | Porte principali |
|---|---|---|
| `core.kernel` (+ `.remote`, `.i18n`, `.crypto`) | errori/retry remoti, `Messages`, cifratura a chunk, `Paged`, `EventSource` | (shared kernel, niente porte) |
| `core.web` | kit UI: `HtmxEvents` (header `HX-Trigger`/toast), `PaginationSupport`, `TailwindAssets`, `BuildInfo` (ora+commit del badge di build) | (shared, niente porte) |
| `core.events` | registro eventi di sistema (errori/avvisi), campanella, pagina `/system/events`, toast, `UnhandledExceptionResolver` | in `ISystemEvents`; out `ISystemEventStore`, `IToastNotifier`, `IEventLinkResolver` (app) |
| `core.push` | SSE verso le tab (`GET /events`), `PushService` (`Sinks`) | in `IClientPush`, `IClientPushStream` |
| `core.secrets` | cifratura dei segreti a riposo | in `ISecretCipher` |
| `core.tokens` | CRUD token API cifrati, scadenze, `/tokens` | in `IApiTokens`; out `IApiTokenStore`, `ITokenProviderCatalog` (app) |
| `core.storage` | binari (immagini/mp4/upload): nome, validazione, local/WebDAV, `/images/{file}`, migrazione | in `IImageStorageService`, `IBlobMigration`; out `IBlobBackend`, `IBlobImportSource`, `IBlobImportTarget`, `IRemoteFileFetcher` |
| `core.backup` | `export`/`import` del jar: backup completo (DB + binari) in un archivio cifrato e ripristino; profilo `backup`, vedi "Backup e restore" | in `IBackupExport`, `IBackupImport`, `IBlobReferences` (SPI: la implementa l'app); out `IDatabaseDump`, `IDatabaseRestore`, `IBackupArchive` |
| `core.manual` | manuale online: pagine Markdown (`manual/<lingua>/<NN-gruppo>/<NN-pagina>.md`) convertite in HTML al volo, `/manual`, ricerca per sezioni; vedi "Manuale online" | in `IManual`; out `IManualSource`, `IMarkdownRenderer` |
| `app.generation` | generazioni (immagini/video/edit), immagini importate, catalogo modelli, form-type, LoRA, galleria, costo, Replicate, recupero | in `IGenerations`, `IImportedImages`, `IModelCatalog`, `IGenerationForms`, `ILoraPresets`; out `IGenerationStore`, `IModelStore`, `ILoraPresetStore`, `IPredictionGateway` |
| `app.chat` | `/deep-chat`: conversazioni, turni, assistente (LLM + tool), watcher delle generazioni, recupero | in `IChat`, `IChatConversations`, `IChatRecovery`; out `IAssistant`, `IChatConversationStore`, `IChatMessageStore`, `IChatNotifier`, `IWebSearchGateway` |
| `app.search` | ricerca semantica, indice (riconciliazione), note, `/search` | in `IArchiveSearch`, `IArchiveNotes`, `IArchiveIndex`, `ISearchableSource` (SPI); out `IVectorIndex` |
| `app.prompt` | "AI enhance" del prompt (one-shot) e descrizione/tag di un'immagine con il modello di visione | in `IPromptEnhancer`, `IImageDescriber`; out `IPromptModel` |
| `app.training` | dataset di addestramento (ritaglio, didascalie), training LoRA su Replicate, storico, risultato (preset + modello + copia HuggingFace), `/trainings` | in `ITrainingDatasets`, `ITrainings`, `ITrainingCaptions`, `ICaptionJobs`, `ITrainingResults`; out `ITrainingDatasetStore`, `ITrainingStore`, `ITrainerGateway`, `IHuggingFaceRepos`, `IDatasetArchiver` |
| `app.credits` | credito residuo Replicate (stima) e OpenRouter per la barra in basso | in `ICredits`; out `IOpenRouterCreditGateway`, `IReplicateBalanceStore` |
| `app.shared` | `AppEventSource`, `AppEventSubjects`, `OpenRouterException`, `Tags` (normalizzazione dei tag utente), `HomeController`, `AppEventLinks` | (dominio comune dell'app) |

### Checklist: aggiungere un sottosistema o una feature

1. Core o app? Generico (riusabile da un'altra webapp) → `core.<nome>`; altrimenti `app.<nome>`. Il core non puo' dipendere dall'app.
2. Creare solo i package che servono (`domain`, `application`, `port.in`, `port.out`, `adapter.in.<tech>`, `adapter.out.<tech>`); la porta `I<Capability>`
   e' l'unica cosa che gli altri sottosistemi vedono. Disegnare PRIMA le porte (senza tipi web/HTTP/Spring Data/Spring AI: usare tipi di dominio o
   `core.kernel.Paged`), poi gli adapter.
3. Persistenza: entity in `domain`, `Spring Data` package-private + `Jpa<..>Store` in `adapter.out.persistence`, migrazione Flyway nella location giusta
   (vedi "Convenzione: migrazioni"); nessuna FK dal core all'app.
4. Dipendere da un altro sottosistema solo via la sua `port.in`/`domain`. Se serve un dato di un altro sottosistema senza che questo ti conosca,
   definire una SPI nella tua `port.in` (come `ISearchableSource`) che lui implementa in un `adapter.out.<tua-feature>`.
5. Un tipo di evento/servizio remoto nuovo: vedi "Checklist nuovo servizio remoto". Bundle i18n: chiavi nel bundle giusto (vedi "Convenzione: i18n").
6. Verificare con `mvn test`: `ArchitectureTest` deve restare verde; aggiungere i test accanto al package (stessa struttura sotto `src/test`).

## Struttura del progetto

Ricavabile dal repo (`git ls-files`); qui solo cio' che non e' ovvio. Sotto `core/<s>` e `app/<s>`: vedi "Dove sta cosa".

- `core.events`: `SystemEventService` (impl di `ISystemEvents`), `JpaSystemEventStore`, `SystemEventController` (web, `/system/events`, campanella),
  `UnhandledExceptionResolver` (rete per gli errori non gestiti), `AsyncErrorConfig` (eccezioni dei `@Async void`), `PushToastNotifier` (toast
  via `IClientPush`), domain `SystemEvent`/`SystemEventSeverity`/`CoreEventSource`/`EventLink`/`EventPage`.
- `core.push`: `PushService`, `EventStreamController` (`GET /events`, unico push), `PushModelAdvice` (espone a `live-events.html` i nomi
  `app.push.client-events`/`reconnect-events`). `core.secrets`: `SecretCipher`. `core.tokens`: `ApiTokenService`, `TokenController`,
  `TokenExpiryScheduler`, `JpaApiTokenStore`, `TokenException`.
- `core.storage`: `ImageStorageService` (impl di `IImageStorageService`), `StorageNames` (`newFilename`, `shardPath`), backend `LocalFsBlobBackend`
  e `WebDavBlobBackend` (+ `EncryptedBlobCache`), `HttpFileFetcher`, `ImageController` (`GET /images/{file}`, unico punto da cui escono i binari,
  Range per il seek dei video ed ETag), `LocalToWebDavMigrator`.
- `core.backup`: comandi `export`/`import` del jar (vedi "Backup e restore"). `BackupRunner` (adapter in `cli`, `ApplicationRunner`, profilo `backup`), `BackupExportService`/`BackupImportService`,
  `JdbcDatabaseDump`/`JdbcDatabaseRestore` (`COPY` via `CopyManager` + Flyway da codice), `ZipBackupArchive` (zip, cifrato per intero con `ChunkedAesGcmCipher#encryptingStream`), domain `BackupManifest`/`BackupSummary`/`TableOrder`.
  Tutti i bean tranne la SPI `IBlobReferences` sono `@Profile("backup")`: il server non li carica.
- `core.manual`: `ManualService` (impl di `IManual`: indice per lingua costruito una volta, pagina in HTML a ogni richiesta, sezioni, ricerca), `ManualController` (web, `/manual` e `/manual/{slug}`),
  `ClasspathManualSource` (adapter out, `classpath*:manual/<lingua>/**/*.md`, ricade su `it`), `CommonmarkRenderer` (adapter out, l'UNICO a vedere i tipi di commonmark-java), domain `ManualEntry`/`ManualPage`/
  `ManualSection`/`ManualHeading`/`HeadingSlugs`/`ManualLinks`. Vedi "Manuale online".
- `app.generation`: `GenerationService` (crea prediction, avanza stato, download; pubblica `GenerationCompletedEvent` a ogni transizione
  terminale; `GenerationsDeletedEvent`/`GenerationImageDeletedEvent` per le cancellazioni), `GenerationController` (crea, polling/dettaglio,
  listato, cancellazioni, "AI enhance" `POST /generations/enhance-prompt`), `GalleryController` (solo SUCCEEDED), `LoraController`,
  `application.form` (la conversione campi di form → parametri e' un use case dell'esagono, usato SIA dalla form diretta SIA dalla chat, che la vede da `IGenerationForms`):
  `GenerationFormService` (impl di `IGenerationForms`: `parameters`, `defaultFields`, `extraFormOptions`, `formModel`) + un `IGenerationParameterHandler` per form-type (`FluxLoraFinetuneParameterHandler`,
  `Flux2Klein9bParameterHandler`, `FluxKreaDevParameterHandler`, `PVideoParameterHandler`, `FluxKontextDevParameterHandler`,
  `FluxDevLoraParameterHandler`; il parsing tollerante dei campi (`asInteger`, `asOneOf`, `isChecked`...) sta nel kernel dell'app `app.shared.domain.FormFields`, riusabile da ogni feature; il fragment Thymeleaf del form-type lo sceglie solo l'adapter web, `GenerationFormFragments` per convenzione di nome; `image` di p-video e `input_image` di kontext le aggiunge `GenerationService`). L'esagono riceve la
  conversione gia' fatta: `IGenerations#create(CreateCommand)` prende una `Map<String,Object>` tipizzata (vocabolario Replicate), mai JSON o campi di form;
  `num_outputs` e' limitato a `IGenerationForms.MAX_NUM_OUTPUTS` (4, limite di Replicate, GLOBALE per ogni form-type con piu' immagini:
  fine-tune LoRA, krea, dev-lora) sia dal `max` dei fragment sia da `asNumOutputs` (clamp 1..4 lato server: il pannello della chat non passa da validazione HTML);
  kind, chiave della sorgente e `aspect_ratio` dei video con sorgente li decide `GenerationService` dal form-type,
  `ModelCatalogService` (impl di `IModelCatalog`, catalogo censito in `replicate_model`), `TokenInputResolver`, adapter `replicate`
  (`ReplicateClient`, `ReplicatePredictionGateway`, `PredictionResponse`), `GalleryPushNotifier`, `AppTokenProviders`,
  `GenerationRecoveryService` (adapter in scheduling). Domain: `Generation`, `GenerationKind`/`Status`/`FormType`, `ReplicateModel`,
  `LoraPreset`, `ReplicatePricing`, `ReplicateException`, `TooManyPredictionsException` (troppe prediction in corso PER LO STESSO MODELLO, vedi `GenerationService#create`),
  `ApiTokenProvider` (CIVITAI, HUGGINGFACE).
- `app.chat`: `ChatService` (un turno: persiste l'ultimo messaggio utente, costruisce la cronologia del modello con `ChatHistoryBuilder` (vedi "Cronologia del modello lato SERVER"), chiede la risposta a `IAssistant`, avvia i watcher), `ChatConversationService`,
  `ChatGenerationWatcher` (`watch` `@Async`, `persistOutcome` idempotente; il legame generazione↔conversazione lo scrive `ChatService` con `IGenerations#attachToConversation`, sincrono, prima che la risposta del turno raggiunga il client), `ChatRecoveryService` (+
  `ChatRecoveryScheduler`), `DeepChatController` (route HTML `/deep-chat/*`), `DeepChatApiController` (JSON per `<deep-chat>`),
  `adapter.ai`: `SpringAiAssistant` (`ChatClient`) riceve la `List<ChatToolkit>` dei tool PRESENTI: ogni classe tool implementa `ChatToolkit#promptSection` (chiave `deep-chat.section.*`, o null)
  e ha un `@Order`; nessun elenco a mano. Il system prompt e' a SEZIONI in `prompts.properties`: `core|guidance|appmap` sempre, poi la sezione di ogni toolkit presente nell'ordine
  `web|library|manual|archive|curation|actions|notes|credits|vision|generation`, poi `prompts.creative-context` e la guida immagini (`deep-chat.image-prompting-guide`, senza modelli nominati: il modello lo sceglie la UI).
  Un toolkit condizionale (`archive`, `notes`: `app.search.enabled`) senza il suo bean non porta la sezione. `ChatPromptTest` carica i testi veri, fissa un tetto (10.000 caratteri; oggi ~8.700), che ogni sezione
  appartenga a un toolkit o sia sempre presente, che i testi sempre presenti non nominino tool condizionali e che ogni nome di tool citato esista. La sezione `guidance` rende il bot moderatamente PROATIVO nel far
  scoprire cio' che sa fare (cenno iniziale, al massimo UN suggerimento pertinente a fine richiesta, solo su capacita' reali: e' sempre presente, quindi cita solo capacita' sempre disponibili; `appmap` dice dove
  vivono form, galleria, import, ricerca, LoRA, token, eventi e manuale e cosa il bot NON puo' fare da solo). I RITORNI dei tool sono testo per il modello e sono in INGLESE (non passano da `Messages`); i messaggi d'errore dicono al modello
  di non riprovare da soli e di avvisare l'utente. I tool: `WebSearchTool` (risultati dichiarati non fidati: mai istruzioni), `ImageGenerationTool` (vedi Scopo: id restituito, tetto per turno, prompt vuoto rifiutato, modello SEMPRE da `ToolContext`),
  `ManualTool` (`searchManual(query?)`: query vuota = indice delle pagine, altrimenti le 3 sezioni migliori col link citabile `/manual/<slug>#<ancora>`; `readManualPage(page, section?)`; SOLA LETTURA e gratuito, solo il gruppo `uso`
  del manuale (l'architettura si legge dal web), tetto di 6.000 caratteri per risposta, `@Order(25)`, sempre presente: il manuale NON e' nel system prompt, che e' al limite di lunghezza; un guasto e' registrato e il testo dice al modello di avvisare l'utente),
  `ArchiveSearchTool` (`searchArchive(query, type?, tag?, favouritesOnly?, media?, since?, until?)`, tipi `generation|imported|note|chat|conversation`; con query vuota e almeno un filtro elenca i piu' recenti),
  `LibraryTool` (SOLA LETTURA sull'app via `port.in`: `listModels` (anche i modelli che richiedono una sorgente, non generabili da qui), `listLoraPresets`, `listTags`, `getGeneration` (tag di generazione e file, sorgente, esito dell'analisi delle importate, impostazioni a WHITELIST `VISIBLE_PARAMETERS`: mai LoRA, token o `parametersJson` grezzo), `conversationGallery`
  (anche le generazioni IN CORSO, con id e stato) e `recentEvents`; niente sorgenti dei LoRA o segreti nell'output; `getGeneration` elenca i NOMI dei file con star, tag e seed riproducibile di ciascuno, perche' `setFavourite`/`setTag`/`propose*` li vogliono esatti; un id nullo dal modello e' un messaggio di ritorno, non un errore registrato; `IAssistant#respond` riceve la `conversationId`, che arriva ai tool via `ToolContext`),
  `CurationTool` (ex `FavouriteTool`; mutazioni leggere e reversibili, solo su richiesta esplicita, tutte IDEMPOTENTI: ricevono lo stato voluto, mai un toggle): `setFavourite`, `setTag(generationId, filename?, tag, present)`, `setConversationTag` e `renameConversation` solo sulla conversazione CORRENTE (`ToolContext`: il modello non sceglie altre conversazioni),
  e `NoteTool` (`saveNote`, solo con `app.search.enabled`); `ActionProposalTool` (`proposeCancel|Delete|DeleteFile|RegenerateWithSeed|Animate|UseAsSource`: NON eseguono, leggono e depositano una `ChatAction` in `ActionProposalHolder`; arriva in `ChatReply#actions` e in `Reply.actions`
  e il client la rende come bottone `button-gen :: chatAction` (classe `chat-action`, handler in `htmlClassUtilities` di `deep-chat.html`, un
  `<template>` per tipo): annulla/cancella = POST a `/generations/{id}/cancel|delete` dopo `window.confirm`, elimina un file = POST `files=<id>:<file>` a `/gallery/delete-selected-files` dopo conferma (l'ultimo file elimina la generazione), rigenera = apre
  `/generations/new?config=<id>&file=<file>` ("Usa configurazione", vedi "Convenzione: stato delle form": il server ricostruisce modello, LoRA, parametri, prompt e seed), anima / usa come sorgente = apre `/generations/new?source=<id>&sourceImage=<file>` (`kind=image` per la seconda; solo un'immagine RIUSCITA, `IGenerations#findAnimatableSource`): in ogni caso la prediction parte solo col "Genera" li'. `ChatAction` porta solo id, file e (rigenera) modello. Le azioni NON sono persistite: al reload il bot le ripropone),
  `CreditsTool` (`getCredits`, SOLA LETTURA: righe di `ICredits#lines` e costo stimato della conversazione corrente; mai `setReplicateBalance`, nessuna stima prima di generare), `VisionTool` (`describeImage(generationId, filename)`: un'importata con analisi `DONE` risponde dall'analisi salvata, SENZA chiamate; per le altre il file va a `IImageDescriber` (modello di visione,
  costa token OpenRouter), tetto per turno `app.chat.max-vision-calls-per-turn` (2) con `VisionCallCounter` nel `ToolContext`; i video si rifiutano, un rifiuto del modello e' un esito atteso e non si registra; il risultato NON si salva),
  `GenerationResultHolder` (canale tool→assistente via `ToolContext`: gli id delle generazioni avviate nel turno); `adapter.out.searxng`: `SearxngClient` (Basic Auth,
  impl di `IWebSearchGateway`), `ChatPushNotifier`, `ChatSearchSource`, store JPA. Domain: `ChatConversation` (con `generationSettingsJson`, vedi "Form per conversazione"), `ChatMessage`, `FileRef`,
  `ChatTurn` (ruoli `user|ai|system`), `ChatReply`, `ChatAction`, `DeepChatFailedException`, `AssistantException`. Config `app.chat.*` in `application.yml`.
- `app.prompt`: `PromptEnhancementService` (one-shot; la riduzione delle immagini grandi per il modello di visione sta dietro `ISourceImageScaler`, adapter
  `AwtSourceImageScaler` con `ImageIO`: un png/jpeg illeggibile e' un errore `ImageScalingException`, non si invia l'originale; webp passa invariato; senza tool ne' cronologia, `ChatClient` dedicato in `ChatClientPromptModel` senza
  `defaultTools`; `enhanceVideo`/`enhanceEdit`/`enhanceInpaint`/`enhanceImg2Img` guardano l'immagine sorgente con un modello di visione OpenRouter non moderato
  `enhancer.vision-model`/`vision-fallback-model`, guide in `prompts.properties`; un rifiuto del modello e' intercettato e non
  sovrascrive la textarea, anche sul percorso solo-testo). Tono/contesto creativo: UNA clausola condivisa `prompts.creative-context` in
  `prompts.properties`, inclusa (`${...}`) in tutte le guide dell'enhancer e nel system prompt della chat (`SpringAiAssistant`): enhancer e chat non
  devono divergere in permissivita'; i limiti (persone reali identificabili, minori) stanno in quella clausola (`PromptGuidesTest`).
  `ImageDescriptionService` (impl di `IImageDescriber`, usata da `app.generation` per le immagini importate) condivide col resto il collaboratore package-private
  `VisionRunner` (modello di visione + fallback + rilevamento rifiuti): un rifiuto e' `ImageAnalysisException` (REJECTED). I test che importano immagini
  in un `@SpringBootTest` mockano `IImageDescriber` (`@MockitoBean`): mai chiamate vere a OpenRouter (ne' a Replicate: `IPredictionGateway`). Con uno stub gia' lancianti
  si ri-stubba con `doReturn(...).when(mock)`, non con `when(mock.metodo())` (che eseguirebbe lo stub vecchio).
- `app.search`: vedi "Ricerca semantica". `app.shared`: vedi "Dove sta cosa". Root `app`: `OpenRouterCalls` (`RemoteCaller` condiviso per OpenRouter),
  `AppStartupOrder` (ordine dei listener di `ApplicationReadyEvent`: prima il recupero delle generazioni, poi quello della chat, poi quello dell'addestramento: didascalie, training in corso, risultati).
- Risorse: `application.yml` (SOLO config dell'app: Spring AI, `replicate.*`, `searxng.*`, `enhancer.*`, `app.recovery.*`, `app.search.*`,
  `app.push.*`, Flyway `locations`) che importa `core.yml` (config del core: web/MVC, i18n, DB, eventi, secrets/token, storage) e
  `prompts.properties`; le chiavi dei due file sono disgiunte (un file importato ha la precedenza su quello che lo importa). Bundle
  `messages-core(.en).properties` + `messages(.en).properties` (app). `db/migration/core` + `db/migration/app`.
- `templates/`: `fragments/core/` (`layout`, `header`, `button`, `alert`, `select`, `toast`, `notification-bell`, `live-events`, `pagination`,
  `status-bar`, `breadcrumbs`, `description-list`, `system-events`, `tokens`, `manual`), `fragments/app/` (tutto il resto, incl. `nav.html`, `button-gen.html`, un
  `generation-params-<form-type>.html` per form-type, `generation-params-source-upload.html`), pagine `templates/core/` (`system-events`, `tokens`, `manual`) e
  `templates/app/` (`index`, `generate`, `generation-status`, `generations-list`, `gallery`, `deep-chat`, `loras`, `search`).
  `header.html` e' sticky; sotto `md` link e theme switch stanno in uno slideover Pines (stato Alpine `navOpen`, `button :: navToggle`);
  le voci di navigazione le mette l'app in `fragments/app/nav.html :: links(inline)` (punto di estensione). `layout.html` legge brand/titolo dalle
  chiavi `app.brand|title` del bundle dell'app.
  **Menu** (`nav.html`): azioni frequenti come link diretti (Deep Chat, Galleria, Ricerca), poi `Crea ▾` (immagine/video/importa/addestra un LoRA) e `Gestione ▾`
  (Generazioni, LoRA, Eventi, Token), infine il link diretto `Manuale` (`header.nav.manual`, bundle dell'app); niente piu' "Archivio" ne' tema (sta nella barra in basso). `header.menu.manage` sta in `messages-core` perche' le pagine core
  (Token, Eventi) lo usano nelle breadcrumbs; `header.menu.create` e' dell'app.
  **Breadcrumbs** (`fragments/core/breadcrumbs.html :: trail(group, parentPath, parentText, current)`): su OGNI pagina tranne la Home, nello slot
  `layout:fragment="breadcrumbs"` di `<main>`: Home › gruppo di menu (solo testo) › un livello intermedio linkato › pagina corrente (`aria-current`).
  Parametri nominati e tutti passati (inutilizzati a `null`); `parentPath` e' un path grezzo (il fragment applica `@{...}` una volta sola, come `navLink`).
  Il dettaglio generazione (`generation-status.html`) ricava il livello intermedio da dove si arriva (`conversationId` → conversazione, `generationsPage` →
  listato, altrimenti `/gallery`) e sostituisce i vecchi link "Torna a…". Un test (`everyPageButHomeShowsBreadcrumbs`) copre le pagine esistenti. `generate-form.html`: `promptField` e' il blocco textarea+"AI enhance"+"Svuota il prompt" (`button-gen :: clearPrompt`, visibile solo con del testo: placeholder `" "` + `peer-placeholder-shown:hidden`, dispatcha `input` per il salvataggio dello stato),
  risostituito in outerHTML da `enhance-prompt`; `generation-params.html` e' il guscio condiviso da form e chat (select modello + campi del form-type).
  `live-events.html`: SSE `GET /events` ri-dispatchata come CustomEvent su `document.body`.

**Barra di stato in basso** (`fragments/core/status-bar.html`, inclusa dal layout; il `<main>` riserva `pb-16` e i toast stanno sopra, `bottom-12`):
a SINISTRA il badge di build (bean `BuildInfo`: ora e commit, sempre visibili, per sapere quale build sta girando), a DESTRA lo slot dell'app
`fragments/app/status-extras.html :: container` (punto di estensione, come `nav.html`: il core non conosce i crediti) e il selettore del tema
(`header :: themeSwitch`, non piu' nel menu Gestione). Da classi sciolte (IntelliJ, `spring-boot:run`) l'ora e' la modifica piu' recente in `target/classes` e il commit viene da `git describe`
(`-dirty` se ci sono modifiche): `build-info.properties` NON si usa li', perche' IntelliJ non esegue i plugin Maven e ne resterebbe una copia vecchia.
Da jar li porta `BuildProperties` (goal `build-info`); il commit solo con `mvn package -Dbuild.commit=<hash>` (il plugin `git-commit-id` non si
risolve dal mirror aziendale, quindi non e' usato).
**Crediti** (`app.credits`, `ICredits`/`CreditsService`, `CreditsController`): il render della pagina NON fa chiamate remote, `status-extras :: container` carica
`GET /credits/bar` via htmx (al load, ogni 5 min, a `gallery-update`; lo stato Alpine `balanceOpen` e il popover del saldo stanno FUORI dalla zona sostituita).
**OpenRouter**: `GET /api/v1/credits` (`OpenRouterCreditsClient`) vuole una MANAGEMENT key, `OPENROUTER_MANAGEMENT_KEY` (`openrouter.management-key`, diversa dal
token della chat: con quella risponde 403); senza, il chip non compare; cache 5 min (1 min dopo un errore, registrato in `system_event`). **Replicate** NON espone il saldo via API:
il credito e' una STIMA (`~$`) = saldo inserito a mano nel popover del chip (tabella a riga unica `replicate_balance_anchor`, `ReplicateBalanceAnchor`) meno `IGenerations#totalCostSince(asOf)`
(somma di `generation.cost_usd` delle generazioni create dopo l'inserimento, mai sotto zero); va riallineato ogni tanto. Sotto `CreditLine.LOW_THRESHOLD_USD` il chip e' `warning`.

Le immagini generate vivono in `./data/images` e il DB di sviluppo (container Postgres) in `./data/postgres`, entrambi fuori da git. Nessun CSS in `static/`: `static/css/tailwind.css` esiste
solo se generato dal profilo `tailwind` (in `target/`, mai committato).

## Pattern Thymeleaf: layout manager

Ogni pagina si decora con `layout:decorate="~{fragments/core/layout}"` sul proprio `<html>` e mette il contenuto in
`<div layout:fragment="content">`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{fragments/core/layout}">
<head>
    <title>Titolo pagina</title>
</head>
<body>
<div layout:fragment="content">
    <!-- contenuto -->
</div>
</body>
</html>
```

- Niente `lang` letterale sul proprio `<html>`: lo risolve il decoratore; ripeterlo lo vince nel merge e disattiva il
  meccanismo (vedi i18n).
- `<title>` della pagina sostituisce quello del layout automaticamente; il resto di `<head>` viene *fuso*.
- Un fragment della pagina sostituisce l'elemento del decoratore, non solo il contenuto: `content` e' un `<div>`, NON un
  `<main>` e NON porta le classi del contenitore. L'unico `<main>` (con `content` e `breadcrumbs` opzionale) vive in
  `layout.html`; ripeterlo annida `<main>` e raddoppia il padding.
- Le pagine dell'app stanno in `templates/app/`, quelle del core in `templates/core/`; i nomi di vista Java sono `app/<pagina>` e `core/<pagina>`.

## Pattern controller: fragment vs pagina intera

**Stessa URL, due risposte**, distinte dall'header `HX-Request`:

```java
@GetMapping("/{id}")
public String status(@PathVariable Long id,
                      @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                      Model model) {
    // ... popolare il model ...
    boolean isHtmxRequest = "true".equalsIgnoreCase(hxRequest);
    return isHtmxRequest ? "fragments/app/generation :: status" : "app/generation-status";
}
```

Un fragment con parametri restituito come vista di risposta diretta richiede parametri **nominati**
(`frag(nome=${valore})`); la forma posizionale funziona solo in un `th:replace` dentro un altro template, altrimenti 500
(`Parameters in a view specification must be named`). Esempi: `GenerationController` (fragment senza parametri),
`GalleryController` (paginazione, `hx-target="#gallery-content"` + `hx-swap="innerHTML"`, parametri nominati).

Un controller (adapter `in.web`) parla solo con le porte `in` (`IGenerations`, `IChat`...) mai con repository, classi `application` o `port.out` (regola
`drivingAdaptersDoNotUsePortsOut`): i punti di estensione dell'app li inietta il SERVIZIO (`Optional<IEventLinkResolver>` in `SystemEventService`, `Optional<ITokenProviderCatalog>`
in `ApiTokenService`) e il controller li legge dalla porta `in` (`ISystemEvents#linksFor`, `IApiTokens#providers`); senza app il fallback (nessun link, nessun provider) e' li'.

## Convenzione: attributi che portano un URL dell'app

Ogni attributo con un URL dell'app (`href`, `src`, `action`, `hx-get/post/put/delete`, URL passati a Web Component come
`connect` di `<deep-chat>`) passa **sempre** da `@{...}`, anche se il path e' letterale: l'app puo' stare dietro un
reverse proxy su subpath (`server.forward-headers-strategy`) e solo `@{...}` applica `X-Forwarded-Prefix`. Bug reale gia'
capitato (vedi `fragments/app/generate-form.html`, `templates/app/deep-chat.html`).

- Per `hx-*` basta il prefisso `th:` con `@{...}` dentro:
  ```html
  <form th:hx-post="@{/generations}" hx-target="#generation-panel" hx-swap="innerHTML"
        th:action="@{/generations}" method="post">
  <a th:hx-get="@{/gallery(page=${p})}" hx-target="#gallery-content" hx-swap="innerHTML">...</a>
  ```
- Fuori da htmx (es. `connect`, letto via JS): `th:attr` con `@{...}` dentro una literal substitution `|...|`
  (vedi `templates/app/deep-chat.html`).
- **Lato Java**: `request.getContextPath() + "/gallery"`, mai `"/gallery"` letterale (`ForwardedHeaderFilter` include il
  prefisso). Es. `GenerationController#delete`/`#deleteImage` per l'header `HX-Redirect`. `redirect:"..."` come nome di
  vista non ne ha bisogno.

## Convenzione: theming

Nessun file CSS: solo Tailwind, config in `src/main/tailwind/tailwind.config.js` (un solo file, vedi "Cosa NON introdurre"). Mai colori
hardcoded fuori da `theme.extend.colors`.

- **Token**: un blocco `{ DEFAULT, dark }` per colore (`canvas: { DEFAULT: '#ffffff', dark: '#0d1117' }`), usato come
  `bg-canvas dark:bg-canvas-dark` (il suffisso `-dark` e' solo una shade in piu'; il prefisso `dark:` decide quando usarla).
  Nomi: `canvas`/`surface`/`ink`/`ink-muted`/`line`/`accent`/`accent-contrast`/`danger` (+ `favourite`, `warning`).
- **Dark mode**: `darkMode: ['selector', '[data-theme="dark"]']`, non `prefers-color-scheme`. Il toggle light/dark/auto
  (Alpine su `<body>` + localStorage/`prefers-color-scheme`) scrive `data-theme`. Lo script di boot inline in `<head>`
  (prima di Tailwind) risolve `auto` e setta `data-theme` PRIMA della compilazione delle classi (evita il FOUC): non
  spostarlo piu' in basso.
- **`@layer base`** (in `fragments/core/layout.html`): solo per elementi "nudi" identici in piu' punti (link, `<code>`, form controls
  senza wrapper, `[x-cloak]`). Specificita' bassa: le classi inline vincono sempre. Tutto il resto e' utility inline, mai
  una nuova regola `@layer`.
- **Bottoni**: mai `<button>` a mano; usare i fragment di `fragments/core/button.html` (`primary`/`neutral`/`danger`/`themeToggle`/
  `dialogOpen`/...) e, per le azioni specifiche dell'app (reset, AI enhance, stop, toggle galleria, overlay dei thumbnail),
  `fragments/app/button-gen.html`; sempre con parametri nominati e passando TUTTI i parametri dichiarati (gli inutilizzati a `null`, es. `hxPost`
  su `danger` se htmx sta sul `<form>`). Se nessuna variante calza, aggiungere un fragment li' (generico → core, altrimenti app).
- **Binding Alpine** (`@click`, `:class`, `:title`, `:placeholder`...) non passano da `th:attr` ne' da `#{...}`: il
  valore dinamico si porta in un attributo `data-*` renderizzato da Thymeleaf (`th:data-theme-value="${value}"`) e si
  legge a runtime con `$el.dataset.themeValue`, lasciando l'espressione Alpine HTML statico. Vale anche per le stringhe
  i18n client-only (vedi toggle impostazioni in `templates/app/deep-chat.html`).
- **Limite**: le varianti Tailwind gestiscono solo due stati per colore (default + `dark:`); un terzo tema (es.
  "high-contrast") richiederebbe di ripensare `theme.extend.colors`, non e' un costo fisso.

## Convenzione: interfacce e storage dei binari

- **Interfacce**: il nome inizia SEMPRE con `I` (`IImageStorageService`, `IGenerations`, `IVectorIndex`); le implementazioni no e dicono il
  ruolo o il backend (`ImageStorageService`, `LocalFsBlobBackend`, `JpaGenerationStore`). Vale per ogni nuova interfaccia, porte comprese.
- **Storage dei binari** (`core.storage`): tutto cio' che l'app serve come file (immagini, mp4, upload sorgente)
  passa da `IImageStorageService`; nessun accesso diretto al filesystem/WebDAV altrove e nessun resource handler statico:
  `/images/**` lo serve `ImageController` leggendo dallo storage. Un nuovo tipo di binario si aggiunge li', non a parte.
  Lo use case `ImageStorageService` (`application`) tiene la logica comune (download, magic bytes, nomi, confinamento del filename);
  il backend (porta out `IBlobBackend`: `write`/`remove`/`size`/`openRange`) e' scelto da `storage.type` (`local` default | `webdav`) con
  `@ConditionalOnProperty` sul bean (`LocalFsBlobBackend` | `WebDavBlobBackend`), alternativi (passando a WebDAV i file locali esistenti
  non sono raggiungibili finche' non si esegue la migrazione). Il download di un URL remoto e' dietro `IRemoteFileFetcher`
  (`HttpFileFetcher`); la porta non espone `MultipartFile` ma `UploadedFile`.
- **Nomi dei file**: OGNI binario nuovo (output di una generazione, upload) si chiama `<sha256 di 32 byte casuali,
  hex>.<ext>` (`StorageNames#newFilename`), mai derivato da id, URL o nome originale: niente collisioni
  nemmeno dopo un reset del DB e nessuna informazione sul contenuto. NON e' un hash del contenuto (nessuna dedup: una riga
  = un file, cancellare non tocca le altre). L'estensione e' solo un'indicazione (su WebDAV il file e' cifrato). Il
  filename e' opaco per l'app: i file storici (`<id>-<n>.<ext>`, `upload-<uuid>.<ext>`) restano validi, nessuna migrazione.
- **Layout fisico annidato** (local e WebDAV): il filename resta piatto (DB, URL `/images/{file}`), ma sul backend vive in
  `ab/cd/<filename>` con `abcd` = primi 2 byte hex dello SHA-256 del FILENAME (`StorageNames#shardPath`:
  derivabile dal solo filename, nessuna colonna in piu'). Su WebDAV le collezioni `ab` e `ab/cd` si creano con MKCOL alla
  prima scrittura. Nessuna migrazione dei file preesistenti: i vecchi file piatti non sono piu' serviti.
- **WebDAV**: contenuti SEMPRE cifrati (AES-256-GCM a chunk da 64 KiB, `ChunkedAesGcmCipher` nel kernel: autenticato, Range/seek
  senza decifrare tutto) con la chiave base64 `storage.webdav.encryption-key` (env `STORAGE_WEBDAV_ENCRYPTION_KEY`, mai nel
  repo; avvio fallisce se manca/non e' 32 byte; persa la chiave i binari sono irrecuperabili). Solo i contenuti sono
  cifrati, i nomi file no. Client = `RestClient.Builder` iniettato (PUT su `.part` + MOVE, GET con Range, HEAD, DELETE,
  MKCOL), nessuna libreria WebDAV.
- **Migrazione locale → WebDAV** (`LocalToWebDavMigrator`, `application`, implementa `IBlobMigration`, sopra le porte `IBlobImportSource` — adapter
  `LocalFsImportSource`, l'unico che tocca il filesystem locale — e `IBlobImportTarget`, implementata da `WebDavBlobBackend`): una tantum, opt-in con
  `storage.migration.from-local.enabled=true` + `storage.type=webdav`; parte all'avvio (`LocalToWebDavMigrationStarter`, adapter in scheduling,
  `ApplicationReadyEvent`) sui file di `storage.images-dir` (esclusi i `.part`), salta quelli gia' sul server (HEAD:
  riavviabile, idempotente), un file che fallisce non ferma gli altri (`ISystemEvents`, operation `migrateLocalToWebDav`). I locali
  restano, salvo `delete-local=true`: ognuno si elimina solo se la dimensione in chiaro riportata dal SERVER coincide. Finche' non ha
  finito, i file non migrati non sono serviti; a fine giro rimettere `enabled=false`.
- **Cache locale** (`EncryptedBlobCache`, `storage.webdav.cache.*`, default 2 GB in `./data/cache`, `0` = off): tiene i
  blob CIFRATI (mai il chiaro), write-through alla scrittura e read-through su miss, eviction LRU, blob oltre il tetto
  letti a range direttamente da WebDAV. Nomi immutabili e unici: nessuna invalidazione se non su `delete`. Un errore di
  cache non fa fallire la richiesta (registrato con `CoreEventSource.STORAGE`).

## Convenzione: migrazioni database (Flyway)

`ddl-auto: validate`: Hibernate controlla solo che lo schema Flyway corrisponda alle entity (altrimenti l'app non parte).
Ogni modifica alla persistenza (entity, campo, indice, rename...) richiede una **nuova migrazione SQL** (DDL **PostgreSQL**).
Due location, una per lato: `src/main/resources/db/migration/core/` (tabelle del core: `system_event`, `api_token`...) e
`src/main/resources/db/migration/app/` (generation*, `replicate_model` + seed, chat_*, `vector_store` + `CREATE EXTENSION vector`,
`lora_preset`); `spring.flyway.locations` le elenca entrambe (in `application.yml`, perche' dipende da quante location ha l'app).

- **Numerazione a timestamp**: `V<AAAA>_<MM>_<GG>_<HHMM>__<descrizione>.sql` (es. `V2026_10_01_1200__core_baseline.sql`). Flyway fonde le
  due location in UNA sequenza e con `outOfOrder=false` un numero progressivo per location (core `V2` dopo app `V1000`) farebbe fallire la
  validazione: i timestamp li ordinano senza coordinarsi. Mai riusare un numero gia' applicato ne' modificare un file gia' eseguito: il
  checksum fa fallire l'avvio. Una migrazione nuova ha SEMPRE un timestamp successivo a tutte le esistenti (di entrambe le location).
- **Nessuna FK dal core all'app** (il core deve funzionare senza `db/migration/app`); le tabelle dell'app possono riferire il core solo se serve davvero.
  Le colonne di collegamento fra feature (es. `chat_message.generation_id`, `generation.conversation_id`) sono `Long` nelle entity (mai un
  `@ManyToOne` verso l'entity di un altro esagono). La FK nello schema c'e' solo nella direzione delle dipendenze (`chat_message.generation_id`
  -> `generation`, `ON DELETE SET NULL`: chat -> generation); `generation.conversation_id` NON ha FK (generation non conosce chat): chi cancella una
  conversazione scollega le sue generazioni con `IGenerations#detachFromConversation` (`ChatConversationService#delete`).
- Regole del dialetto: identificatori **minuscoli non quotati** (Hibernate non quota), testo lungo `text` (nelle entity
  `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`, MAI `@Lob`: su PG sarebbe `oid`), `timestamptz`, `bytea`, enum Java = `varchar` senza
  ENUM/CHECK di DB. Un modello nuovo si censisce con un `INSERT` in `replicate_model` (`version` NULL = "ultima versione"): `form_type` e' un
  varchar, niente `ALTER` di ENUM. I baseline `core` e `app` sono lo storico squashato (passaggio a Postgres e poi alla divisione core/app,
  senza migrare i dati). In sviluppo si riparte da zero con `docker compose down && rm -rf data/postgres`.
- `FlywayCoreAppMigrationTest` prova core+app e una migrazione core piu' recente applicata dopo.

## Convenzione: internazionalizzazione (i18n)

Tutto il testo utente-visibile (template e messaggi d'errore Java) passa da `MessageSource` + `#{...}`, mai stringhe
hardcoded. La lingua segue `Accept-Language` (`AcceptHeaderLocaleResolver`, default Boot: nessun bean da scrivere, niente
switcher/cookie/sessione). Bundle (`spring.messages.basename: messages,messages-core`, in `core.yml`):
- `messages-core.properties` / `messages-core_en.properties`: il core (`html.lang`, `header.menu.*`/`header.theme.*`, `events.*`, `bell.*`,
  `toast.*`, `pagination.*`, `tokens.*`, `imagestorage.*`, `webdav.*`...) e le etichette delle sorgenti del core (`events.source.STORAGE|TOKENS|INTERNAL`);
- `messages.properties` (italiano, default/fallback anche per locale non mappate) / `messages_en.properties`: l'app (incl. `app.brand|title`,
  `events.source.REPLICATE|OPENROUTER|SEARXNG|LORAS|HUGGINGFACE|TRAINING`, `events.link.generation|conversation|training`).
Il testo del core NON nomina servizi o provider dell'app (`events.intro`, `tokens.intro` sono generici): le pagine del core mostrano in piu' la riga facoltativa
`events.intro.app`/`tokens.intro.app` se l'app la definisce nel suo bundle (`#messages.msgOrNull`).
Le chiavi dei due bundle sono **DISGIUNTE** (niente shadowing: lo impone `TemplateRenderingTests.coreAndAppBundlesDefineDisjointKeys`); una chiave
nuova va nel bundle del lato a cui appartiene il codice che la usa. Nuova lingua: nuovi `messages_<locale>.properties` e
`messages-core_<locale>.properties` con le stesse chiavi e aggiornare `TemplateRenderingTests.messageBundlesHaveMatchingKeys`. Chiavi
punto-separate `<pagina-o-componente>.<categoria>.<elemento>` (`header.nav.home`, `replicate.error.tokenMissing`); i due bundle sono
comunque un unico namespace per template ed errori Java.

- **Apostrofi (bug silenziosi)**: Spring usa `MessageFormat` SOLO con argomenti non nulli. Senza parametri
  (`#{key}`, `Messages.get(code)`) gli apostrofi restano letterali (`l'app`); con parametri (`#{key(${arg})}`,
  `Messages.get(code, args...)`) vanno raddoppiati (`''`) o spariscono. Argomenti numerici (id, durate) passano per
  `NumberFormat` con separatori di migliaia: usare `{0,number,#}`, non `{0}`.
- **Lato Java**: iniettare `org.dual.replicate.core.kernel.i18n.Messages` (wrapper su `MessageSourceAccessor`, locale della richiesta
  via `LocaleContextHolder`) ovunque un errore possa arrivare all'utente (oggi `ReplicateClient`, `SearxngClient`,
  `GenerationService`, `ImageStorageService`, `GenerationController`, `DeepChatApiController`, `ChatService`).
  Risolvere al call site, prima di costruire l'eccezione, mai nel costruttore. Se la classe ha gia' una variabile
  `messages` (es. `ChatService`), chiamare il campo iniettato altrimenti (li' `i18n`).
- **`<html lang>`** viene dal bundle (`html.lang=it|en`, `th:lang="#{html.lang}"` sul decoratore), non da
  `#{#locale.language}`: su una locale non mappata il contenuto e' comunque italiano.
- **Limiti accettati**: `Generation.errorMessage` e' salvato gia' tradotto nella locale di chi ha generato l'errore (resta
  congelato); il catch-all di `DeepChatApiController` traduce solo il prefisso `"Errore nel contattare l'assistente: "`,
  non i messaggi di eccezioni di librerie terze.

## Convenzione: errori ed eventi di sistema, errori delle chiamate remote e stati terminali

Il registro errori e' un **registro generico di eventi di sistema** (`SystemEvent`, tabella `system_event`, pagina `/system/events`;
`/errors` reindirizza), classificati per severita': `ERROR` (gli errori di sempre) e `WARNING` (avvisi: oggi la scadenza dei token,
altri seguiranno; `SystemEventSeverity#isAtLeast` per le soglie). Ogni evento ha source (`EventSource`: interfaccia nel kernel,
implementata da `CoreEventSource` e `AppEventSource`; etichetta da `events.source.<NAME>`), `operation`, `subject` opzionale (a cosa si
riferisce: `token:12`, `generation:12`, `conversation:5`, vedi `AppEventSubjects`; il link "apri" lo risolve `IEventLinkResolver`/`AppEventLinks`)
e `acknowledgedAt` (visualizzato, per la campanella). La porta e' `ISystemEvents` (impl `SystemEventService`).

- **ERRORI**: ogni chiamata a Replicate, OpenRouter (Spring AI) o SearXNG, e ogni errore interno non gestito, passa da
`ISystemEvents#record(operation, throwable[, subject])` (la source si ricava da `RemoteServiceException#source()`, `ISystemEvents.sourceOf`;
la forma `record(source, operation, throwable[, subject])` resta per gli errori non remoti, `CoreEventSource.INTERNAL`): MAI un `catch`
che ingoia o soltanto logga. `record` logga con stack, salva/aggiorna una riga `system_event` (transazione propria, non lancia mai;
consultabile da `/system/events`, `SystemEventController`) e alla prima occorrenza di una *serie* consegna il toast a `IToastNotifier`
(`PushToastNotifier`) → SSE `system-event` → toast in tutte le tab con `live-events.html`. Serie = stesso (severity, source, operation,
subject, tipo eccezione) entro 5 minuti: aggiorna `occurrences`/`last_seen_at` invece di creare riga/toast a ogni poll durante un outage.
La query di serie (`SystemEventRepository#findOpenSeries`, adapter `persistence` del core) ha predicati null-safe scritti a mano su `subject`: un JPQL
`= :x` con :x null non combacia mai e ogni evento senza `subject` creerebbe riga e toast nuovi.
- **AVVISI**: `ISystemEvents#warn(source, operation, subject, message)` (messaggio gia' tradotto), stessa semantica (non lancia,
  un toast per serie) ma finestra di serie `app.events.warning-series-window` (24h): un controllo periodico non deve ripetere il toast
  ogni pochi minuti. Toast con `severity` nel payload (`WARNING` = bordo `warning`, token colore in `tailwind.config.js`).
- **Campanella** (`fragments/core/notification-bell.html`, nell'header fuori dallo slideover): si accende (badge, `danger` se c'e' un ERROR,
  altrimenti `warning`) quando ci sono eventi NON visualizzati di severita' >= WARNING; il pannello elenca gli ultimi 5; il click
  porta a `/system/events?event=<id>` (marca quell'evento come visualizzato e lo evidenzia). Il contenitore statico non viene mai
  sostituito (stato Alpine `open`), il contenuto si ricarica da `GET /system/events/bell` al load, ogni 30s e sugli eventi client
  `system-toast` (window) / `system-event` (body). "Segna tutti come letti" = `POST /system/events/seen` (`ISystemEvents#markAllSeen`). Una
  ripetizione di una serie gia' visualizzata NON torna non letta; chi rimuove la causa (token rinnovato/cancellato) chiama
  `ISystemEvents#markSeenBySubject`.
- **Eventi client**: `system-toast` (window, toast; anche via header `HX-Trigger`) e `system-event` (body, ricarica lista/campanella;
  stesso nome dell'evento SSE).

- **Overlay "operazione in corso"** (`fragments/core/busy-overlay.html`, incluso da `layout.html`, core): blocca l'INTERA UI (`inert` su header/main; i
  toast restano fuori) finche' una richiesta non finisce, cosi' un secondo click non innesca una seconda operazione (es. una seconda prediction a
  pagamento). Regola di DEFAULT: ogni richiesta htmx NON-GET blocca (i GET di polling/paginazione/SSE no); sull'elemento htmx `data-busy="off"|"on"`
  (opt-out di un non-GET leggero / opt-in di un GET lento), `data-busy-text` (messaggio gia' tradotto, senza: `busy.default`), `data-busy-delay` (ms
  prima che la grafica si veda, default 250: il blocco e' immediato, lo sfondo/spinner/barra compaiono solo se l'attesa e' lunga, niente lampeggio
  sulle azioni rapide). Si chiude al `loadend` dell'XHR (successo, 4xx/5xx, rete, timeout, abort): il fallimento lo mostra il canale toast di sempre.
  Se la risposta e' una navigazione (`HX-Redirect`/`HX-Location`/`HX-Refresh: true`) NON si chiude e diventa subito visibile: sparisce con la
  pagina; `pageshow` persisted (bfcache) la azzera. Un `<form method=post>` nativo blocca fino all'unload. Watchdog `htmx.config.timeout` 180 s
  (> read-timeout del server) + toast su `htmx:timeout`. Fuori scope: i turni di /deep-chat (fetch del Web Component, ha il proprio stato di attesa).
  Un nuovo endpoint lento non richiede codice: basta un `hx-post/put/delete` (e un `data-busy-text` se serve un messaggio specifico).
- **Toast** (`fragments/core/toast.html`, incluso da `layout.html`): ascolta l'evento window `system-toast` ({key, message, transient, severity}),
  dedupe per `key`. Sorgenti: SSE; header `HX-Trigger` (`HtmxEvents#addToastHeader(response, ToastMessage)` e `#addHxTrigger(response, event, detail)`,
  nel kit `core.web`: l'esito `ISystemEvents.Recorded` e' un `ToastMessage`; la porta `ISystemEvents` non conosce il protocollo HTTP;
  usati da `GenerationController` create/enhance/cancel, `LoraController`, `TokenController`, `SemanticSearchController` e
  `UnhandledExceptionResolver`); listener globali `htmx:responseError`/`htmx:sendError` (solo se la risposta non portava gia' un toast).
- **Chi registra**: dove l'eccezione e' *gestita/ingoiata* (servizi in background, tool, watcher); se risale a un
  controller la registra il controller (`create`, `enhancePrompt`). `UnhandledExceptionResolver` (LOWEST_PRECEDENCE) e
  `AsyncErrorConfig` sono la rete per il resto.
- **Nessuno stato indefinito**, tre reti: (a) `IGenerations#refresh` (`GenerationService`) non lascia stati parziali: download/
  post-processing in try/catch → `FAILED` + file ripuliti; un errore di poll *transitorio* (`RemoteServiceException#isTransient`:
  rete, timeout, 429/5xx) NON fallisce la generazione ma il timeout di business vale comunque e annulla la prediction; uno
  *permanente* (4xx) la fallisce subito. (b) `GenerationRecoveryService` (adapter scheduling di generation) all'avvio
  (`ApplicationReadyEvent`, `@Order(AppStartupOrder.GENERATION_RECOVERY)`) fa avanzare ogni PENDING/PROCESSING; poi `ChatRecoveryService`
  (`ChatRecoveryScheduler`, `@Order(AppStartupOrder.CHAT_RECOVERY)`) riavvia i watcher persi e scrive i turni mancanti. (c) gli stessi servizi, ogni
  `app.recovery.sweep-interval`, chiudono le righe oltre timeout e scrivono i turni di chat mancanti (`ChatGenerationWatcher#persistOutcome`,
  idempotente). Disattivabile con `app.recovery.enabled=false` (i test).
- **Cancellare o far scadere** una generazione in corso annulla la prediction (`cancelPredictionQuietly`); se `create`
  non riesce a salvare la riga dopo aver creato la prediction, la annulla.
- **Chat**: se l'LLM fallisce, `SpringAiAssistant` lancia `AssistantException` (con gli id delle generazioni gia' avviate dai tool: avranno
  comunque il watcher) e `ChatService#reply` scrive un turno ASSISTANT d'errore (`ChatMessage.error`: in
  rosso, mai rimandato all'LLM) e lancia `DeepChatFailedException` (gia' registrata: `DeepChatApiController` mostra solo il
  messaggio). TUTTI i tool catturano da soli e rimandano il testo d'errore al modello (in inglese): un rifiuto atteso (`REJECTED`, es. troppi tag, un file non della generazione) torna com'e'
  e non e' un evento; un guasto vero e' registrato (`systemEvents.record(operazione, e)`) e il testo dice al modello di avvisare l'utente.
- **WebDAV** (`WebDavBlobBackend`, via `RemoteCaller` come gli altri): PUT/MOVE/DELETE/MKCOL e gli HEAD della
  migrazione ritentano (`RetryPolicy.DEFAULT`) i soli transitori; le letture per `/images/**` NO (`RetryPolicy.NONE`: il
  browser riprova, un retry allungherebbe la richiesta). `NoSuchFileException` (404) e' `passThrough`, non un errore. Un `.part`
  che non si riesce a ripulire e' registrato (`cleanupPart`); un file che non si riesce a cancellare resta orfano (registrato
  `deleteFile`, nessun recupero automatico). `IBlobBackend` e `IImageStorageService` lanciano SOLO `StorageException` (mai `UncheckedIOException`):
  `REJECTED` per gli esiti attesi (file inesistente, upload troppo grande/di tipo non valido).
- **Timeout**: `spring.http.clients.connect-timeout/read-timeout` valgono per tutti i `RestClient.Builder`
  auto-configurati (Replicate, download, Spring AI); SearXNG ha un timeout piu' stretto proprio. Un nuovo client HTTP
  usa il `RestClient.Builder` iniettato, mai `RestClient.create()`.
- **Locale**: `SystemEventService` risolve il toast con la locale del thread; un thread async la imposta prima
  (`ChatGenerationWatcher#watch`), il recupero usa l'italiano.

### Errori e retry generici (`core.kernel.remote`) e checklist "nuovo servizio remoto"

Un solo tipo, un solo esecutore, una sola traduzione HTTP:

- **`RemoteServiceException`** (radice di `ReplicateException`, `SearxngException`, `StorageException`, `OpenRouterException`, `TokenException`)
  porta `source()` (un `EventSource`) e `kind()`: `TRANSIENT` (rete/timeout/408/429/5xx, ritentabile), `PERMANENT` (4xx, 507, risposta illeggibile),
  `CONFIGURATION` (token/credenziali mancanti), `REJECTED` (rifiuto applicativo ATTESO: validazione, "non trovato", rifiuto del
  modello). `isReportable()` = tutto tranne `REJECTED`: solo i reportable si registrano/notificano (`GenerationController#create`
  ne decide cosi' "toast o solo form"); il resolver risponde 502 (guasto di un servizio esterno), 422 (`REJECTED`, senza riga
  in `system_event`, con un toast htmx del solo messaggio) o 500 (bug interno). `ReplicateException(String)` = `REJECTED`; con
  causa = `PERMANENT`: un errore vero senza causa va costruito con `Kind` esplicito.
- **`RemoteCaller#call(operazione[, RetryPolicy], supplier)`**: traduce qualunque eccezione e ritenta solo i `TRANSIENT`.
  `RetryPolicy` sempre esplicita per le operazioni NON idempotenti/a pagamento: `RetryPolicy.NONE` (es. `createPrediction`: un
  ritentativo potrebbe fatturare una seconda prediction). Un errore gia' classificato non viene ritradotto anche se RestClient lo
  ha incapsulato in una `ResourceAccessException`. OpenRouter/Spring AI ritenta gia' da se' (`spring.ai.retry.*`, a livello HTTP,
  prima dei tool): `OpenRouterCalls.CALLER` traduce senza un secondo strato di retry (rieseguire un turno rieseguirebbe i tool).
- **`RestClientTranslator`**: unica regola stato HTTP -> `Kind`; messaggi da `<prefix>.error.httpError|connectionFailed`.

Per aggiungere un servizio remoto:
1. Valore in `AppEventSource` (app) o `CoreEventSource` (core) + `events.source.<X>` nel bundle del lato giusto (`messages*` o `messages-core*`) nelle due lingue.
2. `class FooException extends RemoteServiceException` (costruttore `(String message, Throwable cause, Kind kind)`), nel `domain` o nell'adapter del sottosistema.
3. Client (adapter `out`) `extends RestRemoteClient` con prefisso `foo` (chiavi `foo.error.httpError|connectionFailed` nel bundle), che implementa la porta `out`
   del sottosistema (es. `SearxngClient` → `IWebSearchGateway`, `ReplicatePredictionGateway` → `IPredictionGateway`), e ogni
   chiamata in `remote.call("operazione", () -> ...)` (`RetryPolicy.NONE` se non idempotente). Esempio minimo: `RestRemoteClientTest`.
4. Chiamante in background: `systemEvents.record("operazione", e, ...)`; controller: `systemEvents.record(...)` + `HtmxEvents#addToastHeader`
   oppure lasciar risalire (il resolver registra con la source giusta). Niente `catch` che ingoia.
- **Front end**: nessun codice per servizio. Il toast (`fragments/core/toast.html`) e' guidato dal payload `{key, message,
  transient}`; `transient: true` aggiunge "Riprova tra qualche istante". `HtmxEvents#addHxTrigger` FONDE gli eventi
  nell'unico header `HX-Trigger` (un controller puo' emettere `gallery-update` e un toast insieme). Un bottone "Riprova" generico
  sul toast e' escluso di proposito: rieseguire una POST (`create`) creerebbe una seconda prediction a pagamento.

## Token API (CivitAI, HuggingFace) e cifratura dei segreti

CRUD in `/tokens` (`core.tokens`: `TokenController`, `IApiTokens`/`ApiTokenService`, `fragments/core/tokens.html`: dialog Pines come le note di
`/search`), per scaricare LoRA privati con flux-dev-lora. Il core non sa NULLA dei servizi: `ApiToken.provider` e' una stringa e i servizi
offerti li elenca l'app implementando `ITokenProviderCatalog` (`AppTokenProviders`, valori di `ApiTokenProvider`; etichette `tokens.provider.<NAME>`).
Il token si salva con un NOME (unico per provider) e una scadenza facoltativa (data inserita a mano:
nessuno dei due servizi la espone), si sceglie per nome nelle select delle form (`hfTokens`/`civitaiTokens` nel Model, solo dove si
renderizza il fragment del form-type: `GenerationController`, `DeepChatController`; `IGenerationForms#extraFormOptions` (`GenerationFormService`) su `IApiTokens#options`).
Dopo il salvataggio non si vede piu': la UI mostra solo gli ultimi 4 caratteri (`token_hint`). Mai il segreto in log, eventi, toast o modello Thymeleaf.

- **Cifratura**: `ISecretCipher` (impl `SecretCipher`, `core.secrets`) usa la STESSA chiave e lo STESSO algoritmo dei binari WebDAV
  (`ChunkedAesGcmCipher`, AES-256-GCM; `encryptBytes`/`decryptBytes` per i valori piccoli): `app.secrets.encryption-key` = `${storage.webdav.encryption-key}`
  (in `core.yml`), cioe' `STORAGE_WEBDAV_ENCRYPTION_KEY`, nessun segreto nuovo. Con `storage.type=local` la chiave NON e' obbligatoria all'avvio
  (il segnaposto di `.env.example` non e' base64 valido): senza, `ISecretCipher#isConfigured()` e' falso, `/tokens` mostra un alert e salvare un
  token e' un errore `CONFIGURATION` (`SecretException`/`TokenException`). Chiave persa = token irrecuperabili (come i binari); nessuna rotazione;
  il ciphertext non e' legato alla riga. `pom.xml` fissa una chiave di test per Surefire (il `.env` reale non deve cifrare nei test).
- **Uso**: `GenerationService#doCreate` -> `TokenInputResolver#resolveInto(input)` -> `IApiTokens#resolve(id, provider)` (vedi "LoRA al volo").
  Scaduto o inesistente = `TokenException` `REJECTED`, nessuna prediction a pagamento parte.
- **Scadenza**: `TokenExpiryScheduler` (adapter scheduling del core: all'avvio e ogni `app.tokens.expiry-check-interval`, spento nei test con
  `app.tokens.expiry-check-enabled=false`) chiama `IApiTokens#checkExpiries`: per i token scaduti o scadenti entro
  `app.tokens.expiry-warning-days` (15) registra un AVVISO (`CoreEventSource.TOKENS`, operation `tokenExpiring`/`tokenExpired`, subject
  `token:<id>`). Creare/modificare un token con scadenza vicina avvisa subito.

## Convenzione: select (Pines)

Mai una `<select>` nuda: ogni selezione usa il componente `fragments/core/select.html` (Alpine + Tailwind, stile Pines; test strutturale
`everySelectIsWrappedByThePinesSelectComponent`). La `<select>` nativa RESTA nel DOM (`sr-only`) come fonte di verita': `FormData`,
`hx-trigger="change"`/`hx-include`, `required`, `x-model`, il sync di `templates/app/deep-chat.html` e i test sulle `<option selected>` non cambiano; il
componente e' solo la UI, legge le `<option>` e scrive il valore con gli eventi nativi `input`+`change`. Uso:

```html
<div class="relative" x-data="pinesSelect" x-bind="root">
    <select id="..." name="..." class="sr-only" tabindex="-1" aria-hidden="true"> ...<option>... </select>
    <div th:replace="~{fragments/core/select :: ui}"></div>
</div>
```

Un cambio di valore da codice (senza eventi) va annunciato con `select.dispatchEvent(new Event('pines-select:sync'))` (vedi `templates/app/deep-chat.html`,
`button-gen :: resetToDefaults`). Se il pannello fosse tagliato da un contenitore con overflow: fallback `@alpinejs/anchor` o posizione `fixed`.

## Convenzione: stato delle form di parametri di generazione (client)

In `/generations/new` modello + parametri + prompt sono una preferenza del browser, non dati applicativi: stanno in `localStorage` e cambiano
SOLO per modifica dell'utente o "Reimposta ai default" esplicito, mai per una navigazione. In `/deep-chat` invece il form e' PER CONVERSAZIONE e vive sul
server (vedi "Form per conversazione" sotto): localStorage resta solo come fonte per le conversazioni precedenti alla persistenza lato server. Un solo script, `fragments/app/generation-settings-persist.html :: script`
(da includere DOPO il markup), attivo su ogni `form[data-persist-key]`: `/deep-chat` (`deepChat.generationSettings`) e `/generations/new`
(`generate.image|video`, una chiave per tipo di pagina, separata dalla chat). Configurazione via `data-*` sul form:
`data-persist-key`, `data-persist-no-restore` (campi che il server ha valorizzato da un link esplicito, il prompt di "Anima": non si
ripristinano ma si scrivono), `data-shared-accept` (chiavi dello slot globale che il form prende: `prompt seed` su ogni pagina), `data-persist-ignore` (mai scritti: `version`, un hash pinnato ripristinato di nascosto userebbe il modello sbagliato a
pagamento). A ogni sync il form emette `generation-settings:sync` (detail = tutti i campi, hidden inclusi): la chat lo inoltra a
`window.setDeepChatSettings`, registrando il listener PRIMA dell'include. Il listener `input` e' delegato su `document` perche' il form di
`/generations/new` viene ri-renderizzato dopo un create rifiutato (`createFailed` rimette il `seed` nel Model: non e' fra i `defaultFields`). Non
persistiti: file `sourceUpload`, hidden. Un nuovo form-type non richiede nulla qui.

**Form per conversazione** (`/deep-chat`): la configurazione del form (modello, LoRA, tutti i parametri) si salva con la `ChatConversation`
(colonna `generation_settings_json`, testo JSON GREZZO che la chat non interpreta: lo stesso snapshot che lo script scrive in localStorage, `data-persist-ignore`
applicato) e scegliere una conversazione la ripristina. `DeepChatController#page` mette lo stato nel Model; il `<form>` porta `data-persist-server-state` (JSON,
attributo assente se NULL) e `data-persist-save-url` (`@{/deep-chat/{id}/settings}`). Semantica: `NULL` = conversazione precedente alla migrazione (il restore adotta
localStorage e lo salva SUBITO sul server, non alla prima modifica); `"{}"` = default del catalogo (ogni conversazione nuova: lo scrive il costruttore dell'entity,
quindi OGNI percorso di creazione); oggetto pieno = stato salvato, che e' l'UNICA fonte del restore (localStorage ignorato). Lo script ripristina da
`readState(form)`, emette `generation-settings:restored` a restore finito e SOLO da li' salva (`fetch` keepalive `POST /deep-chat/{id}/settings`, JSON, debounce
800 ms, flush su `pagehide`/`visibilitychange`, nessuna richiesta se lo snapshot e' invariato): lo stato intermedio di un cambio modello non sovrascrive quello della
conversazione. NON htmx (un non-GET attiverebbe l'overlay "operazione in corso"). `IChatConversations#saveGenerationSettings` valida (oggetto JSON, max
`MAX_SETTINGS_BYTES` = 16 KB, 422 altrimenti) e NON chiama `touch()` (la sidebar non si riordina per un cambio di parametri). Due tab sulla stessa
conversazione: vince l'ultimo salvataggio.

**Slot globale prompt/seed** (`localStorage['generation.shared']` = `{prompt?, seed?}`): "Usa prompt" (uno per generazione) e "Usa seed" (PER FILE, nella
griglia del dettaglio; `button-gen :: pushShared`, scrittura in `fragments/app/generation-shared-slot.html :: pusher`) NON aprono un caso d'uso: spingono nello
slot. Lo legge `applySharedSlot` nello script di persistenza (avvio, evento `storage` di un'altra tab, bfcache) e CONSUMA ogni chiave applicata; una chiave
resta se il form non la accetta, non ha il campo (il prompt in Deep Chat) o il server l'ha gia' valorizzata da un link ("Anima" vince). Il seed per file e'
`Generation#reusableSeedOf` (tabella `generation_image_seed`, solo se i log hanno un "seed" per output). Altrimenti c'e' un solo seed di batch, e
riproduce SOLO la prima immagine (verificato con due prediction su flux-lora-ff3: la seconda immagine di un batch nasce da un seed derivato che nessun log
riporta, e non e' seed+1): la prima mostra `(batch)` col bottone, le altre NESSUNA riga seed (il seed del batch non e' il loro). Un seed vero per ogni immagine richiede
una prediction per immagine (`num_outputs=1`), non implementato.
La chiave dello slot e' duplicata nei due script: tenerle allineate.
**Reset al default dei campi numerici**: OGNI `<input type="number">` di un `generation-params-<form-type>.html` passa dal fragment condiviso `fragments/app/generation-params-number.html :: field(id, name, value, defaultValue, min, max, step, extraClass)` (la `<label>` resta fuori; test strutturale `NumericResetFieldsTests#everyNumericParameterFieldUsesTheSharedResetFragment`): l'input e `button-gen :: resetNumber`, che compare solo se il valore e' diverso dal default (un campo svuotato conta come variato: l'handler lo omette e Replicate userebbe un SUO default; un campo `readonly` no, es. i passi di ff3 con "schnell") e al click riscrive il default e dispatcha `input` (stesso salvataggio del seed). Il default arriva dal server in `data-default` da `fieldDefaults` nel Model (= `IGenerationParameterHandler#defaultFields`, chiave `IGenerationForms.FIELD_DEFAULTS`: lo mettono `GenerationController#populateFormTypeFields` e `IGenerationForms#formModel` per il primo render della chat): un campo nuovo ha il reset gratis se sta nei `defaultFields` (il test controlla che `data-default` coincida). Serve JS (non basta CSS come per il seed, il cui default e' "vuoto") e lo script di persistenza scrive i valori con `.value` senza eventi `input`: il componente ascolta anche `generation-settings:restored` (emesso con `bubbles: true` a fine restore). Il fragment ha un `x-data` proprio, quindi un `x-ref` al suo interno NON e' visibile al componente esterno (ff3 legge il campo dei passi per id, `steps()`). Il tetto di `num_outputs` si passa come `maxNumOutputs` (`@ModelAttribute` dei due controller: Thymeleaf vieta `T(...)` nei parametri di un fragment). Il seed resta col suo reset (sotto).
**Campo seed** (`fragments/app/generation-params-seed.html :: field`, incluso con `th:replace` da OGNI `generation-params-<form-type>.html`: un form-type nuovo con seed lo include, non lo copia):
vuoto = casuale (il default); `button-gen :: resetSeed` e' un'icona overlay nel campo che lo svuota e dispatcha `input` (come una cancellazione digitata, quindi lo stato si salva), visibile solo
con un seed valorizzato in puro CSS (`peer-placeholder-shown:hidden`: segue anche i `.value` scritti da codice, senza stato Alpine da sincronizzare).

**"Usa configurazione"** (dettaglio di OGNI generazione, `button-gen :: reuseConfig`; "Rigenera" della chat): NON passa dallo slot ma da un link
`/generations/new?config=<id>[&file=<file>]` e il SERVER compila il form (`GenerationController#form` → `reuseForm`). I dati sono quelli GIA' salvati, nessuna
colonna in piu': `IGenerations#reuseConfig(id, file)` → `GenerationConfig` (modello, prompt, seed di quel file via `reusableSeedOf`, `parametersJson` nel
vocabolario del provider, sorgente solo se ancora un'immagine valida per `findAnimatableSource`); i parametri diventano campi di form con l'inverso degli
handler (`IGenerationParameterHandler#toFormFields`, esposto da `IGenerationForms#formFields`: boolean come `"true"`/`"false"`, il form-type dei fine-tune LoRA rimappa `model`→`flux_model`,
le chiavi non note al form-type si scartano). Un campo di un fragment `generation-params-<form-type>.html` deve stare fra i `defaultFields()` del suo handler, altrimenti
sparisce in silenzio (`GenerationFormFieldsCompletenessTest` lo impone). La pagina e' quella del tipo del modello (immagini/video/edit), con `data-persist-fresh` sul
form: arrivandoci lo script di persistenza non ripristina nulla (ne' modello, ne' campi, ne' slot) ma scrive subito lo stato. **La `version` non si spinge MAI**
(il campo sta fuori dai campi del form-type: con un cambio di modello partirebbe, e verrebbe fatturato, l'hash del modello sbagliato). Con `file` (solo la chat "Rigenera", per file) `num_outputs`=1 e il seed e' quello del file; il dettaglio NON passa `file`: `num_outputs` com'era e seed della prima immagine.
Non riproducibili (e quindi la chat non propone "Rigenera", `ActionProposalTool`, per le generazioni che li hanno: `sourceUploadFilename`/`maskUploadFilename`, o una sorgente da generazione sparita): la maschera di inpainting e una sorgente da upload (un `<input type=file>` non si valorizza); un modello disattivato o una generazione
sparita danno la pagina normale con un messaggio (`generateForm.error.reuse*`). "Usa prompt"/"Usa seed" restano (slot).

## Ricerca semantica (PgVectorStore su PostgreSQL+pgvector, embedding locali)

Stesso Postgres dei dati, nessun servizio in piu'. Il sottosistema `app.search` espone solo tipi di dominio (`SearchableDocument`,
`IndexedDocument`, `ScoredDocument`, `DocumentFilter`, `IndexStats`) e NON fa uscire Spring AI (`Document`/`Filter`/`VectorStore`) dall'adapter
`adapter.out.vector`: chi vuole cercare per significato usa `IArchiveSearch` (porta in), l'indice e' dietro `IVectorIndex` (porta out,
impl `PgVectorIndex` sopra `PgVectorStore`): un domani si puo' sostituire con Elasticsearch/Qdrant cambiando quell'adapter.

- **Embedding**: `EmbeddingModel` locale (`TransformersEmbeddingModel`, ONNX) con `multilingual-e5-small` quantizzato (384 dim,
  italiano/inglese, ~120 MB). Il modello e il tokenizer si scaricano UNA volta al primo avvio da Hugging Face in `./data/models`
  (fuori da git; niente download in build). `spring.ai.model.embedding=transformers` evita che l'autoconfig OpenAI crei un secondo
  `EmbeddingModel`. I modelli e5 vogliono i prefissi `passage: ` (documenti) e `query: ` (ricerche): li applica
  `E5PrefixEmbeddingModel`, decorator che solo lo store vede (`getEmbeddingContent` = percorso dell'indicizzazione, `embed(String)` =
  ricerca), chi lo usa passa il testo nudo. I punteggi e5 sono compressi (0.7-0.9): usare top-K, non soglie fisse.
- **`PgVectorStore`** (`SemanticSearchConfig`; dipendenza `spring-ai-pgvector-store`, NON lo starter: niente autoconfig): tabella
  `vector_store` creata da Flyway (migrazione `app`, `initializeSchema=false`): `id text`, `content`, `metadata json`, `embedding vector(384)`.
  Punteggio = `1 - distanza coseno`. **Nessun indice ANN** (HNSW/IVFFlat) di proposito: `/search` vuole TUTTA la classifica sopra soglia
  (`IArchiveSearch#searchAll`: `topK` = numero di documenti) e un indice approssimato tronca a `ef_search` (40); lo scan esatto costa pochi ms a decine di migliaia
  di righe. Cambiare il modello con dimensioni diverse da 384 = nuova migrazione `ALTER ... TYPE vector(N)` (dopo aver svuotato la
  tabella) + reindicizzazione. Il filtro (`DocumentFilter`: `type`, `from`/`to` su `createdAt`, `kind` IMAGE/VIDEO, `favouriteOnly`; gli ultimi due combaciano solo con le generazioni,
  e la UI con "media"/"preferiti" e tipo "tutti" restringe a `type=generation`) e' tradotto da `PgVectorIndex` in un `Filter.Expression` Spring AI
  e da questo in jsonpath: operatori **EQ, NE, IN, NIN, AND, OR, GT/GTE/LT/LTE** (NON NOT ne' ISNULL/ISNOTNULL); numeri solo per i
  confronti, una chiave assente non combacia. Soglia 0 = esclude solo la similarita' esattamente 0 (distanza `<` stretta).
- **Generazioni = artefatti ricercabili**: UN documento per generazione riuscita (`GenerationSearchSource`), non uno per file. Il testo embeddato
  e' il prompt + (dopo `DocumentTypes.TAGS_SEPARATOR`, `\n\n#tags: `) tag d'indice it/en prodotte da `GenerationSearchText` (tipo di media,
  modello, orientamento da `aspect_ratio`/`width`x`height`, risoluzione, nomi dei LoRA + nome/trigger words del preset che ne ha la stessa
  sorgente, "animazione/modifica di un'immagine" se derivata); seed, costo, passi NON entrano. Le tag sono vocabolario d'indice, NON testo per
  l'utente: UI e `ArchiveSearchTool` mostrano `DocumentTypes.visibleText` (`IndexedDocument#visibleContent`); solo il `<pre>` "Dettagli" e' grezzo.
  Il prompt cede spazio alle tag sotto `MAX_CHARS`. Metadata aggiuntivi (non embeddati, quindi un cambio non ri-embedda): `model`, `favourite`
  (bool, almeno un file con la star), `outputs`, `files` (max 4, i preferiti per primi) e `favouriteFiles`: `/search` ne ricava le miniature
  (`<img>`/`<video>` da `/images/{file}`, `onerror` nasconde un file appena cancellato) senza conoscere `generation`. I `<video>` prendono la `src` solo
  quando entrano nel viewport (`x-intersect.once`, plugin Alpine `intersect` in `layout.html`): una pagina di 20 risultati non apre decine di Range request.
- **Metadata**: ogni documento ha `type` (stringa, vedi `DocumentTypes`: `generation`, `chat`, `conversation`, `note`) e `refId` (numero), opzionali
  `conversationId`, `role`, `kind`, `title` e `createdAt` (epoch millis della CREAZIONE del contenuto; `SearchableDocument#of` li costruisce), piu' le
  chiavi **riservate** scritte da `VectorIndexer`: `contentHash` (SHA-256 del testo), `embeddingModel` (URI ONNX) e `indexedAt` (ultima
  indicizzazione; `IndexedDocument#createdAt` ricade su di esso se manca `createdAt`). Nello store non ci sono colonne per hash/modello: stanno li'.
- **`VectorIndexer`** (scrittura, unico punto da cui l'app aggiunge/cancella/ri-embedda): salta i documenti invariati (stesso hash e
  modello), se cambiano solo i metadata li riscrive senza ri-embeddare, altrimenti `vectorStore.add` (upsert). Cambiare modello (URI ONNX =
  id del modello) => alla riconciliazione successiva si ri-embedda tutto. **`VectorDocumentRepository`** (lettura JDBC): `find`, `list`
  (paginato, piu' recenti prima per `createdAt`), `countsByType`, `idsOfType`, `count`; il filtro usa lo stesso convertitore jsonpath dello store.
- **`ArchiveIndexService`** (`IArchiveIndex`) allinea l'indice con una riconciliazione idempotente (non ganci su ogni `save`): chiede a TUTTI i
  bean `ISearchableSource` i loro documenti (generation: prompt delle generazioni SUCCEEDED, `type=generation`; chat: messaggi non d'errore,
  `chat`, e titoli, `conversation`), aggiunge i mancanti/cambiati, rimuove i documenti dei `types()` dichiarati dalle sorgenti la cui
  riga non esiste piu'; la lettura delle sorgenti sta in una transazione read-only, le scritture sull'indice no. Gira in background
  (`ArchiveIndexScheduler`: all'avvio come backfill e ogni `app.search.reindex-interval`) e quando i dati indicizzati cambiano: `GenerationCompletedEvent`, `GenerationImageDeletedEvent`, `GenerationsDeletedEvent`,
  `GenerationFavouriteToggledEvent` (li ascolta `GenerationSearchSource` con `@TransactionalEventListener(fallbackExecution = true)`, cioe' DOPO il commit:
  la riconciliazione gira su un altro thread; chiama `IArchiveIndex#reindexAsync` solo se la ricerca e' attiva). Un documento che fallisce e'
  registrato (`ISystemEvents`) e non ferma gli altri. Una nuova fonte ricercabile = un nuovo `ISearchableSource` nel suo sottosistema.
- **`ArchiveSearchTool`** (`searchArchive(query, type?, tag?, favouritesOnly?, media?, since?, until?)`, in `chat.adapter.ai`, sopra `IArchiveSearch`) e' tra i tool di `SpringAiAssistant` solo se `app.search.enabled`; i parametri riempiono `DocumentFilter` (`since`/`until` ISO, `until` inclusivo); con query vuota e almeno un filtro elenca i piu' recenti (`IArchiveSearch#list`), senza filtri rifiuta.
- **Link alle pagine dell'app in chat**: i tool e il system prompt (sezione `appmap`) parlano di path assoluti (`/generations/12`, `/import/3`, `/gallery`, `/generations/new?kind=video`...). Dietro un reverse
  proxy su subpath non funzionerebbero, quindi `templates/app/deep-chat.html` li riscrive SOLO in visualizzazione (`linkAppPaths`, su
  `responseInterceptor` e sulla cronologia) in link markdown RELATIVI alla pagina corrente (`/deep-chat` -> `generations/12`,
  `/deep-chat/5` -> `../generations/12`), senza dipendere da `X-Forwarded-Prefix`. Elenco CHIUSO di path (`/generations[/new|/N]`, `/import[/N]`, `/gallery`, `/search`, `/loras`, `/tokens`, `/system/events`, `/manual[/slug][#ancora]`, con query opzionale; le ancore sono ASCII, vedi `HeadingSlugs`):
  un nuovo path che il bot deve poter citare va aggiunto li' e in `appmap`. Gli id diventano `#N`. Il testo salvato resta l'originale.
- `app.search.enabled=false` (i test, `application-test.yml`) spegne indice, scheduler, tool, servizi `IArchive*` ed `EmbeddingModel`
  (`spring.ai.model.embedding=none`): `mvn test` non scarica ne' carica mai il modello. I test usano un embedding finto (`FakeEmbeddingModel`).
  Prove reali, opt-in: `mvn test -Dtest='E5ModelSmokeTest,SemanticSearchWiringTest' -Dsemantic.model.test=true`.
- **UI `/search`** (`SemanticSearchController`, `templates/app/search.html` + `fragments/app/search.html`; link nell'header solo con `app.search.enabled`):
  **UN solo form** (testo, tipo, media immagini/video, solo preferiti, periodo dal/al, soglia; a card in stile Pines: riga di ricerca con lente, filtri in griglia, soglia
  come slider con valore live (Alpine) e "solo preferiti" come toggle `peer` su checkbox nativo `sr-only`, "Azzera filtri" = link a `/search`) e **UNA sola lista paginata** (`GET /search/results`, target `#search-results`,
  20 per pagina): con testo e' la classifica per significato (punteggi in %, TUTTA la classifica sopra la
  soglia: nessun top-K nella UI, `app.search.top-k` resta solo per il tool della chat), senza testo si sfogliano i documenti, i piu'
  recenti prima (per `createdAt`); in entrambi i casi filtrati per tipo e per periodo di creazione (date ISO, estremi inclusi, fuso del
  server). Niente liste "non filtrate" a parte: non reintrodurle, sembrerebbero il risultato della ricerca. La paginazione conserva i
  filtri perche' il controller passa `baseQuery` (query string gia' codificata) e il template `'/search/results?' + ${baseQuery}`
  (`pagination :: nav` accoda `&page=N`). Il form si re-invia da solo (`input`/`change` e `note-saved`: una nota creata o modificata
  ricarica la lista coi filtri correnti; `POST /search/notes` risponde con le sole statistiche fuori banda). Mostra
  dettagli/metadata/modello/hash, statistiche (`IndexStats`) e "Riconcilia ora". `createdAt` lo scrivono `ArchiveIndexService#reconcile` (da
  `getCreatedAt` della sorgente, backfill a costo zero: cambiano solo i metadata) e le note (alla creazione, conservato in modifica;
  la riconciliazione timbra con `refId` quelle vecchie). Nei test, i documenti finti di tipo derivato (`chat`/`generation`) possono essere
  cancellati dalla riconciliazione di fondo del contesto: per liste lunghe usare `type=note`.
  **Tag utente nell'indice**: metadata `tags` (lista, esatta, scritta da `GenerationSearchSource` = tag della generazione + dei suoi file, e da `ChatSearchSource` per le
  conversazioni; i messaggi di chat NON li ereditano) e NON nel testo embeddato (il filtro e' per tag esatto: un cambio di tag riscrive i soli metadata, nessun re-embedding). Unica eccezione: una conversazione senza titolo
  ma taggata ha per testo i soli tag (un testo vuoto verrebbe scartato dalla riconciliazione e il filtro non la troverebbe). `DocumentFilter#tag` → `Filter.Expression` EQ su `tags` (jsonpath lax: combacia con un ELEMENTO dell'array, verificato
  in `VectorIndexerTest`); suggerimenti `IArchiveSearch#tags()` ← `VectorDocumentRepository#tagCounts`. Il campo `tag` di `/search` sta in `baseQuery` (la paginazione lo conserva).
  **Solo le note manuali (`type=note`) sono creabili/modificabili/eliminabili** (`IArchiveNotes`, `ArchiveNoteService`): la riconciliazione non le crea ne' rimuove. I documenti
  derivati (generation/chat/conversation) sono in sola lettura (la fonte di verita' e' il DB, una modifica o cancellazione a mano
  verrebbe annullata al giro dopo): su di essi solo "Ri-embedda" (`IArchiveIndex#reembed`, anche dopo un cambio di modello). Un id non-nota su
  modifica/eliminazione => 422.
  "Nuova nota" e "Modifica" usano lo STESSO **dialog modale Pines** (`templates/app/search.html`, stessa meccanica del lightbox:
  `x-data="{ dialogOpen: false }"`, `x-trap.inert.noscroll`, senza teleport; bottoni `fragments/core/button :: dialogOpen|dialogClose|dialogCloseIcon`,
  che assumono `dialogOpen` su un antenato). `dialogOpen` con `hxGet` ricarica `#note-form` (`fragments/app/search :: noteForm`, vuoto per
  `GET /search/notes/new`, precompilato per `/search/notes/{id}/edit`) a ogni apertura. Al salvataggio riuscito il server emette
  `HX-Trigger: note-saved` (`HtmxEvents#addHxTrigger`) che chiude il dialog (risposta: solo le statistiche in creazione, riga `#doc-...`
  in modifica); con un errore di validazione risponde col form (`HX-Retarget: #note-form`) e il dialog resta aperto. Le statistiche
  si aggiornano fuori banda (`hx-swap-oob`).
  La ricerca ha una **soglia di somiglianza minima** in % (`threshold`, 0..100, default `app.search.similarity-threshold-percent`=80; solo con testo, la navigazione senza testo non filtra per punteggio):
  i punteggi e5 sono compressi (tipicamente 70-90%), quindi la soglia utile e' alta.
  Test del controller con embedding finto: `SemanticSearchControllerTest`.
- Fuori scope per ora: ricerca semantica nelle liste/galleria esistenti, descrizione PERSISTENTE delle immagini GENERATE con un modello di visione (le importate la hanno: vedi "Immagini esterne (import)"; la chat puo' guardarne una su richiesta con `VisionTool`, senza salvare nulla), allegati in `/deep-chat`.

## Backup e restore (`export` / `import` del jar)

Lo stesso jar del server e' lo strumento di backup: `java -jar app.jar export <file> [--no-encrypt]` e `java -jar app.jar import <file> [--replace]` (esito 0 = ok,
1 = errore, 2 = uso errato). Esagono `core.backup` (generico, non conosce le tabelle dell'app: le scopre da `information_schema`); testi della console in `messages-core*`
(`backup.*`).

- **Come parte**: `Application.main` vede `export|import` come primo argomento e avvia il profilo `backup` (`application-backup.yml`) con `WebApplicationType.NONE`; un
  `ApplicationRunner` esegue il comando e ESCE (`System.exit(SpringApplication.exit(...))` dentro il runner: i runner girano PRIMA di `ApplicationReadyEvent`, quindi i listener
  "all'avvio" non partono). Il profilo spegne `app.recovery`, `app.search` (niente modello ONNX), il controllo dei token, la migrazione WebDAV e la cache dei blob, e imposta
  `spring.jpa.hibernate.ddl-auto=none` + `spring.flyway.enabled=false` (`validate` e la migrazione automatica romperebbero un import su DB vuoto o piu' vecchio). Un profilo batte
  `application.yml`, dei default programmatici NO: per questo le sovrascritture stanno li'. `BackupProfileContextTest` verifica che nessun bean abbia `@Scheduled` o ascolti
  `ApplicationReadyEvent` e che il contesto parta con i token Replicate/OpenRouter VUOTI (un job aggiunto in futuro lo fa fallire invece di partire di nascosto durante un backup).
- **Formato**: uno zip scritto in streaming. Voci, in quest'ordine: `manifest.json` (versione del formato, versione dello schema Flyway, tabelle con colonne in ordine di caricamento),
  `blobs/<file>` (IN CHIARO), `db/<tabella>.copy` (formato testo di `COPY`), `summary.json` (righe per tabella, file, file mancanti). DEFLATED sempre (blob a livello 0: `ZipInputStream`
  non legge le voci STORED senza dimensioni). Cifrato, e' l'INTERO zip a passare dal formato `DFX1` di `ChunkedAesGcmCipher` (lo stesso dei binari WebDAV e dei token; `encryptingStream`
  e' la versione "a spinta" di `encrypt`): ne' contenuto ne' nomi delle voci si vedono, ogni chunk e' autenticato e un file troncato non si autentica. Cifrato o no lo si riconosce dai
  primi byte (`DFX1` o `PK`): l'import non ha un flag.
- **DB**: `COPY ... TO/FROM STDIN` in formato testo con il driver JDBC (`org.postgresql.copy.CopyManager`, per questo il driver non e' piu' `runtime`), non `pg_dump` (non e' nel jar e vuole un
  client della stessa versione del server). Il testo regge `vector`, `bytea`, `json` e `timestamptz`. Si esportano TUTTE le tabelle tranne `flyway_schema_history` (che le migrazioni
  ricreano), `vector_store` compresa: **le note manuali (`type=note`) vivono SOLO li** e nessuna riconciliazione le ricrea. L'export gira in UNA transazione REPEATABLE READ di sola lettura
  (snapshot coerente, nessuna scrittura sulla sorgente: server acceso possibile, ma file creati o cancellati nel frattempo possono mancare; consigliato fermarlo). L'ordine di caricamento e'
  topologico sulle FK (`TableOrder`); le FK su se' stessa (`generation.source_generation_id`) non contano: i controlli non differibili scattano a fine statement.
- **Quali binari**: quelli REFERENZIATI dal DB, non un elenco del backend (WebDAV non ne ha uno praticabile). La SPI `IBlobReferences` (`core.backup.port.in`, come `ISearchableSource`: NON e'
  una `port.out` del core, quindi `CORE_EXTENSION_POINTS` non cambia) dichiara coppie (tabella, colonna); la implementa `GenerationBlobReferences` (`generation_image.filename`,
  `generation.source_upload_filename`, `generation.mask_upload_filename`) e `BackupBlobColumnsTest` fa fallire una colonna `%filename%` nuova non dichiarata ne' nota come derivata. Un file
  referenziato ma assente e' un avviso nel summary, non un errore. Si leggono/scrivono da `IImageStorageService` (`openRange` / il nuovo `restore(filename, stream)`: nome deciso da chi chiama,
  nessun controllo da upload), quindi l'archivio e' indipendente dal backend: si esporta da WebDAV e si importa in locale o su un altro WebDAV (con un'altra chiave dello storage).
- **Import**: (1) controlli in sola lettura: formato supportato, versione dello schema nota al jar (altrimenti "serve un jar piu' recente"), DB vergine (nessuna `flyway_schema_history`)
  oppure `--replace` (`DROP SCHEMA ... CASCADE` + `CREATE SCHEMA`: cancella i dati attuali); (2) **binari prima**: un file gia' presente si salta (nomi casuali + scrittura atomica: presente =
  completo), quindi un errore qui lascia il DB intatto e l'import si rilancia; (3) **DB per ultimo**, in una transazione: Flyway `migrate` con `target` = versione del backup, `TRUNCATE ... RESTART
  IDENTITY CASCADE` di tutte le tabelle (le migrazioni seminano `replicate_model`), `COPY` in ordine FK, `setval` di OGNI colonna identity al massimo caricato (tutte `GENERATED BY DEFAULT`:
  senza, il primo insert dopo il ripristino collide), verifica delle righe contro il summary, commit; (4) Flyway `migrate` all'ultima versione, cosi' un backup piu' vecchio entra in un jar
  piu' nuovo (uno piu' nuovo del jar si rifiuta). Se il caricamento fallisce il DB resta migrato ma vuoto: si rilancia con `--replace` (il messaggio lo dice).
- **Credenziali** (la parte non ovvia):
  - Servono SOLO quelle di cio' che il comando tocca: `DB_*` (sorgente per l'export, destinazione per l'import) e, se `storage.type=webdav`, `STORAGE_WEBDAV_URL/USERNAME/PASSWORD`. Si leggono
    dal `.env` della working directory (`spring.config.import: optional:file:.env[.properties]`: lanciare il jar da li' o usare variabili d'ambiente) come per il server.
  - **Mai necessarie ne' usate**: `REPLICATE_API_TOKEN`, `OPENROUTER_*`, `SEARXNG_*`. Non stanno nel DB, quindi non stanno nel backup; il comando parte anche con tutte vuote.
  - **La chiave**: `backup.encryption-key` (`core.yml`) = `${BACKUP_ENCRYPTION_KEY:${storage.webdav.encryption-key:}}`, cioe' per default la STESSA chiave dello storage (nessun segreto nuovo; `BACKUP_ENCRYPTION_KEY` la
    separa). Un export senza chiave valida RIFIUTA di partire (mai un backup in chiaro per omissione: serve `--no-encrypt`); l'import cifrato senza la chiave giusta fallisce all'autenticazione
    del primo chunk, prima di toccare DB o storage. La chiave NON e' mai nel backup: va conservata a parte (come gia' per i binari WebDAV).
  - **Token API**: `api_token.token_encrypted` si copia com'e', cifrato con la chiave dello STORAGE (non con quella dell'archivio). Se sul sistema di destinazione la chiave e' un'altra non si apre:
    a fine import `IApiTokens#undecryptableCount` lo conta e si registra un AVVISO (`ISystemEvents#warn`, source TOKENS, nella campanella) piu' una riga in console; il ripristino non si ferma e i
    token si reinseriscono in `/tokens`. Ri-cifrare i token con un'altra chiave e' fuori scope.
- **Fuori scope**: backup incrementale/pianificato; `verify` dell'archivio senza importarlo; esportare `data/cache` e `data/models` (derivati); salvare `.env`; unire a un DB gia' popolato.
- **Test**: `BackupRoundTripTest` (due database dedicati sul container dei test, come `FlywayCoreAppMigrationTest`; confronta ogni tabella e i byte dei blob, verifica le sequenze, `--replace`, schema piu'
  vecchio, chiave sbagliata, rollback, archivio troncato), `BackupRunnerTest`, `TableOrderTest`, `ChunkedAesGcmCipherTest` (stream). Mai chiamate vere a Replicate.

## Manuale online (`core.manual`)

Manuale in Markdown, parte **Uso** (per l'utente) e parte **Architettura** (per chi sviluppa/gestisce), servito da `/manual` e convertito in HTML a ogni richiesta; il bot di `/deep-chat` lo consulta con `ManualTool`.
Il motore e' `core.manual` (generico: si tiene col template); i testi e le etichette dei gruppi sono dell'app (si sostituiscono).

- **File**: `src/main/resources/manual/<lingua>/<NN-gruppo>/<NN-pagina>.md` (oggi `it/01-uso/*` e `it/02-architettura/*`). Ordine = prefisso numerico a due cifre (cartella e file); **slug = nome del file senza prefisso, unico fra tutti i gruppi**
  (URL piatta `/manual/{slug}`; un doppione e' `ManualException` all'indice e fa fallire `ManualContentTest`); gruppo = cartella senza prefisso, etichetta dalla chiave `manual.group.<gruppo>` del bundle DELL'APP (senza: il nome della cartella).
  Titolo = primo `# `, riassunto dell'indice = primo paragrafo. Una lingua senza cartella ricade su `it` (`ClasspathManualSource`): `manual/en/` si aggiunge senza codice. Il contenuto e' in italiano con gli accenti veri (a differenza dei bundle, che usano `e'`).
- **Slug da richiesta**: `IManual#page` cerca lo slug SOLO nella mappa dell'indice, mai costruisce un percorso (nessun path traversal); uno slug ignoto e' un 404 (`ResponseStatusException`, nessun evento di sistema).
- **Indice e sezioni**: `ManualService` costruisce l'indice (titoli, riassunti, sezioni) una volta per lingua (i testi stanno nel jar; devtools riavvia al cambio). Una **sezione** va da un titolo `#`/`##` al successivo (i `###` restano nella loro `##`):
  e' l'unita' che si cerca (`IManual#search`: termini senza accenti, privati della vocale finale cosi' "immagini" trova "immagine", pesi titolo-sezione 6 / titolo-pagina 3 / corpo fino a 5 occorrenze, stopword it/en) e che il bot cita.
- **Ancore**: UNA sola funzione, `HeadingSlugs` (minuscolo, senza diacritici, non alfanumerici -> `-`, duplicati `-1`, `-2`), usata per gli `id` dell'HTML E per le sezioni: se divergessero il bot citerebbe ancore morte.
  Per questo i titoli non possono avere formattazione inline (lo verifica `ManualContentTest`).
- **Link nei `.md`** (regola unica in `ManualLinks`, usata dal renderer e dai test): fra pagine il nome VERO del file, `../01-uso/03-genera-immagini.md#parametri` (funziona anche su GitHub/IDE); verso l'app un path radice, `/gallery`;
  `#ancora` nella pagina; `http(s)://` esterno (si apre in una scheda nuova). Il renderer li riscrive: pagina -> `{prefisso}/manual/{slug}#ancora`, path dell'app -> `{prefisso}{path}`, dove prefisso = `request.getContextPath()` (l'HTML renderizzato non passa da `@{...}`:
  vedi "Convenzione: attributi che portano un URL"). Un link senza forma riconoscibile resta com'e' e il test lo segnala.
- **Sicurezza**: contenuto del repo, ma `CommonmarkRenderer` ha `escapeHtml(true)` (l'HTML grezzo si vede come testo) e `sanitizeUrls(true)`; `manual.html` usa `th:utext` solo sui due `<div>` dell'articolo (titolo e corpo).
- **Stile**: preflight azzera titoli/liste/tabelle, e il theming vieta nuove regole `@layer` e il plugin Typography (colori propri): `templates/core/manual.html` mette varianti arbitrarie Tailwind (`[&_h2]:...`, `dark:[&_th]:...`) coi token del tema, definite UNA volta (`th:with="prose=..."`) e applicate ai due `<div>`.
  Le classi restano stringhe letterali (la CLI Tailwind le scansiona). Le tabelle larghe scorrono nel proprio riquadro (`overflow-x-auto`).
- **Pagina**: `ManualController` (`GET /manual` indice per gruppi, `GET /manual/{slug}`), `templates/core/manual.html` + `fragments/core/manual.html :: toc` (usato due volte: barra laterale da `md`, riquadro `<details>` sotto), "In questa pagina" (h2/h3, solo con piu' di una h2; sta DOPO il titolo: il controller spezza l'HTML al primo `</h1>` in `pageHead`/`pageBody`),
  Precedente/Successivo. Breadcrumbs `trail(group=null, ...)`: Home › Manuale › pagina (il manuale e' un link diretto del menu, non sta in un gruppo). Chiavi `manual.*` (chrome) in `messages-core(.en)`; `header.nav.manual`, `index.link.manual.suffix`
  e `manual.group.*` in `messages(.en)`. Dentro un'espressione `${...}` un parametro di fragment non puo' usare `${a} ? x : y` (errore di parsing): una sola `${a ? x : y}`.
- **Chat**: `ManualTool` (vedi "Struttura del progetto", `app.chat`), sezione `deep-chat.section.manual` (~450 caratteri) e `/manual` in `appmap`; il percorso `/manual/<slug>#<ancora>` e' nell'elenco chiuso di `linkAppPaths`. Il bot cerca/legge solo il gruppo `uso`.
- **Test**: engine in `core/manual/**` (`ManualServiceTest`, `CommonmarkRendererTest`, `HeadingSlugsTest`, `ManualLinksTest`: dati finti, nessun testo dell'app); contenuto e pagina dell'app in `app/manual/` (`ManualContentTest`: slug unici, un solo `#` per pagina,
  nessuna formattazione nei titoli, nessuna sezione oltre 6.000 caratteri, ogni link a una pagina e ogni ancora esistono, nessun `localhost`/numero di porta; `ManualControllerTest`: indice, pagina, 404 senza evento, prefisso `X-Forwarded-Prefix`,
  ogni path dell'app citato e' una rotta vera — `/search` si salta con la ricerca spenta, e' l'unica rotta facoltativa) e `chat/adapter/ai/ManualToolTest`. Al `TemplateRenderingTests` si aggiunge `/manual` nelle breadcrumbs.
- **Dipendenza**: `commonmark` + `commonmark-ext-gfm-tables` 0.24 (`<commonmark.version>` nel `pom.xml`). Con Maven 3.8.1 su JDK 24 il mirror aziendale puo' fallire il TLS ("No appropriate protocol"): `-Dhttps.protocols=TLSv1.2,TLSv1.3`.
- **Fuori scope per ora**: ricerca nel manuale dalla pagina web (la fa il bot), un secondo livello di indice, immagini/screenshot, link "Guida" contestuali sulle pagine, il manuale in inglese (la cartella `en/` e' prevista, mancano i testi).

## Comandi utili

Nessun Maven Wrapper (serve Maven installato; `mvn wrapper:wrapper` per generarlo).

```bash
docker compose up -d          # PostgreSQL+pgvector di sviluppo (DB_USERNAME/DB_PASSWORD nel .env, vedi .env.example)
mvn spring-boot:run          # sviluppo (Thymeleaf cache=false)
mvn test                     # test (include ArchitectureTest)
mvn test -Dtest=ArchitectureTest   # solo le regole di architettura (le altre richiedono Docker)
mvn clean package            # jar eseguibile (Tailwind via Play CDN)
mvn -Ptailwind clean package # + CSS Tailwind compilato/minificato (richiede rete per il binario)
java -jar target/spring-htmx-starter-*.jar export backup.dfb   # backup cifrato di DB + binari (vedi "Backup e restore"); import <file> [--replace] per ripristinare
```

`mvn test` richiede **Docker** e non tocca mai il DB di sviluppo: `PostgresTestContainerInitializer` (`support`, registrato in
`src/test/resources/META-INF/spring.factories`, quindi valido per ogni `@SpringBootTest` senza annotazioni) avvia UN container
`pgvector/pgvector:pg17` per tutta la suite e ne imposta il datasource; Flyway applica lo schema reale (core+app). Il profilo "test" di Surefire
(`<systemPropertyVariables>` in `pom.xml`) resta per il resto della config (`application-test.yml`: recupero, ricerca e controllo scadenze token spenti). I test condividono il DB: i
`@SpringBootTest` che scrivono ripuliscono a mano o sono `@Transactional`. I test stanno sotto `src/test/java` con la stessa struttura dei package
(`core/<s>/...`, `app/<s>/...`); `TemplateRenderingTests` (rendering di tutte le pagine/fragment, bundle) resta in `controller`.
Stesso principio per lo storage: `spring.config.import` carica il `.env` reale anche sotto Surefire, quindi `pom.xml` fissa
come proprieta' di sistema `storage.type=local` e `storage.migration.from-local.enabled=false` (battono qualunque file);
un test che vuole WebDAV o la migrazione li sovrascrive con `@SpringBootTest(properties=...)`, mai contro il server vero.

## Checklist per una nuova pagina/feature

1. Solo navigazione → nuovo controller in `adapter/in/web` del sottosistema giusto (che parla solo con le porte `in`) + template `templates/app/<pagina>.html`
   (o `templates/core/` se generico) col pattern layout manager; la voce di menu in `fragments/app/nav.html` e le breadcrumbs
   (`layout:fragment="breadcrumbs"` con `fragments/core/breadcrumbs :: trail(...)`, vedi "Struttura del progetto").
2. Aggiornamento parziale (ricerca live, paginazione, form senza reload) → estrarre un fragment in
   `fragments/app/<nome>.html` (o `fragments/core/`); il controller lo restituisce se `HX-Request`, la pagina intera altrimenti.
3. Solo interattivita' locale → Alpine (`x-data`/`x-show`/`x-on`) nel template, senza controller.
4. Componente complesso stateful → valutare prima un Web Component isolato.
5. Tocca un'entity JPA → nuova migrazione Flyway a timestamp nella location giusta (`db/migration/core` o `db/migration/app`), mai `ddl-auto`; nessuna FK core → app.
6. Testo utente-visibile → chiave nel bundle giusto (`messages*` per l'app, `messages-core*` per il core) in entrambe le lingue, mai stringa hardcoded
   (template o eccezione); le chiavi dei due bundle restano disgiunte.
7. Serve un `<button>` → fragment di `fragments/core/button.html` (o `fragments/app/button-gen.html` per le azioni dell'app; aggiungerne uno se nessuno calza), mai inline.
8. Serve una `<select>` → wrapper `pinesSelect` (vedi "Convenzione: select (Pines)"), mai nuda.
9. Un evento che l'utente deve notare → `ISystemEvents#warn` (avviso) o `#record` (errore): finisce in `/system/events`, nel toast e nella campanella.
10. Nuovo sottosistema/feature, nuova porta, nuova dipendenza fra sottosistemi → vedi "Architettura" e la sua checklist; `ArchitectureTest` deve restare verde.
11. Cambia cio' che l'utente vede o fa (pagina, bottone, modello, messaggio d'errore, limite) → aggiornare la pagina del manuale che lo descrive (`src/main/resources/manual/it/01-uso/`, o `02-architettura/` se cambia la struttura): `ManualContentTest` blocca solo i link morti, non il testo diventato vecchio. Le etichette dell'interfaccia nel manuale sono quelle dei bundle, con gli accenti veri (`Intensita'` -> Intensità), e per icone e pulsanti il titolo che l'utente vede (`Anima in un video`, non `Anima`): si controlla nel template, non c'e' un test.
