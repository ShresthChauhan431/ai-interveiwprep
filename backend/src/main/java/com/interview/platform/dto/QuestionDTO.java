package com.interview.platform.dto;

public class QuestionDTO {

    private String questionText;
    private String category;
    private String difficulty;
    
    // Response data fields (Fix 3: display bug)
    private String responseVideoUrl;
    private String responseTranscription;
    private boolean answered;

    public QuestionDTO() {
    }

    public QuestionDTO(String questionText, String category, String difficulty) {
        this.questionText = questionText;
        this.category = category;
        this.difficulty = difficulty;
    }

    // Getters and Setters
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

    public boolean isAnswered() {
        return answered;
    }

    public void setAnswered(boolean answered) {
        this.answered = answered;
    }

    @Override
    public String toString() {
        return "QuestionDTO{" +
                "questionText='" + questionText + '\'' +
                ", category='" + category + '\'' +
                ", difficulty='" + difficulty + '\'' +
                ", responseVideoUrl='" + responseVideoUrl + '\'' +
                ", responseTranscription='" + responseTranscription + '\'' +
                ", answered=" + answered +
                '}';
    }
}
