package com.interview.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Service for file storage operations using local filesystem.
 *
 * <p>
 * Replaces the previous S3-based implementation. Files are stored under
 * a configurable root directory (default: {@code ./uploads}). URLs returned
 * by presigned URL methods point to {@code /api/files/**} endpoints served
 * by {@code FileController}.
 * </p>
 *
 * <h3>Key Storage Strategy:</h3>
 * <p>
 * All entity fields that store file references use relative storage keys
 * (e.g., {@code interviews/1/100/response_200_12345.webm}). These keys are
 * resolved to HTTP URLs on-demand when building DTOs for the frontend.
 * </p>
 */
@Service
public class VideoStorageService {

    private static final Logger log = LoggerFactory.getLogger(VideoStorageService.class);

    private final String storageRootPath;
    private final String appBaseUrl;

    public VideoStorageService(@Qualifier("storageRootPath") String storageRootPath,
            @Qualifier("appBaseUrl") String appBaseUrl) {
        this.storageRootPath = storageRootPath;
        this.appBaseUrl = appBaseUrl;
    }

    // ════════════════════════════════════════════════════════════════
    // Server-side uploads (used by resume upload, TTS audio, avatar)
    // ════════════════════════════════════════════════════════════════

    /**
     * Upload a video response to local storage.
     *
     * @param file        the video file
     * @param userId      owner user ID (used in key path)
     * @param interviewId interview ID (used in key path)
     * @param questionId  question ID (used in key path)
     * @return the storage key (NOT a URL)
     */
    public String uploadVideo(MultipartFile file, Long userId, Long interviewId, Long questionId) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String key = String.format("interviews/%d/%d/response_%d_%s.webm",
                userId, interviewId, questionId, timestamp);

        try {
            Path filePath = resolveAndCreateDirs(key);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Uploaded video to local storage: key={}, user={}", key, userId);
            return key;
        } catch (IOException e) {
            log.error("Failed to save video file: user={}, interview={}, question={}",
                    userId, interviewId, questionId, e);
            throw new RuntimeException("Failed to upload video: " + e.getMessage(), e);
        }
    }

    /**
     * Upload a resume file to local storage.
     *
     * @param file   the resume file (PDF or DOCX)
     * @param userId owner user ID
     * @return the storage key (NOT a URL)
     */
    public String uploadResumeFile(MultipartFile file, Long userId) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".pdf";
        String key = String.format("resumes/%d/resume_%s%s", userId, timestamp, extension);

        try {
            Path filePath = resolveAndCreateDirs(key);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Uploaded resume to local storage: key={}, user={}", key, userId);
            return key;
        } catch (IOException e) {
            log.error("Failed to save resume file: user={}", userId, e);
            throw new RuntimeException("Failed to upload resume: " + e.getMessage(), e);
        }
    }

    /**
     * Upload raw bytes to local storage (used by TTS audio and avatar video
     * pipeline).
     *
     * @param data        the file bytes
     * @param key         the storage key
     * @param contentType the MIME type (unused for local storage, kept for API
     *                    compatibility)
     * @return the storage key (same as input — returned for API consistency)
     */
    public String uploadBytes(byte[] data, String key, String contentType) {
        try {
            Path filePath = resolveAndCreateDirs(key);
            Files.write(filePath, data);
            log.info("Uploaded bytes to local storage: key={}, size={}, type={}", key, data.length, contentType);
            return key;
        } catch (IOException e) {
            log.error("Failed to write bytes to local storage: key={}", key, e);
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }
    }



    // ════════════════════════════════════════════════════════════════
    // URL Generation (local HTTP endpoints replace presigned URLs)
    // ════════════════════════════════════════════════════════════════

    /**
     * Generate a URL for accessing a locally stored file.
     *
     * <p>
     * Returns a URL like {@code http://localhost:8080/api/files/some/key}.
     * </p>
     *
     * @param key the storage key
     * @return an HTTP URL for the file
     * @throws IllegalArgumentException if the key is null or blank
     */
    public String generatePresignedGetUrl(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be null or blank");
        }
        String url = appBaseUrl + "/api/files/" + key;
        log.debug("Generated file URL for key={}", key);
        return url;
    }

    /**
     * Generate a URL for accessing a locally stored file (custom duration ignored).
     *
     * @param key             the storage key
     * @param durationMinutes ignored (kept for API compatibility)
     * @return an HTTP URL for the file
     */
    public String generatePresignedGetUrl(String key, int durationMinutes) {
        return generatePresignedGetUrl(key);
    }

    /**
     * Generate a URL for direct file upload (raw PUT body).
     *
     * <p>
     * Returns an upload URL like
     * {@code http://localhost:8080/api/files/upload-raw/some/key}.
     * The frontend sends the file as raw request body (same as S3 presigned PUT).
     * </p>
     *
     * @param key         the storage key where the file will be stored
     * @param contentType expected MIME type (unused for local storage, kept for API
     *                    compatibility)
     * @return an HTTP URL for uploading the file
     */
    public String generatePresignedPutUrl(String key, String contentType) {
        String url = appBaseUrl + "/api/files/upload-raw/" + key;
        log.info("Generated upload URL for key={}", key);
        return url;
    }

    // ════════════════════════════════════════════════════════════════
    // File Operations
    // ════════════════════════════════════════════════════════════════

    /**
     * Generate a storage key for a user's video response upload.
     *
     * @param userId      owner user ID
     * @param interviewId interview ID
     * @param questionId  question ID
     * @return the computed storage key
     */
    public String buildVideoResponseKey(Long userId, Long interviewId, Long questionId) {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        return String.format("interviews/%d/%d/response_%d_%s.webm",
                userId, interviewId, questionId, timestamp);
    }

    /**
     * Delete a file from local storage.
     *
     * <p>
     * Failures are logged but do not throw exceptions (graceful degradation).
     * </p>
     *
     * @param key the storage key to delete
     */
    public void deleteFile(String key) {
        try {
            Path filePath = Path.of(storageRootPath).resolve(key).normalize();
            if (Files.deleteIfExists(filePath)) {
                log.info("Deleted file: key={}", key);
            } else {
                log.debug("File not found for deletion: key={}", key);
            }
        } catch (IOException e) {
            log.error("Failed to delete file: key={}", key, e);
            // Graceful — don't throw, just log
        }
    }

    /**
     * Check if a file exists in local storage.
     *
     * @param key the storage key to check
     * @return true if the file exists
     */
    public boolean fileExists(String key) {
        Path filePath = Path.of(storageRootPath).resolve(key).normalize();
        return Files.exists(filePath);
    }



    // ════════════════════════════════════════════════════════════════
    // Internal helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * Resolve a key to a full file path and create parent directories.
     */
    private Path resolveAndCreateDirs(String key) throws IOException {
        Path filePath = Path.of(storageRootPath).resolve(key).normalize();
        Files.createDirectories(filePath.getParent());
        return filePath;
    }
}
