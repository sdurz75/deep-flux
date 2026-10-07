package org.hexa.core.push.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.hexa.core.push.domain.PushMessage;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import reactor.core.publisher.BaseSubscriber;

/**
 * ReactiveTypeHandler di Spring MVC (il consumatore reale di {@code subscribe()}, vedi EventStreamController) chiede un
 * elemento alla volta (request(1), poi ne richiede un altro solo dopo aver spedito il precedente): un BaseSubscriber che fa lo
 * stesso riproduce esattamente quel pattern di domanda, senza bisogno di reactor-test.
 */
class PushServiceTest {

    @Test
    void bufferPerSottoscrittoreConsegnaEntrambiGliEventiAncheConDomandaLimitata() {
        PushService push = new PushService();
        List<String> received = new CopyOnWriteArrayList<>();
        AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();

        push.subscribe().subscribe(new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                subscriptionRef.set(subscription);
                subscription.request(1);
            }

            @Override
            protected void hookOnNext(PushMessage value) {
                received.add(value.event());
                // Nessuna request(1) qui: simula la scrittura ancora in volo quando arriva il secondo evento, esattamente
                // il punto che "directBestEffort" da solo (senza il buffer per-sottoscrittore in subscribe()) perderebbe.
            }
        });

        push.emit("gallery-update", "refresh");
        push.emit("chat-message", "payload");

        assertThat(received).containsExactly("gallery-update");

        subscriptionRef.get().request(1);

        assertThat(received).containsExactly("gallery-update", "chat-message");
    }

    @Test
    void emitSenzaSottoscrittoriNonLanciaEccezioni() {
        assertThatCode(() -> new PushService().emit("chat-message", "nessuno ascolta")).doesNotThrowAnyException();
    }

    @Test
    void ogniSottoscrittoreRiceveIlPayloadDelMessaggio() {
        PushService push = new PushService();
        List<PushMessage> first = new CopyOnWriteArrayList<>();
        List<PushMessage> second = new CopyOnWriteArrayList<>();
        push.subscribe().subscribe(first::add);
        push.subscribe().subscribe(second::add);

        push.emit("x", 42);

        assertThat(first).containsExactly(new PushMessage("x", 42));
        assertThat(second).containsExactly(new PushMessage("x", 42));
    }
}
