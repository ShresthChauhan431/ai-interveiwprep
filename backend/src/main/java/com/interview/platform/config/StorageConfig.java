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

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * Create the storage root directory at startup if it doesn't exist.
     */
    @PostConstruct
    public void init() {
        try {
            Path root = Path.of(storagePath);
            Files.createDirectories(root);
            log.info("Local file storage initialized: path={}, baseUrl={}", root.toAbsolutePath(), baseUrl);
        } catch (IOException e) {
            log.error("Failed to create storage directory: {}", storagePath, e);
            throw new RuntimeException("Cannot initialize local storage at: " + storagePath, e);
        }
    }

    /**
     * Expose the storage root path as a bean for injection.
     */
    @Bean("storageRootPath")
    public String storageRootPath() {
        return storagePath;
    }

    /**
     * Expose the application base URL for generating file URLs.
     */
    @Bean("appBaseUrl")
    public String appBaseUrl() {
        return baseUrl;
    }
}
