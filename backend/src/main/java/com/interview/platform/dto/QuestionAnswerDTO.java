package com.interview.platform.dto;

/**
 * DTO representing a question and its user's answer for the feedback screen.
 * 
 * <p>Each question has an individual score (0-100) based on strict evaluation:
 * <ul>
 *   <li>0: No answer provided or completely irrelevant response</li>
 *   <li>1-40: Poor answer - missing key points, off-topic, or very brief</li>
 *   <li>41-60: Average answer - addresses the question but lacks depth</li>
 *   <li>61-80: Good answer - covers main points with reasonable detail</li>
 *   <li>81-100: Excellent answer - comprehensive, well-structured, demonstrates expertise</li>
 * </ul>
 * </p>
 */
public class QuestionAnswerDTO {

    private String questionText;
    private String userAnswer;
    private String idealAnswer;
    private int score; // Per-question score 0-100
    private String feedback; // Brief feedback explaining the score

    public QuestionAnswerDTO() {
    }

    public QuestionAnswerDTO(String questionText, String userAnswer, String idealAnswer) {
        this.questionText = questionText;
        this.userAnswer = userAnswer;
        this.idealAnswer = idealAnswer;
        this.score = 0;
        this.feedback = "";
    }

    public QuestionAnswerDTO(String questionText, String userAnswer, String idealAnswer, int score, String feedback) {
        this.questionText = questionText;
        this.userAnswer = userAnswer;
        this.idealAnswer = idealAnswer;
        this.score = score;
        this.feedback = feedback;
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

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = Math.max(0, Math.min(100, score)); // Clamp to 0-100
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }
}
