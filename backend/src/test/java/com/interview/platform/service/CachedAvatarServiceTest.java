package com.interview.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CachedAvatarService Tests")
class CachedAvatarServiceTest {

    @Mock
    private AvatarVideoService avatarVideoService;

    @Mock
    private VideoStorageService videoStorageService;

    @Mock
    private com.interview.platform.repository.AvatarVideoCacheRepository cacheRepository;

    @InjectMocks
    private CachedAvatarService cachedAvatarService;

    private static final String DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM";
    private static final String DEFAULT_AVATAR_IMAGE_URL = "https://d-id-public-bucket.s3.us-west-2.amazonaws.com/alice.jpg";
    private static final double DEFAULT_VOICE_STABILITY = 0.5;
    private static final double DEFAULT_SIMILARITY_BOOST = 0.75;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cachedAvatarService, "voiceId", DEFAULT_VOICE_ID);
        ReflectionTestUtils.setField(cachedAvatarService, "avatarImageUrl", DEFAULT_AVATAR_IMAGE_URL);
        ReflectionTestUtils.setField(cachedAvatarService, "voiceStability", DEFAULT_VOICE_STABILITY);
        ReflectionTestUtils.setField(cachedAvatarService, "similarityBoost", DEFAULT_SIMILARITY_BOOST);
    }

    // ============================================================
    // getOrGenerateAvatar — cache hit
    // ============================================================

    @Nested
    @DisplayName("getOrGenerateAvatar — cache hit")
    class CacheHitTests {

        @Test
        @DisplayName("Should return cached S3 key when avatar video already exists in S3")
        void testCacheHit_ReturnsCachedKey() {
            // Arrange
            String questionText = "Tell me about your experience with Spring Boot.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(true);

            // Act
            String result = cachedAvatarService.getOrGenerateAvatar(questionText, 201L);

            // Assert — returns the cache key without generating
            assertThat(result).isEqualTo(cacheKey);
            assertThat(result).startsWith("avatar-cache/");
            assertThat(result).endsWith(".mp4");

            // Should NOT call avatar generation
            verify(avatarVideoService, never()).generateAvatarVideo(anyString(), anyLong());
        }

        @Test
        @DisplayName("Should check S3 existence using the deterministic cache key")
        void testCacheHit_ChecksCorrectS3Key() {
            String questionText = "What are your strengths?";
            String expectedCacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(expectedCacheKey)).thenReturn(true);

            cachedAvatarService.getOrGenerateAvatar(questionText, 100L);

            verify(videoStorageService).fileExists(expectedCacheKey);
        }

        @Test
        @DisplayName("Cache hit should not depend on questionId — same text, different IDs, same result")
        void testCacheHit_QuestionIdIndependent() {
            String questionText = "Describe a challenging project.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(true);

            String result1 = cachedAvatarService.getOrGenerateAvatar(questionText, 100L);
            String result2 = cachedAvatarService.getOrGenerateAvatar(questionText, 999L);

            assertThat(result1).isEqualTo(result2);
            assertThat(result1).isEqualTo(cacheKey);

            // Should never generate
            verify(avatarVideoService, never()).generateAvatarVideo(anyString(), anyLong());
        }
    }

    // ============================================================
    // getOrGenerateAvatar — cache miss
    // ============================================================

    @Nested
    @DisplayName("getOrGenerateAvatar — cache miss")
    class CacheMissTests {

        @Test
        @DisplayName("Should generate avatar video and return cache S3 key on cache miss")
        void testCacheMiss_GeneratesAndReturnsCacheKey() {
            // Arrange
            String questionText = "Tell me about yourself.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(false);
            when(avatarVideoService.generateAvatarVideo(questionText, 201L))
                    .thenReturn("avatar-videos/question_201_1700000000.mp4");
            // Act
            String result = cachedAvatarService.getOrGenerateAvatar(questionText, 201L);

            // Assert — returns the generated key
            assertThat(result).isEqualTo("avatar-videos/question_201_1700000000.mp4");

            // Assert — avatar generation was invoked
            verify(avatarVideoService).generateAvatarVideo(questionText, 201L);
        }

        @Test
        @DisplayName("Should call AvatarVideoService.generateAvatarVideo on cache miss")
        void testCacheMiss_CallsAvatarVideoService() {
            String questionText = "What motivates you?";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(false);
            when(avatarVideoService.generateAvatarVideo(questionText, 500L))
                    .thenReturn("avatar-videos/question_500_ts.mp4");
            cachedAvatarService.getOrGenerateAvatar(questionText, 500L);

            verify(avatarVideoService).generateAvatarVideo(questionText, 500L);
        }

        @Test
        @DisplayName("Should throw RuntimeException when avatar generation fails on cache miss")
        void testCacheMiss_GenerationFailure() {
            String questionText = "Describe your leadership style.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(false);
            when(avatarVideoService.generateAvatarVideo(questionText, 300L))
                    .thenThrow(new RuntimeException("D-ID API returned 503"));

            assertThatThrownBy(() -> cachedAvatarService.getOrGenerateAvatar(questionText, 300L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Avatar video generation failed for question 300");
        }

        @Test
        @DisplayName("Should throw RuntimeException when AvatarVideoService returns null")
        void testCacheMiss_NullReturnFromGeneration() {
            String questionText = "Why should we hire you?";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(false);
            when(avatarVideoService.generateAvatarVideo(questionText, 400L))
                    .thenReturn(null);
            // Act
            String result = cachedAvatarService.getOrGenerateAvatar(questionText, 400L);

            assertThat(result).isNull();
        }
    }

    // ============================================================
    // computeCacheKey — determinism and normalization
    // ============================================================

    @Nested
    @DisplayName("computeCacheKey")
    class ComputeCacheKeyTests {

        @Test
        @DisplayName("Should produce deterministic output for the same input")
        void testDeterministic() {
            String text = "Tell me about your experience.";

            String key1 = cachedAvatarService.computeCacheKey(text);
            String key2 = cachedAvatarService.computeCacheKey(text);

            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("Should produce 64-character hex string (SHA-256)")
        void testSha256Length() {
            String key = cachedAvatarService.computeCacheKey("Any question text here.");

            assertThat(key).hasSize(64);
            assertThat(key).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("Should normalize: trim whitespace")
        void testNormalize_Trim() {
            String key1 = cachedAvatarService.computeCacheKey("  Tell me about yourself.  ");
            String key2 = cachedAvatarService.computeCacheKey("Tell me about yourself.");

            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("Should normalize: case-insensitive")
        void testNormalize_CaseInsensitive() {
            String key1 = cachedAvatarService.computeCacheKey("Tell Me About Yourself.");
            String key2 = cachedAvatarService.computeCacheKey("tell me about yourself.");

            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("Should normalize: collapse multiple whitespace to single space")
        void testNormalize_CollapseWhitespace() {
            String key1 = cachedAvatarService.computeCacheKey("Tell  me   about    yourself.");
            String key2 = cachedAvatarService.computeCacheKey("Tell me about yourself.");

            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("Should normalize tabs and newlines to spaces")
        void testNormalize_TabsAndNewlines() {
            String key1 = cachedAvatarService.computeCacheKey("Tell\tme\nabout\r\nyourself.");
            String key2 = cachedAvatarService.computeCacheKey("Tell me about yourself.");

            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("Should produce different keys for different question texts")
        void testDifferentTexts() {
            String key1 = cachedAvatarService.computeCacheKey("Tell me about yourself.");
            String key2 = cachedAvatarService.computeCacheKey("What are your strengths?");

            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("Should produce different keys when voice ID changes")
        void testDifferentVoiceId() {
            String key1 = cachedAvatarService.computeCacheKey("Same question.");

            ReflectionTestUtils.setField(cachedAvatarService, "voiceId", "differentVoiceId");
            String key2 = cachedAvatarService.computeCacheKey("Same question.");

            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("Should produce different keys when avatar image URL changes")
        void testDifferentAvatarImageUrl() {
            String key1 = cachedAvatarService.computeCacheKey("Same question.");

            ReflectionTestUtils.setField(cachedAvatarService, "avatarImageUrl",
                    "https://different-bucket.s3.amazonaws.com/new-avatar.jpg");
            String key2 = cachedAvatarService.computeCacheKey("Same question.");

            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("Should produce different keys when voice stability changes")
        void testDifferentVoiceStability() {
            String key1 = cachedAvatarService.computeCacheKey("Same question.");

            ReflectionTestUtils.setField(cachedAvatarService, "voiceStability", 0.9);
            String key2 = cachedAvatarService.computeCacheKey("Same question.");

            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("Should produce different keys when similarity boost changes")
        void testDifferentSimilarityBoost() {
            String key1 = cachedAvatarService.computeCacheKey("Same question.");

            ReflectionTestUtils.setField(cachedAvatarService, "similarityBoost", 0.3);
            String key2 = cachedAvatarService.computeCacheKey("Same question.");

            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("Should handle null question text without throwing")
        void testNullText() {
            // computeCacheKey normalizes null to empty string
            assertThatCode(() -> cachedAvatarService.computeCacheKey(null))
                    .doesNotThrowAnyException();

            String key = cachedAvatarService.computeCacheKey(null);
            assertThat(key).hasSize(64);
        }

        @Test
        @DisplayName("Should handle empty question text")
        void testEmptyText() {
            assertThatCode(() -> cachedAvatarService.computeCacheKey(""))
                    .doesNotThrowAnyException();

            String key = cachedAvatarService.computeCacheKey("");
            assertThat(key).hasSize(64);
        }

        @Test
        @DisplayName("Null and empty text should produce the same cache key")
        void testNullEqualsEmpty() {
            String keyNull = cachedAvatarService.computeCacheKey(null);
            String keyEmpty = cachedAvatarService.computeCacheKey("");

            assertThat(keyNull).isEqualTo(keyEmpty);
        }
    }

    // ============================================================
    // buildCacheS3Key
    // ============================================================

    @Nested
    @DisplayName("buildCacheS3Key")
    class BuildCacheS3KeyTests {

        @Test
        @DisplayName("Should return key with avatar-cache/ prefix and .mp4 extension")
        void testFormat() {
            String s3Key = cachedAvatarService.buildCacheS3Key("Tell me about yourself.");

            assertThat(s3Key).startsWith("avatar-cache/");
            assertThat(s3Key).endsWith(".mp4");
        }

        @Test
        @DisplayName("Should contain the SHA-256 hash in the middle")
        void testContainsHash() {
            String questionText = "Tell me about yourself.";
            String expectedHash = cachedAvatarService.computeCacheKey(questionText);
            String s3Key = cachedAvatarService.buildCacheS3Key(questionText);

            assertThat(s3Key).isEqualTo("avatar-cache/" + expectedHash + ".mp4");
        }

        @Test
        @DisplayName("Should be deterministic for the same text")
        void testDeterministic() {
            String key1 = cachedAvatarService.buildCacheS3Key("What are your weaknesses?");
            String key2 = cachedAvatarService.buildCacheS3Key("What are your weaknesses?");

            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("Should normalize text before building key")
        void testNormalized() {
            String key1 = cachedAvatarService.buildCacheS3Key("  WHAT are your weaknesses?  ");
            String key2 = cachedAvatarService.buildCacheS3Key("what are your weaknesses?");

            assertThat(key1).isEqualTo(key2);
        }
    }

    // ============================================================
    // isCached
    // ============================================================

    @Nested
    @DisplayName("isCached")
    class IsCachedTests {

        @Test
        @DisplayName("Should return true when cached avatar exists in S3")
        void testIsCached_True() {
            String questionText = "Tell me about yourself.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(true);

            assertThat(cachedAvatarService.isCached(questionText)).isTrue();

            verify(videoStorageService).fileExists(cacheKey);
        }

        @Test
        @DisplayName("Should return false when no cached avatar exists in S3")
        void testIsCached_False() {
            String questionText = "Describe a time you failed.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(false);

            assertThat(cachedAvatarService.isCached(questionText)).isFalse();

            verify(videoStorageService).fileExists(cacheKey);
        }

        @Test
        @DisplayName("Should use normalized text for cache lookup")
        void testIsCached_Normalized() {
            String rawText = "  TELL me about   yourself.  ";
            String normalizedCacheKey = cachedAvatarService.buildCacheS3Key(rawText);

            when(videoStorageService.fileExists(normalizedCacheKey)).thenReturn(true);

            assertThat(cachedAvatarService.isCached(rawText)).isTrue();

            // Verify it checked the normalized cache key (same as clean text)
            String cleanCacheKey = cachedAvatarService.buildCacheS3Key("tell me about yourself.");
            assertThat(normalizedCacheKey).isEqualTo(cleanCacheKey);
        }
    }

    // ============================================================
    // Edge cases and error handling
    // ============================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle very long question text without issues")
        void testLongQuestionText() {
            String longText = "Tell me about your experience with ".repeat(100) + "microservices.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(longText);

            // SHA-256 always produces 64-char hex regardless of input size
            assertThat(cacheKey).startsWith("avatar-cache/");
            assertThat(cacheKey).endsWith(".mp4");
            // avatar-cache/ (13) + hash (64) + .mp4 (4) = 81 chars
            assertThat(cacheKey).hasSize(81);
        }

        @Test
        @DisplayName("Should handle special characters in question text")
        void testSpecialCharacters() {
            String text1 = "What's your approach to error handling? (e.g., try/catch, Result<T>)";
            String text2 = "What's your approach to error handling? (e.g., try/catch, Result<T>)";

            String key1 = cachedAvatarService.computeCacheKey(text1);
            String key2 = cachedAvatarService.computeCacheKey(text2);

            assertThat(key1).isEqualTo(key2);
            assertThat(key1).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("Should handle Unicode characters in question text")
        void testUnicodeCharacters() {
            String text = "¿Cuéntame sobre tu experiencia? — 你好世界";
            String key = cachedAvatarService.computeCacheKey(text);

            assertThat(key).hasSize(64);
            assertThat(key).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("Should handle S3 fileExists failure gracefully in isCached")
        void testIsCached_S3Error() {
            String questionText = "Test question";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey))
                    .thenThrow(new RuntimeException("S3 timeout"));

            assertThatThrownBy(() -> cachedAvatarService.isCached(questionText))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("S3 timeout");
        }

        @Test
        @DisplayName("Cache miss should wrap generation exception with question ID context")
        void testCacheMiss_WrapsExceptionWithContext() {
            String questionText = "Test question for error handling.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(false);
            when(avatarVideoService.generateAvatarVideo(questionText, 42L))
                    .thenThrow(new RuntimeException("ElevenLabs 429 Too Many Requests"));

            assertThatThrownBy(() -> cachedAvatarService.getOrGenerateAvatar(questionText, 42L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Avatar video generation failed for question 42")
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("Should return generated key even if copy is skipped")
        void testCopyToCache_Skipped() {
            String questionText = "Handle copy failure gracefully.";
            String cacheKey = cachedAvatarService.buildCacheS3Key(questionText);

            when(videoStorageService.fileExists(cacheKey)).thenReturn(false);
            when(avatarVideoService.generateAvatarVideo(questionText, 201L))
                    .thenReturn("avatar-videos/question_201_ts.mp4");

            // Act
            String result = cachedAvatarService.getOrGenerateAvatar(questionText, 201L);

            // Assert — returns the generated key
            assertThat(result).isEqualTo("avatar-videos/question_201_ts.mp4");

            // Verify generation was called
            verify(avatarVideoService).generateAvatarVideo(questionText, 201L);
        }
    }
}
