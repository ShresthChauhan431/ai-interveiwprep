package com.interview.platform.service;

import com.interview.platform.model.AvatarVideoCache;
import com.interview.platform.repository.AvatarVideoCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Service that provides deterministic cache-key lookup for avatar videos.
 *
 * <p>
 * Avatar video generation (ElevenLabs TTS → D-ID lip-sync) is the most
 * expensive operation in the platform, both in latency (~30-120 seconds per
 * question) and cost (~$0.05 per D-ID video). This service implements a
 * 3-tier caching strategy (Caffeine → DB → S3) to avoid redundant generation.
 * </p>
 *
 * <h3>Cache Key Strategy:</h3>
 * <p>
 * The cache key is a SHA-256 hash of the concatenation of:
 * </p>
 * <ul>
 * <li><strong>Question text</strong> — the interview question (normalized:
 * trimmed, lowercased)</li>
 * <li><strong>Voice ID</strong> — the ElevenLabs voice used for TTS
 * (different voices produce different audio)</li>
 * <li><strong>Avatar image URL</strong> — the D-ID source image
 * (different images produce different lip-sync videos)</li>
 * <li><strong>Voice stability</strong> — ElevenLabs voice parameter</li>
 * <li><strong>Similarity boost</strong> — ElevenLabs voice parameter</li>
 * </ul>
 *
 * <p>
 * Any change to these inputs invalidates the cache for that question,
 * ensuring that configuration updates (e.g., switching to a different avatar
 * image or voice) produce fresh videos.
 * </p>
 *
 * <h3>S3 Layout:</h3>
 *
 * <pre>
 *   avatar-cache/{sha256-hash}.mp4
 * </pre>
 *
 * <h3>Cache Layers:</h3>
 * <ol>
 * <li><strong>Level 1: Caffeine (In-Memory)</strong> — Fastest access,
 * populated via Spring Cache.</li>
 * <li><strong>Level 2: Database (MySQL)</strong> — Persistent mapping
 * (cache_key → s3_key). Checked on L1 miss.</li>
 * <li><strong>Level 3: S3 (Object Storage)</strong> — The actual video
 * file.</li>
 * </ol>
 *
 * @see AvatarVideoService
 * @see com.interview.platform.event.AvatarPipelineListener
 */
@Deprecated // FIX: D-ID avatar caching replaced by TTS audio caching
@Service
public class CachedAvatarService {

    private static final Logger log = LoggerFactory.getLogger(CachedAvatarService.class);

    private static final String CACHE_PREFIX = "avatar-cache/";
    private static final String CACHE_EXTENSION = ".mp4";

    private final AvatarVideoService avatarVideoService;
    private final VideoStorageService videoStorageService;
    private final AvatarVideoCacheRepository cacheRepository;

    @Value("${elevenlabs.voice.id:21m00Tcm4TlvDq8ikWAM}")
    private String voiceId;

    @Value("${did.avatar.image.url:https://d-id-public-bucket.s3.us-west-2.amazonaws.com/alice.jpg}")
    private String avatarImageUrl;

    @Value("${elevenlabs.voice.stability:0.5}")
    private double voiceStability;

    @Value("${elevenlabs.voice.similarity-boost:0.75}")
    private double similarityBoost;

    public CachedAvatarService(AvatarVideoService avatarVideoService,
            VideoStorageService videoStorageService,
            AvatarVideoCacheRepository cacheRepository) {
        this.avatarVideoService = avatarVideoService;
        this.videoStorageService = videoStorageService;
        this.cacheRepository = cacheRepository;
    }

    /**
     * Get or generate an avatar video for the given question text.
     *
     * <p>
     * First checks if a cached avatar video exists in S3 for the
     * deterministic cache key. If found, returns the cached S3 key
     * immediately (cache hit). If not found, generates a new avatar
     * video via the full TTS → D-ID pipeline, stores the result
     * at the cache key location, and returns the cache key (cache miss).
     * </p>
     *
     * <p>
     * This method is synchronous and intended to be called from an
     * async context (e.g., {@code AvatarPipelineListener} running on a
     * virtual thread). The blocking I/O (S3 HEAD, TTS call, D-ID polling)
     * yields the virtual thread's carrier thread automatically.
     * </p>
     *
     * @param questionText the interview question text
     * @param questionId   the question entity ID (used as a fallback
     *                     identifier for the generation pipeline)
     * @return the S3 object key of the avatar video (either cached or
     * @return the S3 object key of the avatar video
     * @throws RuntimeException if avatar generation fails
     */
    @Cacheable(value = "avatarVideos", key = "#root.target.computeCacheKey(#questionText)")
    @Transactional
    public String getOrGenerateAvatar(String questionText, Long questionId) {
        String cacheKey = computeCacheKey(questionText);

        // ── Level 2: Check Database ──────────────────────────────
        // If Caffeine (L1) misses, we land here. Check if we have a persistent record.
        var cachedEntry = cacheRepository.findByCacheKey(cacheKey);
        if (cachedEntry.isPresent()) {
            String s3Key = cachedEntry.get().getS3Key();
            log.info("Avatar DB cache HIT for question {}: cacheKey={}", questionId, cacheKey);
            return s3Key;
        }

        // ── Level 3: Check S3 (Legacy Fallback) ──────────────────
        // Check if file exists in S3 at the deterministic location (backward
        // compatibility)
        String legacyS3CacheKey = CACHE_PREFIX + cacheKey + CACHE_EXTENSION;
        if (videoStorageService.fileExists(legacyS3CacheKey)) {
            log.info("Avatar S3 cache HIT for question {}: cacheKey={}", questionId, legacyS3CacheKey);
            // Backward-fill the DB cache so next time we hit L2 or L1
            saveToDbCache(cacheKey, legacyS3CacheKey);
            return legacyS3CacheKey;
        }

        // ── Cache miss — generate new avatar video ───────────────
        log.info("Avatar cache MISS for question {}: cacheKey={}, generating...", questionId, legacyS3CacheKey);

        try {
            // generateAvatarVideo returns the S3 key of the generated video
            // (e.g., "avatar-videos/question_123_1700000000.mp4")
            String generatedS3Key = avatarVideoService.generateAvatarVideo(questionText, questionId);

            // Store mapping in DB cache (Level 2) and implicitly in Caffeine (L1) via
            // return
            saveToDbCache(cacheKey, generatedS3Key);

            // Also copy to deterministic S3 path for redundancy/legacy support
            // (Best effort, non-blocking if we were using async S3 client, but here just
            // robust)
            copyToCache(generatedS3Key, legacyS3CacheKey);

            log.info("Avatar generated and cached for question {}: {}", questionId, generatedS3Key);
            return generatedS3Key;

        } catch (Exception e) {
            log.error("Avatar generation failed for question {}: {}", questionId, e.getMessage(), e);
            throw new RuntimeException("Avatar video generation failed for question " + questionId, e);
        }
    }

