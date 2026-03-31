package com.interview.platform.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for starting a new interview.
 */
public class StartInterviewRequest {

    @NotNull(message = "Resume ID is required")
    private Long resumeId;

    @NotNull(message = "Job Role ID is required")
    private Long jobRoleId;

    private Integer numQuestions; // Optional, defaults handled in service

    private String difficulty; // Optional: EASY, MEDIUM, HARD, or null for AUTO

    public StartInterviewRequest() {
    }

    public StartInterviewRequest(Long resumeId, Long jobRoleId, Integer numQuestions, String difficulty) {
        this.resumeId = resumeId;
        this.jobRoleId = jobRoleId;
        this.numQuestions = numQuestions;
        this.difficulty = difficulty;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public Long getJobRoleId() {
        return jobRoleId;
    }

    public void setJobRoleId(Long jobRoleId) {
        this.jobRoleId = jobRoleId;
    }

    public Integer getNumQuestions() {
        return numQuestions;
    }

    public void setNumQuestions(Integer numQuestions) {
        this.numQuestions = numQuestions;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }
}
