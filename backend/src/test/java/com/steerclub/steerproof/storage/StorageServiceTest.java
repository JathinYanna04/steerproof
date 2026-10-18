package com.steerclub.steerproof.storage;

import com.steerclub.steerproof.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StorageServiceTest {

    @TempDir
    Path tempDir;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(tempDir.toString());
    }

    @Test
    void testMissingFileReturnsNotFound() {
        ApiException ex = assertThrows(ApiException.class, () -> storageService.loadAsResource("nonexistent.png"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void testPathTraversalDotDotReturnsBadRequest() {
        ApiException ex = assertThrows(ApiException.class, () -> storageService.resolve("../outside.txt"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("PATH_TRAVERSAL", ex.getErrorCode());
    }

    @Test
    void testUrlEncodedPathTraversalReturnsBadRequest() {
        ApiException ex = assertThrows(ApiException.class, () -> storageService.resolve("%2e%2e/outside.txt"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("PATH_TRAVERSAL", ex.getErrorCode());
    }

    @Test
    void testSaveAndLoadFile() throws IOException {
        String savedRel = storageService.saveFile("test", "hello.txt", new ByteArrayInputStream("hello world".getBytes()));
        assertNotNull(savedRel);
        var res = storageService.loadAsResource(savedRel);
        assertTrue(res.exists());
    }
}