    /**
     * Check if a cached avatar video exists for the given question text.
     *
     * <p>
     * Useful for the frontend to determine if a question's avatar video
     * is immediately available or needs to be generated.
     * </p>
     *
     * @param questionText the interview question text
     * @return true if a cached avatar video exists in S3
     */
    public boolean isCached(String questionText) {
        String cacheKey = computeCacheKey(questionText);
        String s3CacheKey = CACHE_PREFIX + cacheKey + CACHE_EXTENSION;
        return videoStorageService.fileExists(s3CacheKey);
    }

    /**
     * Compute a deterministic cache key from the question text and
     * current avatar configuration.
     *
     * <p>
     * The key is a SHA-256 hash of the normalized inputs. Normalization
     * ensures that minor formatting differences (leading/trailing whitespace,
     * case) don't cause cache misses for semantically identical questions.
     * </p>
     *
     * @param questionText the raw question text
     * @return a hex-encoded SHA-256 hash string (64 characters)
     */
    public String computeCacheKey(String questionText) {
        String normalized = normalizeQuestionText(questionText);

        // Build the composite key input
        // Separator '|' prevents ambiguity between fields
        String keyInput = String.join("|",
                normalized,
                voiceId,
                avatarImageUrl,
                String.valueOf(voiceStability),
                String.valueOf(similarityBoost));

        return sha256(keyInput);
    }

    /**
     * Normalize question text for cache key computation.
     *
     * <p>
     * Applies the following transformations:
     * </p>
     * <ul>
     * <li>Trim leading/trailing whitespace</li>
     * <li>Convert to lowercase</li>
     * <li>Collapse multiple whitespace characters to a single space</li>
     * </ul>
     *
     * <p>
     * This ensures that "Tell me about yourself.", "tell me about yourself.",
     * and "Tell me about yourself." all map to the same cache key.
     * </p>
     *
     * @param text the raw question text
     * @return normalized text
     */
    private String normalizeQuestionText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    /**
     * Compute SHA-256 hash of the input string and return as hex.
     *
     * @param input the string to hash
     * @return 64-character lowercase hex string
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JVM spec — this should never happen
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Copy an S3 object from the generated location to the cache key location.
     *
     * <p>
     * Uses S3 server-side copy to avoid downloading and re-uploading the
     * video data. This is efficient because the data stays within S3.
     * </p>
     *
     * <p>
     * If the copy fails (e.g., source key doesn't exist yet due to eventual
     * consistency), it logs a warning but does not throw — the generated key
     * is still valid for immediate use, and the next cache lookup for the
     * same question text will attempt to regenerate and cache again.
     * </p>
     *
     * @param sourceKey      the S3 key of the generated avatar video
     * @param destinationKey the S3 cache key to copy to
     */
    private void copyToCache(String sourceKey, String destinationKey) {
        // Legacy S3 copy - we keep this as a secondary mechanism but rely primarily on
        // DB mapping
        try {
            // Basic implementation: if fileStorageService supported copy, use it.
            // Since we lack explicit copy support in VideoStorageService currently,
            // and we rely on the generated key being the primary artifact via DB mapping,
            // we can skip the physical copy. The generated file IS the cached file.
            // The DB maps cacheKey -> generatedS3Key.
            log.debug("Skipping physical copy from {} to {}; relying on DB cache mapping", sourceKey, destinationKey);
        } catch (Exception e) {
            log.warn("Failed to copy avatar video to cache legacy path: {}", e.getMessage());
        }
    }

    private void saveToDbCache(String cacheKey, String s3Key) {
        try {
            AvatarVideoCache entry = new AvatarVideoCache(cacheKey, s3Key);
            cacheRepository.save(entry);
        } catch (Exception e) {
            // Non-fatal: concurrency could cause duplicate key exceptions
            log.warn("Failed to save avatar cache entry (possibly duplicate): {}", e.getMessage());
        }
    }

    /**
     * Build the S3 cache key path for a given question text.
     *
     * <p>
     * Exposed for testing and for callers that need to check or reference
     * the cache key without triggering generation.
     * </p>
     *
     * @param questionText the question text
     * @return the full S3 key path (e.g., "avatar-cache/a1b2c3...f0.mp4")
     */
    public String buildCacheS3Key(String questionText) {
        return CACHE_PREFIX + computeCacheKey(questionText) + CACHE_EXTENSION;
    }
}
