package org.dual.replicate.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L'unico esecutore di chiamate remote: traduce qualunque eccezione in una {@link RemoteServiceException} e ritenta
 * (con {@link RetryPolicy}) solo quelle {@link RemoteServiceException#isTransient() transitorie}. Non registra nulla:
 * chi GESTISCE l'errore lo registra ({@code AppErrorService#record}), cosi' un ritentativo riuscito non lascia tracce e
 * un errore definitivo ne lascia una sola.
 *
 * <p>Oggetto semplice (nessun bean), costruito una volta per client:
 * <pre>{@code
 * this.remote = RemoteCaller.builder(translator).retry(RetryPolicy.DEFAULT).build();
 * ... remote.call("getThing", () -> restClient.get()...body(Thing.class));
 * remote.call("createThing", RetryPolicy.NONE, () -> ...);   // non idempotente
 * }</pre>
 * Eccezioni "di controllo" che NON sono errori (es. {@code NoSuchFileException} = "non esiste") si dichiarano con
 * {@link Builder#passThrough}: sono rilanciate cosi' come sono (anche se RestClient le ha incapsulate in una
 * {@code ResourceAccessException}), senza traduzione ne' retry.
 */
public final class RemoteCaller {

    private static final Logger log = LoggerFactory.getLogger(RemoteCaller.class);

    private final Function<Throwable, RemoteServiceException> translator;
    private final RetryPolicy defaultPolicy;
    private final List<Class<? extends Throwable>> passThrough;

    private RemoteCaller(Builder builder) {
        this.translator = builder.translator;
        this.defaultPolicy = builder.policy;
        this.passThrough = List.copyOf(builder.passThrough);
    }

    public static Builder builder(Function<Throwable, RemoteServiceException> translator) {
        return new Builder(translator);
    }

    public <T> T call(String operation, ThrowingSupplier<T> call) {
        return call(operation, defaultPolicy, call);
    }

    public <T> T call(String operation, RetryPolicy policy, ThrowingSupplier<T> call) {
        int attempt = 0;
        while (true) {
            RemoteServiceException failure;
            try {
                return call.get();
            } catch (Exception e) {
                Throwable control = controlException(e);
                if (control != null) {
                    throw RemoteCaller.<RuntimeException>sneaky(control);
                }
                // Gia' classificata (anche se RestClient l'ha incapsulata in una ResourceAccessException uscendo da un
                // exchange): si tiene la classificazione, mai la si ritraduce come "rete" (e quindi ritentabile).
                RemoteServiceException classified = causeOfType(e, RemoteServiceException.class);
                failure = classified != null ? classified : translator.apply(e);
            }
            if (!failure.isTransient() || attempt >= policy.retries()) {
                throw failure;
            }
            attempt++;
            log.debug("Chiamata remota {} fallita (tentativo {}/{}): {}, ritento", operation, attempt, policy.retries() + 1,
                    failure.getMessage());
            try {
                Thread.sleep(policy.backoff().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure;
            }
        }
    }

    /** L'eccezione di controllo cercata lungo la catena delle cause (RestClient incapsula le IOException), o null. */
    private Throwable controlException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (Class<? extends Throwable> type : passThrough) {
                if (type.isInstance(t)) {
                    return t;
                }
            }
        }
        return null;
    }

    private static <T extends Throwable> T causeOfType(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable t) throws E {
        throw (E) t;
    }

    public static final class Builder {

        private final Function<Throwable, RemoteServiceException> translator;
        private RetryPolicy policy = RetryPolicy.DEFAULT;
        private final List<Class<? extends Throwable>> passThrough = new ArrayList<>();

        private Builder(Function<Throwable, RemoteServiceException> translator) {
            this.translator = translator;
        }

        /** Policy delle chiamate che non ne indicano una. */
        public Builder retry(RetryPolicy policy) {
            this.policy = policy;
            return this;
        }

        @SafeVarargs
        public final Builder passThrough(Class<? extends Throwable>... types) {
            passThrough.addAll(List.of(types));
            return this;
        }

        public RemoteCaller build() {
            return new RemoteCaller(this);
        }
    }
}
