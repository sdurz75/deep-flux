package org.dual.replicate.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.service.SystemEventService;
import org.dual.replicate.service.storage.AbstractImageStorageService;
import org.dual.replicate.service.storage.LocalFsImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /images/** serve dallo storage (qui il backend locale): contenuto, tipo, Range (seek dei video), 404. */
class ImageControllerTest {

    @TempDir
    Path dir;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        put("1-0.mp4", "0123456789");
        put("2-0.jpg", "jpeg-bytes");
        LocalFsImageStorageService storage = new LocalFsImageStorageService(dir.toString(), mock(Messages.class));
        mvc = MockMvcBuilders.standaloneSetup(new ImageController(storage, mock(SystemEventService.class))).build();
    }

    @Test
    void servesTheFileWithItsTypeLengthAndAcceptRanges() throws Exception {
        mvc.perform(get("/images/2-0.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Length", "10"))
                .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
                .andExpect(content().string("jpeg-bytes"));
    }

    @Test
    void aConditionalRequestGets304() throws Exception {
        String etag = mvc.perform(get("/images/2-0.jpg")).andExpect(header().exists("ETag")).andReturn()
                .getResponse().getHeader("ETag");

        mvc.perform(get("/images/2-0.jpg").header("If-None-Match", etag)).andExpect(status().isNotModified());
    }

    @Test
    void servesVideosAsMp4() throws Exception {
        mvc.perform(get("/images/1-0.mp4"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"));
    }

    @Test
    void answersARangeWith206AndContentRange() throws Exception {
        mvc.perform(get("/images/1-0.mp4").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(header().string("Content-Length", "4"))
                .andExpect(content().string("2345"));
        mvc.perform(get("/images/1-0.mp4").header("Range", "bytes=7-"))
                .andExpect(status().isPartialContent())
                .andExpect(content().string("789"));
        mvc.perform(get("/images/1-0.mp4").header("Range", "bytes=-3"))
                .andExpect(status().isPartialContent())
                .andExpect(content().string("789"));
    }

    @Test
    void anUnsatisfiableRangeIs416() throws Exception {
        mvc.perform(get("/images/1-0.mp4").header("Range", "bytes=50-60"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */10"));
    }

    @Test
    void headHasNoBody() throws Exception {
        mvc.perform(head("/images/2-0.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", "10"))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void unknownFileIs404() throws Exception {
        mvc.perform(get("/images/nope.png")).andExpect(status().isNotFound());
    }

    @Test
    void pathTraversalIs404() throws Exception {
        Files.writeString(dir.getParent().resolve("secret.png"), "secret");
        mvc.perform(get("/images/..%2Fsecret.png")).andExpect(status().isNotFound());
        mvc.perform(get("/images/a..b.png")).andExpect(status().isNotFound());
    }

    @Test
    void servesEmptyFiles() throws Exception {
        put("3-0.png", "");
        mvc.perform(get("/images/3-0.png")).andExpect(status().isOk()).andExpect(header().string("Content-Length", "0"));
    }

    private void put(String filename, String content) throws IOException {
        Path file = dir.resolve(AbstractImageStorageService.shardPath(filename));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
