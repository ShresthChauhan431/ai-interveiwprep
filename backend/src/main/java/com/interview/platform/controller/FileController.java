package com.interview.platform.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST controller for serving and accepting locally stored files.
 *
 * <p>
 * Replaces S3 presigned URLs — the frontend accesses files via
 * {@code GET /api/files/**} and uploads via {@code PUT /api/files/upload/**}.
 * </p>
 *
 * <h3>Audit Fixes Applied:</h3>
 * <ul>
 *   <li><strong>P0-2 / P0-3:</strong> File endpoints are no longer {@code permitAll()}.
 *       {@code SecurityConfig} now requires authentication for {@code /api/files/**}.
 *       Ownership validation should be added as a follow-up.</li>
 *   <li><strong>P2-6:</strong> Path traversal check now uses {@code toAbsolutePath()}
 *       before {@code normalize()} to prevent bypasses when {@code storageRootPath}
 *       is a relative path (e.g., {@code ./uploads}). Previously, the relative
 *       path comparison could be tricked because
 *       {@code Path.of("./uploads").normalize()} returns {@code "uploads"} but
 *       {@code Path.of("./uploads").resolve("../../../etc/passwd").normalize()}
 *       returns {@code "../../etc/passwd"}, which does NOT start with {@code "uploads"}
 *       — the check worked by accident but was fragile. Using absolute paths makes
 *       the check robust on all platforms.</li>
 *   <li><strong>P2-2:</strong> The storage root is canonicalized to an absolute path
 *       at construction time, ensuring consistent behavior regardless of the working
 *       directory at startup or across different deployment environments.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    /**
     * Canonicalized absolute path to the storage root directory.
     * All file operations are resolved against this path and validated
     * to stay within its subtree (path traversal prevention).
     */
    private final Path storageRoot;

    public FileController(@Qualifier("storageRootPath") String storageRootPath) {
        // P2-2 / P2-6: Canonicalize to absolute path at construction time.
        // This ensures Path.startsWith() checks are reliable regardless of
        // whether the configured path is relative (e.g., "./uploads") or absolute.
        this.storageRoot = Path.of(storageRootPath).toAbsolutePath().normalize();
        log.info("FileController initialized with storage root: {}", this.storageRoot);
    }

    // AUDIT-FIX: Pattern to extract userId from file storage keys (e.g., "interviews/{userId}/..." or "resumes/{userId}/...")
    private static final Pattern OWNER_PATH_PATTERN = Pattern.compile("^(?:interviews|resumes)/(\\d+)/");

    /**
     * AUDIT-FIX: Validate that the authenticated user owns the requested file.
     * File keys follow the pattern "interviews/{userId}/..." or "resumes/{userId}/...".
     * If the userId in the path does not match the authenticated user, access is denied.
     *
     * @param key     the storage key
     * @param request the HTTP request (userId set by JwtAuthenticationFilter)
     * @return true if the user is authorized, false otherwise
     */
    private boolean validateFileOwnership(String key, HttpServletRequest request) {
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr == null) {
            log.warn("File ownership check failed: no userId in request for key='{}'", key);
            return false;
        }
        Long currentUserId = (Long) userIdAttr;

        Matcher matcher = OWNER_PATH_PATTERN.matcher(key);
        if (matcher.find()) {
            Long fileOwnerId = Long.parseLong(matcher.group(1));
            if (!fileOwnerId.equals(currentUserId)) {
                log.warn("File ownership check failed: user={} attempted to access file owned by user={}, key='{}'",
                        currentUserId, fileOwnerId, key);
                return false;
            }
        }
        // If the key doesn't match the pattern, allow access (e.g., shared assets)
        return true;
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

        // AUDIT-FIX: Verify the authenticated user owns the requested file
        if (!validateFileOwnership(key, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // P2-6: Resolve against absolute root, then normalize to collapse any ".." segments
        Path filePath = storageRoot.resolve(key).normalize();

        // Security: prevent path traversal — resolved path must remain under storage root
        if (!filePath.startsWith(storageRoot)) {
            log.warn("Path traversal attempt blocked: key='{}', resolved='{}'", key, filePath);
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

        // AUDIT-FIX: Verify the authenticated user owns the upload path
        if (!validateFileOwnership(key, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }

        // P2-6: Use absolute path for traversal check
        Path filePath = storageRoot.resolve(key).normalize();

        if (!filePath.startsWith(storageRoot)) {
            log.warn("Path traversal attempt blocked on upload-raw: key='{}', resolved='{}'", key, filePath);
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

        // AUDIT-FIX: Verify the authenticated user owns the upload path
        if (!validateFileOwnership(key, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }

        // P2-6: Use absolute path for traversal check
        Path filePath = storageRoot.resolve(key).normalize();

        if (!filePath.startsWith(storageRoot)) {
            log.warn("Path traversal attempt blocked on upload: key='{}', resolved='{}'", key, filePath);
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
