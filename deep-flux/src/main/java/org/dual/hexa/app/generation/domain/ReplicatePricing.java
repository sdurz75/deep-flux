package org.dual.hexa.app.generation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Stima del costo (USD) di una prediction completata, dai suoi
 * {@code metrics}: l'API Replicate non espone il prezzo, solo queste
 * metriche (predict_time piu' campi specifici del modello). Una regola per
 * modello censito (i fine-tune LoRA ne hanno una per form-type), prezzi in un solo posto: se Replicate li cambia, si
 * aggiornano qui (le generazioni gia' completate conservano il costo
 * calcolato al momento, vedi GENERATION.COST_USD, V13).
 *
 * Funzione pura e statica, senza bean, di proposito: GenerationService non
 * ha bisogno di una dipendenza in piu'. Il risultato e' vuoto (mai un
 * numero inventato) per un modello sconosciuto o se manca una metrica.
 * Provenienza dei prezzi (2026-09-29): p-video dal README del modello;
 * kontext-dev ($0.025 per output image) dalla pagina del modello; krea-dev e klein-9b forniti dall'utente, trattati come "per immagine"
 * (per klein potrebbe essere a megapixel: identico a 1 MP, l'output di
 * default); i fine-tune LoRA addestrati su Replicate ({@link GenerationFormType#FLUX_LORA_FINETUNE}, es. flux-lora-ff3) a tempo
 * di calcolo, tariffa H100 da replicate.com/pricing (ipotesi: l'hardware e' per modello, quindi un altro fine-tune su una GPU diversa
 * va dato come regola esplicita per modello, che ha la precedenza sul form-type); flux-dev-lora (2026-10-01) STIMATO uguale a flux-dev
 * ($0.025 per immagine, scelta dell'utente: la pagina del modello non riporta il prezzo) e la metrica
 * image_output_count non e' stata verificata su quel modello. flux-fill-dev (2026-10-02): $0.025 per immagine dalla
 * pagina del modello, metrica image_output_count idem non verificata.
 * flux-fill-pro (2026-10-02): $0.05 per immagine (prezzo BFL/Replicate indicato per 1 MP, non verificato per risoluzioni maggiori), una prediction = un'immagine.
 */
public final class ReplicatePricing {

    static final BigDecimal KREA_DEV_PER_IMAGE = new BigDecimal("0.06");
    static final BigDecimal KLEIN_9B_PER_IMAGE = new BigDecimal("0.02");
    static final BigDecimal KONTEXT_DEV_PER_IMAGE = new BigDecimal("0.025");
    static final BigDecimal DEV_LORA_PER_IMAGE = new BigDecimal("0.025");
    static final BigDecimal FILL_DEV_PER_IMAGE = new BigDecimal("0.025");
    static final BigDecimal FILL_PRO_PER_IMAGE = new BigDecimal("0.05");
    static final BigDecimal H100_PER_SECOND = new BigDecimal("0.001525");

    private static final BigDecimal P_VIDEO_DRAFT_720P = new BigDecimal("0.005");
    private static final BigDecimal P_VIDEO_DRAFT_1080P = new BigDecimal("0.01");
    private static final BigDecimal P_VIDEO_STANDARD_720P = new BigDecimal("0.02");
    private static final BigDecimal P_VIDEO_STANDARD_1080P = new BigDecimal("0.04");

    private ReplicatePricing() {
    }

    public static Optional<BigDecimal> estimate(String model, Map<String, Object> metrics) {
        return estimate(model, null, metrics);
    }

    /**
     * Come {@link #estimate(String, Map)}, con il form-type del modello: la regola esplicita per modello vince, in mancanza decide il
     * form-type (un fine-tune LoRA nuovo e' stimato senza codice). {@code formType} puo' essere null.
     */
    public static Optional<BigDecimal> estimate(String model, GenerationFormType formType, Map<String, Object> metrics) {
        if (model == null || metrics == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> cost = switch (model) {
            case "black-forest-labs/flux-krea-dev" ->
                    number(metrics, "image_output_count").map(n -> n.multiply(KREA_DEV_PER_IMAGE));
            case "black-forest-labs/flux-2-klein-9b" ->
                    number(metrics, "image_output_count").map(n -> n.multiply(KLEIN_9B_PER_IMAGE));
            case "black-forest-labs/flux-kontext-dev" ->
                    number(metrics, "image_output_count").map(n -> n.multiply(KONTEXT_DEV_PER_IMAGE));
            case "black-forest-labs/flux-dev-lora" ->
                    number(metrics, "image_output_count").map(n -> n.multiply(DEV_LORA_PER_IMAGE));
            case "black-forest-labs/flux-fill-dev" ->
                    number(metrics, "image_output_count").map(n -> n.multiply(FILL_DEV_PER_IMAGE));
            // Una prediction = un'immagine: nessuna metrica da leggere (image_output_count non e' verificata su questo modello).
            case "black-forest-labs/flux-fill-pro" -> Optional.of(FILL_PRO_PER_IMAGE);
            case "prunaai/p-video" -> pVideo(metrics);
            default -> formType == GenerationFormType.FLUX_LORA_FINETUNE
                    ? number(metrics, "predict_time").map(n -> n.multiply(H100_PER_SECOND))
                    : Optional.empty();
        };
        return cost.map(c -> c.setScale(6, RoundingMode.HALF_UP));
    }

    private static Optional<BigDecimal> pVideo(Map<String, Object> metrics) {
        boolean draft = "draft".equals(metrics.get("model_variant"));
        boolean hd = "1080p".equals(metrics.get("resolution_target"));
        BigDecimal perSecond = draft
                ? (hd ? P_VIDEO_DRAFT_1080P : P_VIDEO_DRAFT_720P)
                : (hd ? P_VIDEO_STANDARD_1080P : P_VIDEO_STANDARD_720P);
        return number(metrics, "video_output_duration_seconds").map(seconds -> seconds.multiply(perSecond));
    }

    private static Optional<BigDecimal> number(Map<String, Object> metrics, String key) {
        return metrics.get(key) instanceof Number n
                ? Optional.of(new BigDecimal(n.toString()))
                : Optional.empty();
    }
}
