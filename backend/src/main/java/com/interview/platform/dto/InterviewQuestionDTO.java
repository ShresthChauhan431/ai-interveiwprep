package com.interview.platform.dto;

/**
 * DTO for individual interview questions (inside InterviewDTO).
 */
public class InterviewQuestionDTO {

    private Long questionId;
    private Integer questionNumber;
    private String questionText;
    private String category;
    private String difficulty;
    private String avatarVideoUrl;
    private boolean answered;
    private String responseVideoUrl;
    private String responseTranscription;

    public InterviewQuestionDTO() {
    }

    // Getters and Setters
    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Integer getQuestionNumber() {
        return questionNumber;
    }

    public void setQuestionNumber(Integer questionNumber) {
        this.questionNumber = questionNumber;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getAvatarVideoUrl() {
        return avatarVideoUrl;
    }

    public void setAvatarVideoUrl(String avatarVideoUrl) {
        this.avatarVideoUrl = avatarVideoUrl;
    }

    public boolean isAnswered() {
        return answered;
    }

    public void setAnswered(boolean answered) {
        this.answered = answered;
    }

    public String getResponseVideoUrl() {
        return responseVideoUrl;
    }

    public void setResponseVideoUrl(String responseVideoUrl) {
        this.responseVideoUrl = responseVideoUrl;
    }

    public String getResponseTranscription() {
        return responseTranscription;
    }

    public void setResponseTranscription(String responseTranscription) {
        this.responseTranscription = responseTranscription;
    }
}
