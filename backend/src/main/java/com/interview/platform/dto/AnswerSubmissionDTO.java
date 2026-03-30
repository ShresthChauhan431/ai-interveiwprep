package com.interview.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for submitting a user's answer to a question during a hybrid interview.
 *
 * <p>The frontend sends this payload when the user completes their answer.
 * The backend records the answer and (if in the dynamic zone) generates
 * the next adaptive question.</p>
 *
 * @see com.interview.platform.controller.InterviewController#submitAnswerAndGetNext
 */
public class AnswerSubmissionDTO {

    /**
     * The transcribed text of the user's spoken answer.
     * May be empty if transcription failed, but should not be null.
     */
    @NotNull(message = "Answer transcript cannot be null")
    private String answerTranscript;

    /**
     * URL to the recorded audio/video file of the answer (optional).
     * Used for later review and feedback generation.
     */
    private String answerVideoUrl;

    /**
     * Duration of the answer in seconds (optional).
     * Useful for analytics and pacing feedback.
     */
    private Integer durationSeconds;

    public AnswerSubmissionDTO() {
    }

    public AnswerSubmissionDTO(String answerTranscript, String answerVideoUrl, Integer durationSeconds) {
        this.answerTranscript = answerTranscript;
        this.answerVideoUrl = answerVideoUrl;
        this.durationSeconds = durationSeconds;
    }

    public String getAnswerTranscript() {
        return answerTranscript;
    }

    public void setAnswerTranscript(String answerTranscript) {
        this.answerTranscript = answerTranscript;
    }

    public String getAnswerVideoUrl() {
        return answerVideoUrl;
    }

    public void setAnswerVideoUrl(String answerVideoUrl) {
        this.answerVideoUrl = answerVideoUrl;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    @Override
    public String toString() {
        return "AnswerSubmissionDTO{" +
                "answerTranscript='" + (answerTranscript != null && answerTranscript.length() > 50
                    ? answerTranscript.substring(0, 50) + "..." : answerTranscript) + '\'' +
                ", answerVideoUrl='" + answerVideoUrl + '\'' +
                ", durationSeconds=" + durationSeconds +
                '}';
    }
}
