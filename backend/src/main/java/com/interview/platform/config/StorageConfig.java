package com.interview.platform.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local file storage configuration.
 *
 * <p>
 * Replaces the AWS S3 configuration with local filesystem storage.
 * Files are stored under a configurable root directory (default:
 * {@code ./uploads}).
 * This allows the application to run without AWS credentials.
 * </p>
 *
 * <h3>Audit Fixes Applied:</h3>
 * <ul>
 *   <li><strong>P2-2:</strong> The storage path is now canonicalized to an
 *       absolute path via {@code toAbsolutePath().normalize()} at startup.
 *       Previously, the relative default {@code ./uploads} resolved differently
 *       depending on the working directory, and was ephemeral in containers
 *       without a mounted volume. Canonicalization ensures consistent path
 *       resolution and makes the path traversal check in {@code FileController}
 *       reliable (see P2-6).</li>
 *   <li><strong>P1-6:</strong> The {@code app.base-url} default is now
 *       {@code http://localhost:8081} to match the server port. Previously it
 *       defaulted to port 8080, causing all generated file URLs to 404 in a
 *       fresh clone without explicit {@code APP_BASE_URL} override.</li>
 *   <li><strong>P3-12 (partial):</strong> Added {@code @PostConstruct}
 *       validation to fail fast if the storage directory cannot be created.</li>
 * </ul>
 *
 * <h3>Served via:</h3>
 * <p>
 * Files are served to the frontend via {@code /api/files/**} endpoints
 * handled by {@code FileController}.
 * </p>
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Value("${storage.local.path:./uploads}")
    private String storagePath;

    @Value("${app.base-url:http://localhost:8081}")
    private String baseUrl;

    /**
     * The canonicalized absolute path to the storage root directory.
     *
     * <p>P2-2: Computed once at startup via {@code toAbsolutePath().normalize()}
     * so that all downstream consumers (FileController, VideoStorageService)
     * work with the same canonical path. This is critical for the path traversal
     * check in FileController (P2-6) — {@code Path.startsWith()} only works
     * reliably when both paths are absolute.</p>
     */
    private Path canonicalStorageRoot;

    /**
     * Create the storage root directory at startup if it doesn't exist.
     *
     * <p>P2-2: Canonicalizes the configured storage path to an absolute,
     * normalized path. This prevents issues where:</p>
     * <ul>
     *   <li>Relative paths resolve differently depending on CWD at startup</li>
     *   <li>Path traversal checks in FileController fail on relative paths</li>
     *   <li>Container restarts lose uploads when using ephemeral relative paths
     *       without a mounted volume</li>
     * </ul>
     */
    @PostConstruct
    public void init() {
        try {
            // P2-2: Canonicalize to absolute path for reliable path operations
            canonicalStorageRoot = Path.of(storagePath).toAbsolutePath().normalize();
            Files.createDirectories(canonicalStorageRoot);
            log.info("Local file storage initialized: path={} (configured as '{}'), baseUrl={}",
                    canonicalStorageRoot, storagePath, baseUrl);

            // Warn if the path looks ephemeral (e.g., inside /tmp or a relative path in a container)
            String absPath = canonicalStorageRoot.toString();
            if (absPath.startsWith("/tmp") || absPath.startsWith("/var/tmp")) {
                log.warn("Storage path '{}' is in a temporary directory and may be cleared on reboot. "
                        + "Consider using a persistent path or a mounted volume.", absPath);
            }
        } catch (IOException e) {
            log.error("Failed to create storage directory: configured='{}', resolved='{}'",
                    storagePath, canonicalStorageRoot, e);
            throw new RuntimeException("Cannot initialize local storage at: " + storagePath, e);
        }
    }

    /**
     * Expose the canonicalized storage root path as a bean for injection.
     *
     * <p>P2-2: Returns the absolute, normalized path string rather than the
     * raw configured value. All consumers (FileController, VideoStorageService)
     * receive the same canonical path, ensuring consistent file resolution
     * and secure path traversal checks.</p>
     */
    @Bean("storageRootPath")
    public String storageRootPath() {
        // Return the canonical absolute path (computed in @PostConstruct)
        // If init() hasn't run yet (shouldn't happen with Spring lifecycle),
        // fall back to computing it inline.
        if (canonicalStorageRoot != null) {
            return canonicalStorageRoot.toString();
        }
        return Path.of(storagePath).toAbsolutePath().normalize().toString();
    }

    /**
     * Expose the application base URL for generating file URLs.
     *
     * <p>P1-6: Default changed from port 8080 to 8081 to match
     * {@code server.port} in application.properties.</p>
     */
    @Bean("appBaseUrl")
    public String appBaseUrl() {
        return baseUrl;
    }
}
