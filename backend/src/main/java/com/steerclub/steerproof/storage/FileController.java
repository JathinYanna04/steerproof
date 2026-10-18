package com.steerclub.steerproof.storage;

import com.steerclub.steerproof.common.ApiException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class FileController {

    private final StorageService storageService;

    public FileController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/files/**")
    public ResponseEntity<Resource> serveFile(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri == null || !requestUri.startsWith("/files/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Invalid file request URI");
        }

        // Check raw query and raw URI for encoded traversals
        String rawUri = request.getRequestURI();
        if (rawUri.toLowerCase().contains("%2e") || rawUri.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Path traversal detected");
        }

        String relativePath = requestUri.substring("/files/".length());
        Resource resource = storageService.loadAsResource(relativePath);

        String contentType = null;
        try {
            Path filePath = storageService.resolve(relativePath);
            contentType = Files.probeContentType(filePath);
        } catch (IOException ignored) {}

        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }
}
