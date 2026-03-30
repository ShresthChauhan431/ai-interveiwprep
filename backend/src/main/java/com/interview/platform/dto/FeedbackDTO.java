package com.interview.platform.dto;

import java.util.List;

/**
 * DTO representing AI-generated interview feedback.
 */
public class FeedbackDTO {

    private int score;
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> recommendations;
    private String detailedAnalysis;
    private List<QuestionAnswerDTO> questionAnswers;

    public FeedbackDTO() {
    }

    public FeedbackDTO(int score, List<String> strengths, List<String> weaknesses,
            List<String> recommendations, String detailedAnalysis) {
        this.score = score;
        this.strengths = strengths;
        this.weaknesses = weaknesses;
        this.recommendations = recommendations;
        this.detailedAnalysis = detailedAnalysis;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths;
    }

    public List<String> getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(List<String> weaknesses) {
        this.weaknesses = weaknesses;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations;
    }

    public String getDetailedAnalysis() {
        return detailedAnalysis;
    }

    public void setDetailedAnalysis(String detailedAnalysis) {
        this.detailedAnalysis = detailedAnalysis;
    }

    public List<QuestionAnswerDTO> getQuestionAnswers() {
        return questionAnswers;
    }

    public void setQuestionAnswers(List<QuestionAnswerDTO> questionAnswers) {
        this.questionAnswers = questionAnswers;
    }
}
