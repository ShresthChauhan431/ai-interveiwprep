package com.interview.platform.dto;

import java.util.List;

/**
 * DTO representing resume analysis feedback.
 */
public class ResumeAnalysisDTO {

    private int score;
    private List<String> strengths;
    private List<String> weaknesses;
    private List<String> suggestions;
    private String overallFeedback;
    private String resumeFileName;

    public ResumeAnalysisDTO() {
    }

    public ResumeAnalysisDTO(int score, List<String> strengths, List<String> weaknesses,
            List<String> suggestions, String overallFeedback, String resumeFileName) {
        this.score = score;
        this.strengths = strengths;
        this.weaknesses = weaknesses;
        this.suggestions = suggestions;
        this.overallFeedback = overallFeedback;
        this.resumeFileName = resumeFileName;
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

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public String getOverallFeedback() {
        return overallFeedback;
    }

    public void setOverallFeedback(String overallFeedback) {
        this.overallFeedback = overallFeedback;
    }

    public String getResumeFileName() {
        return resumeFileName;
    }

    public void setResumeFileName(String resumeFileName) {
        this.resumeFileName = resumeFileName;
    }
}
