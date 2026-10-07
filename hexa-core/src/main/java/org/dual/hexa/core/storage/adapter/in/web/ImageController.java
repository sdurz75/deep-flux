package org.dual.hexa.core.storage.adapter.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.OptionalLong;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.dual.hexa.core.storage.domain.StorageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Serve i binari (immagini, video, upload) da {@link IImageStorageService} sotto {@code /images/**}, qualunque sia il
 * backend (filesystem locale o WebDAV cifrato: qui arrivano sempre in chiaro). Supporta {@code Range} (un solo
 * intervallo), necessario al seek dei {@code <video>}.
 */
@Controller
public class ImageController {

    private static final int BUFFER_SIZE = 64 * 1024;
    /** Nomi unici e immutabili (id/UUID): il browser non deve rivalidare (su WebDAV ogni rivalida e' una HEAD). Solo sui 200/206. */
    private static final String CACHE_CONTROL = "private, max-age=31536000, immutable";

    private final IImageStorageService storage;
    private final ISystemEvents systemEvents;

    public ImageController(IImageStorageService storage, ISystemEvents systemEvents) {
        this.storage = storage;
        this.systemEvents = systemEvents;
    }

    @GetMapping("/images/{filename:.+}")
    public void serve(@PathVariable String filename, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long size;
        try {
            OptionalLong found = storage.size(filename);
            if (found.isEmpty()) {
                response.sendError(HttpStatus.NOT_FOUND.value());
                return;
            }
            size = found.getAsLong();
        } catch (IllegalArgumentException e) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        } catch (StorageException e) {
            systemEvents.record(CoreEventSource.STORAGE, "serveImage", e);
            response.sendError(HttpStatus.BAD_GATEWAY.value());
            return;
        }

        // I nomi sono unici e immutabili: la dimensione basta come validatore (il vecchio resource handler statico
        // dava ETag/Last-Modified; senza, il browser riscaricherebbe ogni immagine a ogni visita).
        if (new ServletWebRequest(request, response).checkNotModified("\"" + Long.toHexString(size) + "\"")) {
            return;
        }

        long start = 0;
        long end = size - 1;
        boolean partial = false;
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (ranges.size() == 1) {
                    start = ranges.get(0).getRangeStart(size);
                    end = ranges.get(0).getRangeEnd(size);
                    partial = true;
                    if (start >= size || start > end) {
                        // HttpRange non lancia se l'inizio e' oltre la fine del file: si segnala qui.
                        throw new IllegalArgumentException(rangeHeader);
                    }
                }
                // Piu' intervalli: non supportati, si serve il file intero (risposta valida per HTTP).
            } catch (IllegalArgumentException e) {
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                response.sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                return;
            }
        }
        long length = size == 0 ? 0 : end - start + 1;

        InputStream in = null;
        if (length > 0 && !"HEAD".equals(request.getMethod())) {
            try {
                in = storage.openRange(filename, start, length);
            } catch (IOException | StorageException e) {
                systemEvents.record(CoreEventSource.STORAGE, "serveImage", e);
                response.sendError(HttpStatus.BAD_GATEWAY.value());
                return;
            }
        }
        try {
            response.setStatus(partial ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
            response.setContentType(IImageStorageService.mimeOf(filename));
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (partial) {
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
            }
            response.setContentLengthLong(length);
            if (in != null) {
                copy(in, response.getOutputStream(), filename);
            }
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    /**
     * Distingue gli errori di lettura dallo storage (da registrare: file corrotto, chiave errata, WebDAV giu') da
     * quelli di scrittura verso il client (chiusura del browser o seek di un video: normali, ignorati).
     */
    private void copy(InputStream in, OutputStream out, String filename) {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            int read;
            try {
                read = in.read(buffer);
            } catch (IOException e) {
                systemEvents.record(CoreEventSource.STORAGE, "serveImage", e);
                return;
            }
            if (read < 0) {
                return;
            }
            try {
                out.write(buffer, 0, read);
            } catch (IOException clientGone) {
                return;
            }
        }
    }
}
