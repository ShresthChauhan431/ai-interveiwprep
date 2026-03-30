package com.interview.platform.controller;

import com.interview.platform.dto.ResumeAnalysisDTO;
import com.interview.platform.dto.ResumeResponse;
import com.interview.platform.model.Resume;
import com.interview.platform.repository.ResumeRepository;
import com.interview.platform.service.ResumeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;
    private final ResumeRepository resumeRepository;

    public ResumeController(ResumeService resumeService, ResumeRepository resumeRepository) {
        this.resumeService = resumeService;
        this.resumeRepository = resumeRepository;
    }

    // AUDIT-FIX (Section 9b): Added GET /api/resumes for listing all user's resumes with ownership filter
    /**
     * List all resumes belonging to the authenticated user.
     *
     * @param request the HTTP request (userId extracted from JWT)
     * @return list of resume summaries for the current user
     */
    @GetMapping
    public ResponseEntity<List<ResumeResponse>> listResumes(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        List<Resume> resumes = resumeRepository.findByUserId(userId);
        List<ResumeResponse> responses = resumes.stream()
                .map(resume -> ResumeResponse.builder()
                        .id(resume.getId())
                        .fileName(resume.getFileName())
                        .fileUrl(resume.getFileUrl())
                        .uploadedAt(resume.getUploadedAt())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/upload")
    public ResponseEntity<ResumeResponse> uploadResume(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        ResumeResponse response = resumeService.uploadResume(file, userId);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/my-resume")
    public ResponseEntity<ResumeResponse> getMyResume(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        ResumeResponse response = resumeService.getLatestResume(userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResumeResponse> getResumeById(
            @PathVariable Long id,
            HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        ResumeResponse response = resumeService.getResumeById(id, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a resume by ID (P2-11).
     *
     * <p>Validates ownership before deleting. Removes both the database
     * entity and the physical file from storage to prevent orphaned files.</p>
     *
     * @param id      the resume ID to delete
     * @param request the HTTP request (userId extracted from JWT)
     * @return 204 No Content on success
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResume(
            @PathVariable Long id,
            HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        resumeService.deleteResume(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Analyze the user's latest resume and provide feedback.
     */
    @GetMapping("/analyze")
    public ResponseEntity<ResumeAnalysisDTO> analyzeResume(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        ResumeAnalysisDTO analysis = resumeService.analyzeResume(userId);
        return ResponseEntity.ok(analysis);
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr == null) {
            throw new RuntimeException("User not authenticated");
        }
        return (Long) userIdAttr;
    }
}
