package com.example.oauth2host;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Un provider OIDC finto, in-process: discovery, JWKS e endpoint dei token con una chiave RSA generata al volo. I test non chiamano MAI un provider vero.
 * Chi guida il flusso imposta {@link #nonce} (letto dalla richiesta di autorizzazione), l'email e il {@code sub} dell'utente che "si autentica".
 */
final class FakeOidcProvider implements AutoCloseable {

    static final String CLIENT_ID = "client-1";
    static final String CLIENT_SECRET = "secret-1";

    private final HttpServer server;
    private final RSAKey key;
    volatile String nonce;
    volatile String email = "ok@example.com";
    volatile boolean emailVerified = true;
    volatile String subject = "sub-1";

    FakeOidcProvider() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/.well-known/openid-configuration", exchange -> respond(exchange, """
                {"issuer":"%1$s","authorization_endpoint":"%1$s/authorize","token_endpoint":"%1$s/token","jwks_uri":"%1$s/jwks",
                 "token_endpoint_auth_methods_supported":["client_secret_basic"]}""".formatted(issuer())));
        server.createContext("/jwks", exchange -> respond(exchange, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                respond(exchange, """
                        {"access_token":"at","token_type":"Bearer","expires_in":3600,"id_token":"%s"}""".formatted(idToken()));
            } catch (Exception e) {
                throw new IOException(e);
            }
        });
        server.start();
    }

    String issuer() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private String idToken() throws Exception {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer()).subject(subject).audience(CLIENT_ID)
                .issueTime(now).expirationTime(new Date(now.getTime() + 300_000)).claim("nonce", nonce)
                .claim("email", email).claim("email_verified", emailVerified).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
