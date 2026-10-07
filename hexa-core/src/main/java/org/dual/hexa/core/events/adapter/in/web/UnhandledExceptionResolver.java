package org.dual.hexa.core.events.adapter.in.web;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.web.HtmxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Ultima rete per le eccezioni che nessun catch del controller ne' i resolver standard di Spring MVC
 * (ExceptionHandler, ResponseStatus, DefaultHandlerExceptionResolver: 404, 400, 405...) hanno gestito: prima
 * finivano in un 500 senza traccia utile, che htmx non renderizza (l'utente non vedeva nulla). Ora sono
 * registrate (ISystemEvents) e, per una richiesta htmx, accompagnate dall'header HX-Trigger del toast.
 * <p>
 * Ordine LOWEST_PRECEDENCE: vede solo cio' che gli altri resolver hanno lasciato passare. Un resolver e non
 * un {@code @ControllerAdvice(Exception)} proprio per non intercettare (e trasformare) gli errori "normali"
 * del framework. Le disconnessioni del client (chiusura di una tab su GET /events, broken pipe) NON sono
 * errori dell'app: ignorate.
 */
@Component
public class UnhandledExceptionResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UnhandledExceptionResolver.class);

    private final ISystemEvents systemEvents;
    private final HtmxEvents htmx;

    public UnhandledExceptionResolver(ISystemEvents systemEvents, HtmxEvents htmx) {
        this.systemEvents = systemEvents;
        this.htmx = htmx;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (isClientDisconnect(ex)) {
            return null;
        }
        boolean isHtmx = "true".equalsIgnoreCase(request.getHeader("HX-Request"));
        String operation = request.getMethod() + " " + request.getRequestURI();
        RemoteServiceException remote = remoteCause(ex);
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        if (remote != null && remote.kind() == RemoteServiceException.Kind.REJECTED) {
            // Rifiuto atteso (es. "generazione non trovata" cliccando su una tab vecchia): non e' un guasto, quindi
            // niente registro errori; all'utente htmx si mostra comunque il messaggio.
            status = 422;
            if (isHtmx && !response.isCommitted()) {
                htmx.addHxTrigger(response, "system-toast",
                        Map.of("key", "rejected-" + Math.abs(remote.getMessage().hashCode()), "message", remote.getMessage()));
            }
        } else {
            // La source viene dall'eccezione (Replicate, storage...), non e' piu' sempre INTERNAL; un guasto di un
            // servizio esterno e' un 502, non un 500 dell'app.
            ISystemEvents.Recorded recorded = systemEvents.record(operation, ex);
            if (remote != null) {
                status = HttpServletResponse.SC_BAD_GATEWAY;
            }
            if (isHtmx && !response.isCommitted()) {
                htmx.addToastHeader(response, recorded);
            }
        }
        if (response.isCommitted()) {
            return null;
        }
        try {
            response.sendError(status);
        } catch (IOException | IllegalStateException e) {
            log.debug("Impossibile inviare l'errore {}: {}", status, e.toString());
        }
        return new ModelAndView(); // gestita: niente altri resolver
    }

    private static RemoteServiceException remoteCause(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RemoteServiceException remote) {
                return remote;
            }
        }
        return null;
    }

    /** Il client ha chiuso la connessione (tab chiusa, navigazione altrove): non e' un errore dell'app. */
    private static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            String message = t.getMessage() == null ? "" : t.getMessage();
            if (t instanceof AsyncRequestNotUsableException
                    || name.endsWith("ClientAbortException")
                    || message.contains("Broken pipe")
                    || message.contains("Connection reset by peer")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
