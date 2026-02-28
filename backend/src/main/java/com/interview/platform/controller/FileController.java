package com.interview.platform.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * REST controller for serving and accepting locally stored files.
 *
 * <p>
 * Replaces S3 presigned URLs — the frontend accesses files via
 * {@code GET /api/files/**} and uploads via {@code PUT /api/files/upload/**}.
 * </p>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final String storageRootPath;

    public FileController(@Qualifier("storageRootPath") String storageRootPath) {
        this.storageRootPath = storageRootPath;
    }

    /**
     * Serve a locally stored file.
     *
     * <p>
     * The file key is extracted from the URL path after {@code /api/files/}.
     * Content-Type is probed from the file extension.
     * </p>
     *
     * @param request the HTTP request (used to extract the full path)
     * @return the file as a downloadable resource
     */
    @GetMapping("/**")
    public ResponseEntity<Resource> serveFile(HttpServletRequest request) {
        String key = extractKey(request, "/api/files/");
        Path filePath = Path.of(storageRootPath).resolve(key).normalize();

        // Security: prevent path traversal
        if (!filePath.startsWith(Path.of(storageRootPath).normalize())) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                log.warn("File not found: key={}", key);
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filePath.getFileName() + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            log.error("Invalid file path: key={}", key, e);
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            log.error("Error reading file: key={}", key, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Accept a raw PUT body upload (presigned-style flow).
     *
     * <p>
     * The frontend sends the video blob directly in the request body
     * (e.g. {@code axios.put(uploadUrl, videoBlob)}). This matches
     * S3 presigned PUT behavior so the same frontend code works for
     * both local storage and S3.
     * </p>
     *
     * @param request the HTTP request (used to extract the key from path)
     * @param body    the raw file bytes
     * @return success message
     */
    @PutMapping("/upload-raw/**")
    public ResponseEntity<Map<String, String>> uploadFileRaw(HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        String key = extractKey(request, "/api/files/upload-raw/");
        Path filePath = Path.of(storageRootPath).resolve(key).normalize();

        if (!filePath.startsWith(Path.of(storageRootPath).normalize())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid path"));
        }
        if (body == null || body.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
        }

        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, body);
            log.info("File uploaded via PUT (raw): key={}, size={}", key, body.length);
            return ResponseEntity.ok(Map.of("message", "Upload successful", "key", key));
        } catch (IOException e) {
            log.error("Failed to save uploaded file: key={}", key, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload failed"));
        }
    }

    /**
     * Accept a multipart file upload (form-data with "file" part).
     *
     * @param request the HTTP request (used to extract the full path/key)
     * @param file    the uploaded file
     * @return success message
     */
    @PutMapping("/upload/**")
    public ResponseEntity<Map<String, String>> uploadFile(HttpServletRequest request,
            @RequestParam("file") MultipartFile file) {
        String key = extractKey(request, "/api/files/upload/");
        Path filePath = Path.of(storageRootPath).resolve(key).normalize();

        if (!filePath.startsWith(Path.of(storageRootPath).normalize())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid path"));
        }

        try {
            Files.createDirectories(filePath.getParent());
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("File uploaded via PUT: key={}, size={}", key, file.getSize());
            return ResponseEntity.ok(Map.of("message", "Upload successful", "key", key));
        } catch (IOException e) {
            log.error("Failed to save uploaded file: key={}", key, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload failed"));
        }
    }

    private String extractKey(HttpServletRequest request, String prefix) {
        String fullPath = request.getRequestURI();
        int idx = fullPath.indexOf(prefix);
        if (idx >= 0) {
            return fullPath.substring(idx + prefix.length());
        }
        return fullPath;
    }
}
