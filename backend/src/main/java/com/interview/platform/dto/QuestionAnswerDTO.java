package com.interview.platform.dto;

/**
 * DTO representing a question and its user's answer for the feedback screen.
 */
public class QuestionAnswerDTO {

    private String questionText;
    private String userAnswer;
    private String idealAnswer;

    public QuestionAnswerDTO() {
    }

    public QuestionAnswerDTO(String questionText, String userAnswer, String idealAnswer) {
        this.questionText = questionText;
        this.userAnswer = userAnswer;
        this.idealAnswer = idealAnswer;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getUserAnswer() {
        return userAnswer;
    }

    public void setUserAnswer(String userAnswer) {
        this.userAnswer = userAnswer;
    }

    public String getIdealAnswer() {
        return idealAnswer;
    }

    public void setIdealAnswer(String idealAnswer) {
        this.idealAnswer = idealAnswer;
    }
}
