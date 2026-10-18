package com.steerclub.steerproof.storage;

import com.steerclub.steerproof.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Service
public class StorageService {

    private final Path rootPath;

    public StorageService(@Value("${app.storage.dir:../storage}") String storageDir) {
        this.rootPath = Paths.get(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootPath);
            Files.createDirectories(this.rootPath.resolve("papers"));
            Files.createDirectories(this.rootPath.resolve("pages"));
            Files.createDirectories(this.rootPath.resolve("parsed"));
            Files.createDirectories(this.rootPath.resolve("audio"));
            Files.createDirectories(this.rootPath.resolve("broll"));
            Files.createDirectories(this.rootPath.resolve("broll-library"));
            Files.createDirectories(this.rootPath.resolve("renders"));
            Files.createDirectories(this.rootPath.resolve("exports"));
            Files.createDirectories(this.rootPath.resolve("debug"));
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage directory", e);
        }
    }

    public Path getRootPath() {
        return rootPath;
    }

    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Path cannot be empty");
        }

        // Detect encoded traversal patterns like %2e%2e
        if (relativePath.contains("%2e") || relativePath.contains("%2E")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Path traversal attempt detected");
        }

        Path resolved = rootPath.resolve(relativePath).normalize();

        // Must remain within rootPath
        if (!resolved.startsWith(rootPath)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Path is outside storage directory");
        }

        return resolved;
    }

    public String saveFile(String subDir, String filename, InputStream inputStream) throws IOException {
        Path targetDir = rootPath.resolve(subDir);
        Files.createDirectories(targetDir);
        Path targetFile = targetDir.resolve(filename);
        Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
        return rootPath.relativize(targetFile).toString();
    }

    public Resource loadAsResource(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.exists(file) || !Files.isReadable(file) || Files.isDirectory(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "File not found: " + relativePath, Map.of("path", relativePath));
        }

        try {
            return new UrlResource(file.toUri());
        } catch (MalformedURLException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Invalid URL resource for path: " + relativePath);
        }
    }
}
