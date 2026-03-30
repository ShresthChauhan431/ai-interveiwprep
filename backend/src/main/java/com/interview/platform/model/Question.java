package com.interview.platform.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "questions")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_id", nullable = false)
    private Interview interview;

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    private String questionText;

    @Column(nullable = false)
    private Integer questionNumber;

    @Column(length = 50)
    private String category;

    @Column(length = 20)
    private String difficulty;

    @Column(name = "avatar_video_url")
    private String avatarVideoUrl;

    @Column(name = "audio_url") // FIX: ElevenLabs TTS audio URL field replacing D-ID avatar video generation
    private String audioUrl;

    /**
     * How this question was generated:
     * - "PRE_GENERATED": Generated before interview started (in the pre-gen zone)
     * - "DYNAMIC": Generated dynamically during the interview based on previous answers
     */
    @Column(name = "generation_mode", length = 20)
    private String generationMode;

    /**
     * For dynamically generated questions, the ID of the question whose answer triggered this generation.
     * Null for pre-generated questions.
     */
    @Column(name = "generated_after_question_id")
    private Long generatedAfterQuestionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Question() {
    }

    public Question(Long id, Interview interview, String questionText, Integer questionNumber, String category,
            String difficulty, String avatarVideoUrl, LocalDateTime createdAt) {
        this.id = id;
        this.interview = interview;
        this.questionText = questionText;
        this.questionNumber = questionNumber;
        this.category = category;
        this.difficulty = difficulty;
        this.avatarVideoUrl = avatarVideoUrl;
        this.createdAt = createdAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Interview getInterview() {
        return interview;
    }

    public void setInterview(Interview interview) {
        this.interview = interview;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public Integer getQuestionNumber() {
        return questionNumber;
    }

    public void setQuestionNumber(Integer questionNumber) {
        this.questionNumber = questionNumber;
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

    public String getAudioUrl() { // FIX: Getter for ElevenLabs TTS audio URL
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) { // FIX: Setter for ElevenLabs TTS audio URL
        this.audioUrl = audioUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getGenerationMode() {
        return generationMode;
    }

    public void setGenerationMode(String generationMode) {
        this.generationMode = generationMode;
    }

    public Long getGeneratedAfterQuestionId() {
        return generatedAfterQuestionId;
    }

    public void setGeneratedAfterQuestionId(Long generatedAfterQuestionId) {
        this.generatedAfterQuestionId = generatedAfterQuestionId;
    }
}
