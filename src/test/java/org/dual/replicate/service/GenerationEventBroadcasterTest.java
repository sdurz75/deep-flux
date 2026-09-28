package org.dual.replicate.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.dual.replicate.domain.Generation;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import reactor.core.publisher.BaseSubscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ReactiveTypeHandler di Spring MVC (il consumatore reale di
 * GenerationEventBroadcaster.subscribe(), vedi EventStreamController)
 * chiede un elemento alla volta (request(1), poi ne richiede un altro
 * solo dopo aver spedito il precedente): un BaseSubscriber che fa lo
 * stesso riproduce esattamente quel pattern di domanda, senza bisogno
 * di reactor-test.
 */
class GenerationEventBroadcasterTest {

    @Test
    void bufferPerSottoscrittoreConsegnaEntrambiGliEventiAncheConDomandaLimitata() {
        GenerationEventBroadcaster broadcaster = new GenerationEventBroadcaster();
        List<String> receivedEventNames = new CopyOnWriteArrayList<>();
        AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();

        broadcaster.subscribe().subscribe(new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                subscriptionRef.set(subscription);
                subscription.request(1);
            }

            @Override
            protected void hookOnNext(org.springframework.http.codec.ServerSentEvent<Object> value) {
                receivedEventNames.add(value.event());
                // Nessuna request(1) qui: simula la scrittura ancora in
                // volo quando arriva il secondo evento, esattamente il
                // punto che "directBestEffort" da solo (senza il buffer
                // per-sottoscrittore in subscribe()) perderebbe.
            }
        });

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        broadcaster.onGenerationCompleted(new GenerationCompletedEvent(generation));
        broadcaster.broadcastChatMessage(new GenerationEventBroadcaster.ChatMessagePush(1L, "Immagine pronta", null));

        assertThat(receivedEventNames).containsExactly("gallery-update");

        subscriptionRef.get().request(1);

        assertThat(receivedEventNames).containsExactly("gallery-update", "chat-message");
    }

    @Test
    void emitSenzaSottoscrittoriNonLanciaEccezioni() {
        GenerationEventBroadcaster broadcaster = new GenerationEventBroadcaster();

        assertThatCode(() -> broadcaster.broadcastChatMessage(
                new GenerationEventBroadcaster.ChatMessagePush(1L, "nessuno ascolta", null)))
                .doesNotThrowAnyException();
    }
}
