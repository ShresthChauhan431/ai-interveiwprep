package com.interview.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("VideoStorageService Tests (Local Filesystem)")
class VideoStorageServiceTest {

    @TempDir
    Path tempDir;

    private VideoStorageService videoStorageService;

    @BeforeEach
    void setUp() {
        videoStorageService = new VideoStorageService(
                tempDir.toString(), "http://localhost:8080");
    }

    // ============================================================
    // uploadVideo
    // ============================================================

    @Nested
    @DisplayName("uploadVideo")
    class UploadVideoTests {

        private MockMultipartFile videoFile;

        @BeforeEach
        void setUpFile() {
            videoFile = new MockMultipartFile(
                    "video", "response.webm", "video/webm", "fake-video-data".getBytes());
        }

        @Test
        @DisplayName("Should upload with correct key pattern and return storage key (not URL)")
        void testUploadVideo_Success() {
            String result = videoStorageService.uploadVideo(videoFile, 1L, 100L, 200L);

            assertThat(result).startsWith("interviews/1/100/response_200_");
            assertThat(result).endsWith(".webm");
            assertThat(result).doesNotContain("http");

            // Verify file actually exists on disk
            Path filePath = tempDir.resolve(result);
            assertThat(filePath).exists();
            assertThat(filePath).hasContent("fake-video-data");
        }
    }

    // ============================================================
    // uploadResumeFile
    // ============================================================

    @Nested
    @DisplayName("uploadResumeFile")
    class UploadResumeFileTests {

        @Test
        @DisplayName("Should upload resume with correct key and extension")
        void testUploadResumeFile_Success() {
            MockMultipartFile resumeFile = new MockMultipartFile(
                    "resume", "my_resume.pdf", "application/pdf", "pdf-contents".getBytes());

            String result = videoStorageService.uploadResumeFile(resumeFile, 1L);

            assertThat(result).startsWith("resumes/1/resume_");
            assertThat(result).endsWith(".pdf");
            assertThat(result).doesNotContain("http");

            // Verify file exists
            assertThat(tempDir.resolve(result)).exists();
        }

        @Test
        @DisplayName("Should default to .pdf extension when filename has no extension")
        void testUploadResumeFile_NoExtension() {
            MockMultipartFile resumeFile = new MockMultipartFile(
                    "resume", "resume_no_ext", "application/pdf", "pdf-contents".getBytes());

            String result = videoStorageService.uploadResumeFile(resumeFile, 1L);

            assertThat(result).endsWith(".pdf");
            assertThat(tempDir.resolve(result)).exists();
        }
    }

    // ============================================================
    // uploadBytes
    // ============================================================

    @Nested
    @DisplayName("uploadBytes")
    class UploadBytesTests {

        @Test
        @DisplayName("Should upload raw bytes and return storage key")
        void testUploadBytes_Success() {
            byte[] audioData = "fake-audio-data".getBytes();

            String result = videoStorageService.uploadBytes(audioData, "audio/test.mp3", "audio/mpeg");

            assertThat(result).isEqualTo("audio/test.mp3");

            Path filePath = tempDir.resolve("audio/test.mp3");
            assertThat(filePath).exists();
            assertThat(filePath).hasBinaryContent(audioData);
        }
    }

    // ============================================================
    // generatePresignedGetUrl
    // ============================================================

    @Nested
    @DisplayName("generatePresignedGetUrl")
    class GeneratePresignedGetUrlTests {

        @Test
        @DisplayName("Should generate local HTTP URL with correct path")
        void testGeneratePresignedGetUrl_Success() {
            String url = videoStorageService.generatePresignedGetUrl("some/key");

            assertThat(url).isEqualTo("http://localhost:8080/api/files/some/key");
        }

        @Test
        @DisplayName("Should accept custom duration (ignored, returns same URL)")
        void testGeneratePresignedGetUrl_CustomDuration() {
            String url = videoStorageService.generatePresignedGetUrl("some/key", 30);

            assertThat(url).isEqualTo("http://localhost:8080/api/files/some/key");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for null key")
        void testGeneratePresignedGetUrl_NullKey() {
            assertThatThrownBy(() -> videoStorageService.generatePresignedGetUrl(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for blank key")
        void testGeneratePresignedGetUrl_BlankKey() {
            assertThatThrownBy(() -> videoStorageService.generatePresignedGetUrl("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null or blank");
        }
    }

    // ============================================================
    // generatePresignedPutUrl
    // ============================================================

    @Nested
    @DisplayName("generatePresignedPutUrl")
    class GeneratePresignedPutUrlTests {

        @Test
        @DisplayName("Should generate local upload URL with correct path")
        void testGeneratePresignedPutUrl_Success() {
            String url = videoStorageService.generatePresignedPutUrl(
                    "interviews/1/100/response.webm", "video/webm");

            assertThat(url).isEqualTo("http://localhost:8080/api/files/upload-raw/interviews/1/100/response.webm");
        }
    }

    // ============================================================
    // buildVideoResponseKey
    // ============================================================

    @Nested
    @DisplayName("buildVideoResponseKey")
    class BuildVideoResponseKeyTests {

        @Test
        @DisplayName("Should build key with correct path pattern")
        void testBuildVideoResponseKey_Format() {
            String key = videoStorageService.buildVideoResponseKey(1L, 100L, 200L);

            assertThat(key).startsWith("interviews/1/100/response_200_");
            assertThat(key).endsWith(".webm");
            assertThat(key).matches("interviews/1/100/response_200_\\d+\\.webm");
        }

        @Test
        @DisplayName("Should generate unique keys on successive calls")
        void testBuildVideoResponseKey_UniqueKeys() {
            String key1 = videoStorageService.buildVideoResponseKey(1L, 100L, 200L);
            String key2 = videoStorageService.buildVideoResponseKey(1L, 100L, 200L);

            assertThat(key1).matches("interviews/1/100/response_200_\\d+\\.webm");
            assertThat(key2).matches("interviews/1/100/response_200_\\d+\\.webm");
        }
    }

    // ============================================================
    // deleteFile
    // ============================================================

    @Nested
    @DisplayName("deleteFile")
    class DeleteFileTests {

        @Test
        @DisplayName("Should delete existing file")
        void testDeleteFile_Success() throws IOException {
            // Create a file to delete
            Path filePath = tempDir.resolve("test/file.txt");
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, "test content");

            assertThat(filePath).exists();

            videoStorageService.deleteFile("test/file.txt");

            assertThat(filePath).doesNotExist();
        }

        @Test
        @DisplayName("Should not throw when file does not exist (graceful handling)")
        void testDeleteFile_NotFound_NoException() {
            assertThatCode(() -> videoStorageService.deleteFile("nonexistent/key"))
                    .doesNotThrowAnyException();
        }
    }

    // ============================================================
    // fileExists
    // ============================================================

    @Nested
    @DisplayName("fileExists")
    class FileExistsTests {

        @Test
        @DisplayName("Should return true when file exists")
        void testFileExists_True() throws IOException {
            Path filePath = tempDir.resolve("existing/file.txt");
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, "content");

            assertThat(videoStorageService.fileExists("existing/file.txt")).isTrue();
        }

        @Test
        @DisplayName("Should return false when file does not exist")
        void testFileExists_False() {
            assertThat(videoStorageService.fileExists("missing/key")).isFalse();
        }
    }

}
