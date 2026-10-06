package org.dual.replicate.core.storage.adapter.out.webdav;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Server WebDAV in memoria per i test (HttpServer JDK: PUT/GET con Range/HEAD/DELETE/MKCOL/MOVE, Basic Auth
 * user:secret sotto {@code /dav}). Nessuna rete esterna. {@code store} contiene i blob cosi' come li vede il server.
 */
public final class FakeWebDavServer {

    public final Map<String, byte[]> store = new ConcurrentHashMap<>();
    public final Map<String, AtomicInteger> gets = new ConcurrentHashMap<>();
    public final AtomicInteger mkcols = new AtomicInteger();
    public final AtomicBoolean down = new AtomicBoolean();
    /** Se != 0, stato con cui risponde MKCOL (es. 409 sulla radice, come Yandex). */
    public volatile int mkcolStatus;
    /** Le prossime {@code failuresLeft} richieste (a qualunque metodo) rispondono {@code failureStatus}, poi si torna normali. */
    public final AtomicInteger failuresLeft = new AtomicInteger();
    public volatile int failureStatus = 503;
    /** Se non null, i fallimenti simulati colpiscono solo questo metodo HTTP. */
    public volatile String failMethod;
    /** Richieste ricevute per metodo (per contare i tentativi). */
    public final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final HttpServer server;

    public FakeWebDavServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav", this::dav);
        server.start();
    }

    /** Serve {@code body} in GET su {@code path} (un'origine "esterna", come un URL di output di Replicate). */
    public void serve(String path, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int getsOf(String name) {
        AtomicInteger n = gets.get("/dav/" + name);
        return n == null ? 0 : n.get();
    }

    public int requestsOf(String method) {
        AtomicInteger n = requests.get(method);
        return n == null ? 0 : n.get();
    }

    public void stop() {
        server.stop(0);
    }

    private void dav(HttpExchange exchange) throws IOException {
        try {
            requests.computeIfAbsent(exchange.getRequestMethod(), k -> new AtomicInteger()).incrementAndGet();
            if ((failMethod == null || failMethod.equals(exchange.getRequestMethod()))
                    && failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                exchange.getRequestBody().readAllBytes(); // come un server vero: il corpo si consuma, altrimenti il client vede un reset
                exchange.sendResponseHeaders(failureStatus, -1);
                return;
            }
            if (down.get()) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            if (!("Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8)))
                    .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            switch (exchange.getRequestMethod()) {
                case "MKCOL" -> {
                    mkcols.incrementAndGet();
                    exchange.sendResponseHeaders(mkcolStatus != 0 ? mkcolStatus : mkcols.get() == 1 ? 201 : 405, -1);
                }
                case "PUT" -> {
                    store.put(path, exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(201, -1);
                }
                case "MOVE" -> {
                    byte[] moved = store.remove(path);
                    String destination = URI.create(exchange.getRequestHeaders().getFirst("Destination")).getPath();
                    if (moved == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        store.put(destination, moved);
                        exchange.sendResponseHeaders(201, -1);
                    }
                }
                case "DELETE" -> exchange.sendResponseHeaders(store.remove(path) == null ? 404 : 204, -1);
                case "HEAD" -> {
                    byte[] body = store.get(path);
                    // L'HttpServer JDK ignora la lunghezza passata per i HEAD: il Content-Length va scritto a mano.
                    if (body != null) {
                        exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
                    }
                    exchange.sendResponseHeaders(body == null ? 404 : 200, -1);
                }
                case "GET" -> {
                    gets.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                    byte[] body = store.get(path);
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    if (range == null) {
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } else {
                        String[] parts = range.substring("bytes=".length()).split("-");
                        int from = Integer.parseInt(parts[0]);
                        int to = Math.min(body.length - 1, Integer.parseInt(parts[1]));
                        exchange.sendResponseHeaders(206, to - from + 1);
                        exchange.getResponseBody().write(body, from, to - from + 1);
                    }
                }
                default -> exchange.sendResponseHeaders(405, -1);
            }
        } finally {
            exchange.close();
        }
    }
}
