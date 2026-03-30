package com.interview.platform.dto;

/**
 * DTO returned after submitting an answer during a hybrid interview.
 *
 * <p>Contains the next question (if any), its audio URL, and metadata about
 * the interview progress. The frontend uses this to transition to the next
 * question without a full page reload.</p>
 *
 * @see com.interview.platform.controller.InterviewController#submitAnswerAndGetNext
 */
public class NextQuestionResponseDTO {

    /**
     * The ID of the next question, or null if interview is complete.
     */
    private Long nextQuestionId;

    /**
     * The text of the next question, or null if interview is complete.
     */
    private String nextQuestionText;

    /**
     * The 1-based question number (e.g., 3 of 5).
     */
    private Integer nextQuestionNumber;

    /**
     * URL to the pre-generated TTS audio for the next question.
     * May be null briefly while audio is being generated for dynamic questions.
     */
    private String nextQuestionAudioUrl;

    /**
     * Category of the next question (TECHNICAL, BEHAVIORAL, GENERAL).
     */
    private String nextQuestionCategory;

    /**
     * Difficulty of the next question (EASY, MEDIUM, HARD).
     */
    private String nextQuestionDifficulty;

    /**
     * How the question was generated: "PRE_GENERATED" or "DYNAMIC".
     */
    private String generationMode;

    /**
     * Total number of questions in this interview.
     */
    private int totalQuestions;

    /**
     * True if this was the last question and the interview is now complete.
     */
    private boolean interviewComplete;

    /**
     * Optional message (e.g., "Interview complete! Generating feedback...")
     */
    private String message;

    public NextQuestionResponseDTO() {
    }

    // Builder-style setters for fluent construction

    public NextQuestionResponseDTO nextQuestionId(Long nextQuestionId) {
        this.nextQuestionId = nextQuestionId;
        return this;
    }

    public NextQuestionResponseDTO nextQuestionText(String nextQuestionText) {
        this.nextQuestionText = nextQuestionText;
        return this;
    }

    public NextQuestionResponseDTO nextQuestionNumber(Integer nextQuestionNumber) {
        this.nextQuestionNumber = nextQuestionNumber;
        return this;
    }

    public NextQuestionResponseDTO nextQuestionAudioUrl(String nextQuestionAudioUrl) {
        this.nextQuestionAudioUrl = nextQuestionAudioUrl;
        return this;
    }

    public NextQuestionResponseDTO nextQuestionCategory(String nextQuestionCategory) {
        this.nextQuestionCategory = nextQuestionCategory;
        return this;
    }

    public NextQuestionResponseDTO nextQuestionDifficulty(String nextQuestionDifficulty) {
        this.nextQuestionDifficulty = nextQuestionDifficulty;
        return this;
    }

    public NextQuestionResponseDTO generationMode(String generationMode) {
        this.generationMode = generationMode;
        return this;
    }

    public NextQuestionResponseDTO totalQuestions(int totalQuestions) {
        this.totalQuestions = totalQuestions;
        return this;
    }

    public NextQuestionResponseDTO interviewComplete(boolean interviewComplete) {
        this.interviewComplete = interviewComplete;
        return this;
    }

    public NextQuestionResponseDTO message(String message) {
        this.message = message;
        return this;
    }

    // Standard getters and setters

    public Long getNextQuestionId() {
        return nextQuestionId;
    }

    public void setNextQuestionId(Long nextQuestionId) {
        this.nextQuestionId = nextQuestionId;
    }

    public String getNextQuestionText() {
        return nextQuestionText;
    }

    public void setNextQuestionText(String nextQuestionText) {
        this.nextQuestionText = nextQuestionText;
    }

    public Integer getNextQuestionNumber() {
        return nextQuestionNumber;
    }

    public void setNextQuestionNumber(Integer nextQuestionNumber) {
        this.nextQuestionNumber = nextQuestionNumber;
    }

    public String getNextQuestionAudioUrl() {
        return nextQuestionAudioUrl;
    }

    public void setNextQuestionAudioUrl(String nextQuestionAudioUrl) {
        this.nextQuestionAudioUrl = nextQuestionAudioUrl;
    }

    public String getNextQuestionCategory() {
        return nextQuestionCategory;
    }

    public void setNextQuestionCategory(String nextQuestionCategory) {
        this.nextQuestionCategory = nextQuestionCategory;
    }

    public String getNextQuestionDifficulty() {
        return nextQuestionDifficulty;
    }

    public void setNextQuestionDifficulty(String nextQuestionDifficulty) {
        this.nextQuestionDifficulty = nextQuestionDifficulty;
    }

    public String getGenerationMode() {
        return generationMode;
    }

    public void setGenerationMode(String generationMode) {
        this.generationMode = generationMode;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(int totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public boolean isInterviewComplete() {
        return interviewComplete;
    }

    public void setInterviewComplete(boolean interviewComplete) {
        this.interviewComplete = interviewComplete;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * Factory method for creating a response indicating interview completion.
     */
    public static NextQuestionResponseDTO completed(int totalQuestions) {
        return new NextQuestionResponseDTO()
                .interviewComplete(true)
                .totalQuestions(totalQuestions)
                .message("Interview complete! Generating feedback...");
    }
}
