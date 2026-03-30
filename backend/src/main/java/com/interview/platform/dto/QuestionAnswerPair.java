package com.interview.platform.dto;

/**
 * Lightweight DTO representing a question-answer pair for hybrid question generation.
 *
 * <p>Used to pass the interview history (previous Q&A pairs) to Ollama when
 * generating the next adaptive question in the dynamic zone.</p>
 *
 * <p>This is distinct from {@link QuestionAnswerDTO} which includes idealAnswer
 * and is used for the feedback screen.</p>
 *
 * @see com.interview.platform.service.OllamaService#generateNextAdaptiveQuestion
 */
public class QuestionAnswerPair {

    private int questionNumber;
    private String question;
    private String answer;

    public QuestionAnswerPair() {
    }

    public QuestionAnswerPair(int questionNumber, String question, String answer) {
        this.questionNumber = questionNumber;
        this.question = question;
        this.answer = answer;
    }

    public int getQuestionNumber() {
        return questionNumber;
    }

    public void setQuestionNumber(int questionNumber) {
        this.questionNumber = questionNumber;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    @Override
    public String toString() {
        return "QuestionAnswerPair{" +
                "questionNumber=" + questionNumber +
                ", question='" + (question != null && question.length() > 50 
                    ? question.substring(0, 50) + "..." : question) + '\'' +
                ", answer='" + (answer != null && answer.length() > 50 
                    ? answer.substring(0, 50) + "..." : answer) + '\'' +
                '}';
    }
}
