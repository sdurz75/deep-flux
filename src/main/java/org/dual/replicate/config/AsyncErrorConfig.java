package org.dual.replicate.config;

import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.service.SystemEventService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * Le eccezioni che escono da un metodo {@code @Async void} (DeepChatGenerationWatcher#watch) altrimenti
 * finiscono solo nel log di default di Spring: qui passano dal registro errori (tabella + toast).
 * L'executor resta quello di default di Spring Boot (getAsyncExecutor non sovrascritto).
 */
@Configuration
public class AsyncErrorConfig implements AsyncConfigurer {

    private final SystemEventService systemEvents;

    public AsyncErrorConfig(@org.springframework.context.annotation.Lazy SystemEventService systemEvents) {
        this.systemEvents = systemEvents;
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                systemEvents.record(CoreEventSource.INTERNAL, "async:" + method.getName(), throwable);
    }
}
