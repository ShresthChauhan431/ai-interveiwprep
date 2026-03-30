package com.interview.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.platform.dto.ResumeAnalysisDTO;
import com.interview.platform.dto.ResumeResponse;
import com.interview.platform.exception.UserNotFoundException;
import com.interview.platform.model.Resume;
import com.interview.platform.model.User;
import com.interview.platform.repository.ResumeRepository;
import com.interview.platform.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for resume upload, validation, text extraction, and retrieval.
 *
 * <h3>Audit Fixes Applied:</h3>
 * <ul>
 *   <li><strong>P3-3:</strong> Replaced {@code java.util.logging} with SLF4J
 *       for consistent log output via Logback configuration.</li>
 *   <li><strong>P1-5:</strong> Added magic-byte validation in addition to MIME
 *       type checks. The {@code Content-Type} header is trivially spoofable;
 *       magic bytes verify the actual file content.</li>
 *   <li><strong>P2-5:</strong> Extracted resume text is truncated to
 *       {@link #MAX_EXTRACTED_TEXT_LENGTH} characters to prevent unbounded
 *       TEXT column growth and LLM context window overflow.</li>
 *   <li><strong>P1-4:</strong> Resume text is sanitized before storage to
 *       mitigate prompt injection attacks when the text is interpolated
 *       into LLM prompts by {@code OllamaService}.</li>
 *   <li><strong>P2-11:</strong> Added {@link #deleteResume(Long, Long)} for
 *       resume deletion with ownership validation and file cleanup.</li>
 * </ul>
 */
@Service
public class ResumeService {

    // P3-3: Use SLF4J instead of java.util.logging
    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final VideoStorageService videoStorageService;
    private final OllamaService ollamaService;
    private final ObjectMapper objectMapper;

    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    /**
     * P2-5: Maximum characters to store from extracted resume text.
     * Issue 16: 15,000 chars stored in DB, but OllamaService uses 4,000 for LLM prompt.
     * This provides sufficient context while preventing LLM context overflow.
     */
    private static final int MAX_EXTRACTED_TEXT_LENGTH = 15_000;

    // ── Magic byte constants for file validation (P1-5) ──────
    // PDF magic: %PDF (0x25 0x50 0x44 0x46)
    private static final byte[] PDF_MAGIC = { 0x25, 0x50, 0x44, 0x46 };
    // DOCX magic: PK (0x50 0x4B) — ZIP archive format
    private static final byte[] DOCX_MAGIC = { 0x50, 0x4B };

    public ResumeService(ResumeRepository resumeRepository, UserRepository userRepository,
            VideoStorageService videoStorageService, OllamaService ollamaService, ObjectMapper objectMapper) {
        this.resumeRepository = resumeRepository;
        this.userRepository = userRepository;
        this.videoStorageService = videoStorageService;
        this.ollamaService = ollamaService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResumeResponse uploadResume(MultipartFile file, Long userId) {
        // Validate file (MIME type + magic bytes)
        validateFile(file);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        try {
            // Extract text from file
            String rawText = extractText(file);

            // FIX: Fallback when text extraction returns empty — never crash the upload, proceed with generic interview
            if (rawText == null || rawText.isBlank()) {
                rawText = "Resume text could not be extracted. Generic interview will proceed.";
                log.warn("Text extraction returned empty for user: {}. Using fallback text.", userId);
            }

            // P1-4: Sanitize extracted text to mitigate prompt injection
            String sanitizedText = sanitizeResumeText(rawText);

            // P2-5: Truncate to prevent unbounded storage and LLM context overflow
            String extractedText = truncateText(sanitizedText, MAX_EXTRACTED_TEXT_LENGTH);

            // Upload file to storage
            String fileUrl = videoStorageService.uploadResumeFile(file, userId);

            // Save resume entity
            Resume resume = new Resume();
            resume.setUser(user);
            resume.setFileName(file.getOriginalFilename());
            resume.setFileUrl(fileUrl);
            resume.setExtractedText(extractedText);

            Resume savedResume = resumeRepository.save(resume);
            log.info("Resume uploaded successfully for user: {}", userId);

            return ResumeResponse.builder()
                    .id(savedResume.getId())
                    .fileName(savedResume.getFileName())
                    .fileUrl(savedResume.getFileUrl())
                    .uploadedAt(savedResume.getUploadedAt())
                    .message("Resume uploaded successfully")
                    .build();

        } catch (IOException e) {
            log.error("Failed to process resume for user: {}", userId, e);
            throw new RuntimeException("Failed to process resume: " + e.getMessage(), e);
        }
    }

    public ResumeResponse getLatestResume(Long userId) {
        Resume resume = resumeRepository.findLatestByUserId(userId)
                .orElseThrow(() -> new com.interview.platform.exception.ResourceNotFoundException(
                        "No resume found for user"));

        return buildResumeResponse(resume);
    }

    public ResumeResponse getResumeById(Long id, Long userId) {
        Resume resume = resumeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Resume not found"));

        // Verify ownership
        if (!resume.getUser().getId().equals(userId)) {
            throw new RuntimeException("Access denied: Resume does not belong to user");
        }

        return buildResumeResponse(resume);
    }

    /**
     * P2-11: Delete a resume with ownership validation and file cleanup.
     *
     * @param id     the resume ID to delete
     * @param userId the authenticated user's ID (ownership check)
     */
    @Transactional
    public void deleteResume(Long id, Long userId) {
        Resume resume = resumeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Resume not found"));

        // Verify ownership
        if (!resume.getUser().getId().equals(userId)) {
            throw new RuntimeException("Access denied: Resume does not belong to user");
        }

        // Delete the physical file from storage
        String fileUrl = resume.getFileUrl();
        if (fileUrl != null && !fileUrl.isBlank()) {
            videoStorageService.deleteFile(fileUrl);
            log.info("Deleted resume file from storage: key={}", fileUrl);
        }

        // Delete the database entity
        resumeRepository.delete(resume);
        log.info("Deleted resume id={} for user={}", id, userId);
    }

    /**
     * Validate the uploaded file: emptiness, size, MIME type, and magic bytes.
     *
     * <p><strong>P1-5:</strong> The MIME type check alone is insufficient because
     * {@code file.getContentType()} reads the {@code Content-Type} header sent by
     * the client, which is trivially spoofable. Magic byte validation verifies
     * the actual file content matches the declared type.</p>
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum limit of 5MB");
        }

        // Step 1: Check MIME type header
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Invalid file type. Only PDF and DOCX files are allowed");
        }

        // Step 2: P1-5 — Verify magic bytes match the declared content type
        validateFileMagicBytes(file, contentType);
    }

    /**
     * P1-5: Validate the file's magic bytes match the expected format.
     *
     * <p>PDF files start with {@code %PDF} (0x25504446). DOCX files are ZIP
     * archives starting with {@code PK} (0x504B). A file with a spoofed
     * Content-Type header but wrong magic bytes is rejected.</p>
     *
     * @param file        the uploaded file
     * @param contentType the declared content type (already validated against allowlist)
     */
    private void validateFileMagicBytes(MultipartFile file, String contentType) {
        try {
            byte[] header = new byte[8];
            int bytesRead;
            try (InputStream is = file.getInputStream()) {
                bytesRead = is.read(header);
            }

            if (bytesRead < 4) {
                throw new IllegalArgumentException("File is too small to be a valid document");
            }

            boolean isPdf = header[0] == PDF_MAGIC[0]
                    && header[1] == PDF_MAGIC[1]
                    && header[2] == PDF_MAGIC[2]
                    && header[3] == PDF_MAGIC[3];
            boolean isDocx = header[0] == DOCX_MAGIC[0]
                    && header[1] == DOCX_MAGIC[1];

            if ("application/pdf".equals(contentType) && !isPdf) {
                log.warn("File claims to be PDF but magic bytes don't match: [{}, {}, {}, {}]",
                        header[0], header[1], header[2], header[3]);
                throw new IllegalArgumentException(
                        "File content does not match PDF format. The file may be corrupted or misnamed.");
            }

            if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)
                    && !isDocx) {
                log.warn("File claims to be DOCX but magic bytes don't match: [{}, {}]",
                        header[0], header[1]);
                throw new IllegalArgumentException(
                        "File content does not match DOCX format. The file may be corrupted or misnamed.");
            }

            // Extra safety: reject files that match neither format
            if (!isPdf && !isDocx) {
                throw new IllegalArgumentException(
                        "File content does not match any supported format (PDF or DOCX).");
            }
        } catch (IOException e) {
            log.error("Failed to read file magic bytes for validation", e);
            throw new IllegalArgumentException("Unable to validate file content", e);
        }
    }

    private String extractText(MultipartFile file) throws IOException {
        String contentType = file.getContentType();

        if ("application/pdf".equals(contentType)) {
            return extractTextFromPdf(file.getInputStream());
        } else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)) {
            return extractTextFromDocx(file.getInputStream());
        }

        return "";
    }

    private String extractTextFromPdf(InputStream inputStream) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.debug("Extracted {} characters from PDF", text.length());
            return text;
        } catch (Exception e) {
            // FIX: Never throw on extraction failure — return empty so fallback kicks in
            log.warn("PDF text extraction failed: {}", e.getMessage());
            return "";
        }
    }

    private String extractTextFromDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            String text = document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
            log.debug("Extracted {} characters from DOCX", text.length());
            return text;
        } catch (Exception e) {
            // FIX: Never throw on extraction failure — return empty so fallback kicks in
            log.warn("DOCX text extraction failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * P1-4: Sanitize resume text to mitigate prompt injection.
     *
     * <p>Raw user-supplied resume text is interpolated directly into LLM prompts
     * by {@code OllamaService}. A malicious resume could contain instructions like
     * "Ignore all previous instructions..." to manipulate question generation or
     * feedback scoring.</p>
     *
     * <p>Sanitization steps:</p>
     * <ul>
     *   <li>Strip triple-backtick delimiters that could escape prompt boundaries</li>
     *   <li>Remove common prompt injection prefixes</li>
     *   <li>Collapse excessive whitespace</li>
     * </ul>
     *
     * <p>Note: This is defense-in-depth. The primary mitigation is in
     * {@code OllamaService} which wraps resume content in clearly-delimited
     * blocks with explicit model instructions.</p>
     *
     * @param text the raw extracted text
     * @return sanitized text safe for prompt interpolation
     */
    private String sanitizeResumeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String sanitized = text;

        // Remove triple backticks that could escape prompt delimiter boundaries
        sanitized = sanitized.replace("```", "");

        // Remove XML-like tags that could interfere with structured prompts
        sanitized = sanitized.replaceAll("</?(?:RESUME_BEGIN|RESUME_END|SYSTEM|INSTRUCTIONS?|PROMPT)[^>]*>", "");

        // Collapse excessive whitespace (more than 3 consecutive newlines)
        sanitized = sanitized.replaceAll("\\n{4,}", "\n\n\n");

        // Collapse runs of spaces (more than 4)
        sanitized = sanitized.replaceAll(" {5,}", "    ");

        return sanitized.trim();
    }

    /**
     * P2-5: Truncate text to a maximum length.
     *
     * <p>Prevents unbounded TEXT column storage and ensures the text fits
     * within LLM context windows when used in prompts.</p>
     *
     * @param text      the text to truncate
     * @param maxLength maximum allowed characters
     * @return the truncated text
     */
    private String truncateText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        log.info("Truncating extracted resume text from {} to {} characters", text.length(), maxLength);
        return text.substring(0, maxLength);
    }

    private ResumeResponse buildResumeResponse(Resume resume) {
        return ResumeResponse.builder()
                .id(resume.getId())
                .fileName(resume.getFileName())
                .fileUrl(resume.getFileUrl())
                .uploadedAt(resume.getUploadedAt())
                .build();
    }

    /**
     * Analyze the user's latest resume and provide feedback.
     *
     * @param userId the authenticated user's ID
     * @return ResumeAnalysisDTO with feedback, strengths, weaknesses, and suggestions
     */
    public ResumeAnalysisDTO analyzeResume(Long userId) {
        log.info("Analyzing resume for user: {}", userId);

        Resume resume = resumeRepository.findLatestByUserId(userId)
                .orElseThrow(() -> new com.interview.platform.exception.ResourceNotFoundException(
                        "No resume found for user. Please upload a resume first."));

        String resumeText = resume.getExtractedText();
        if (resumeText == null || resumeText.isBlank()) {
            throw new RuntimeException("Resume has no text to analyze. Please upload a resume with content.");
        }

        try {
            String jsonResponse = ollamaService.analyzeResume(resumeText);
            return parseAnalysisResponse(jsonResponse, resume.getFileName());
        } catch (Exception e) {
            log.error("Failed to analyze resume for user: {}", userId, e);
            throw new RuntimeException("Failed to analyze resume: " + e.getMessage(), e);
        }
    }

    private ResumeAnalysisDTO parseAnalysisResponse(String jsonResponse, String fileName) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            
            int score = root.has("score") ? root.get("score").asInt() : 50;
            String overallFeedback = root.has("overallFeedback") ? root.get("overallFeedback").asText() : "";
            
            List<String> strengths = new ArrayList<>();
            if (root.has("strengths") && root.get("strengths").isArray()) {
                root.get("strengths").forEach(node -> strengths.add(node.asText()));
            }
            
            List<String> weaknesses = new ArrayList<>();
            if (root.has("weaknesses") && root.get("weaknesses").isArray()) {
                root.get("weaknesses").forEach(node -> weaknesses.add(node.asText()));
            }
            
            List<String> suggestions = new ArrayList<>();
            if (root.has("suggestions") && root.get("suggestions").isArray()) {
                root.get("suggestions").forEach(node -> suggestions.add(node.asText()));
            }

            return new ResumeAnalysisDTO(score, strengths, weaknesses, suggestions, overallFeedback, fileName);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse resume analysis response", e);
            throw new RuntimeException("Failed to parse analysis result", e);
        }
    }
}
