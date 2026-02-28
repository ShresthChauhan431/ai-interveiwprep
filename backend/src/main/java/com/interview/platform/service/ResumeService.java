package com.interview.platform.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class ResumeService {

    private static final Logger logger = Logger.getLogger(ResumeService.class.getName());

    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final VideoStorageService videoStorageService;

    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    public ResumeService(ResumeRepository resumeRepository, UserRepository userRepository,
            VideoStorageService videoStorageService) {
        this.resumeRepository = resumeRepository;
        this.userRepository = userRepository;
        this.videoStorageService = videoStorageService;
    }

    @Transactional
    public ResumeResponse uploadResume(MultipartFile file, Long userId) {
        // Validate file
        validateFile(file);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        try {
            // Extract text from file
            String extractedText = extractText(file);

            // Upload to S3
            String fileUrl = videoStorageService.uploadResumeFile(file, userId);

            // Save resume entity
            Resume resume = new Resume();
            resume.setUser(user);
            resume.setFileName(file.getOriginalFilename());
            resume.setFileUrl(fileUrl);
            resume.setExtractedText(extractedText);

            Resume savedResume = resumeRepository.save(resume);
            logger.info("Resume uploaded successfully for user: " + userId);

            return ResumeResponse.builder()
                    .id(savedResume.getId())
                    .fileName(savedResume.getFileName())
                    .fileUrl(savedResume.getFileUrl())
                    .uploadedAt(savedResume.getUploadedAt())
                    .message("Resume uploaded successfully")
                    .build();

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to process resume for user: " + userId, e);
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

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum limit of 5MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Invalid file type. Only PDF and DOCX files are allowed");
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
            logger.fine("Extracted " + text.length() + " characters from PDF");
            return text;
        }
    }

    private String extractTextFromDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            String text = document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
            logger.fine("Extracted " + text.length() + " characters from DOCX");
            return text;
        }
    }

    private ResumeResponse buildResumeResponse(Resume resume) {
        return ResumeResponse.builder()
                .id(resume.getId())
                .fileName(resume.getFileName())
                .fileUrl(resume.getFileUrl())
                .uploadedAt(resume.getUploadedAt())
                .build();
    }
}
