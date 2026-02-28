package com.interview.platform.controller;

import com.interview.platform.dto.ResumeResponse;
import com.interview.platform.service.ResumeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
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

    private Long getUserIdFromRequest(HttpServletRequest request) {
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr == null) {
            throw new RuntimeException("User not authenticated");
        }
        return (Long) userIdAttr;
    }
}
