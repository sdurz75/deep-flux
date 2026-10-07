package org.dual.hexa.core.events.adapter.in.async;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * Le eccezioni che escono da un metodo {@code @Async void} (ChatGenerationWatcher#watch) altrimenti
 * finiscono solo nel log di default di Spring: qui passano dal registro errori (tabella + toast).
 * L'executor resta quello di default di Spring Boot (getAsyncExecutor non sovrascritto).
 */
@Configuration
public class AsyncErrorConfig implements AsyncConfigurer {

    private final ISystemEvents systemEvents;

    public AsyncErrorConfig(@org.springframework.context.annotation.Lazy ISystemEvents systemEvents) {
        this.systemEvents = systemEvents;
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                systemEvents.record(CoreEventSource.INTERNAL, "async:" + method.getName(), throwable);
    }
}
