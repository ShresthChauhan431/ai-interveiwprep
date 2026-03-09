package com.interview.platform.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
// AUDIT-FIX: Unique constraint prevents duplicate response submissions per question (aligns with V4 migration uq_response_question)
@Table(name = "responses", uniqueConstraints = @UniqueConstraint(
        name = "uq_response_question",
        columnNames = {"question_id"}))
public class Response {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_id", nullable = false)
    private Interview interview;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "video_url", nullable = false)
    private String videoUrl;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String transcription;

    @Column(name = "transcription_confidence")
    private Double transcriptionConfidence;

    @Column(name = "video_duration")
    private Integer videoDuration;

    @Column(name = "responded_at", nullable = false, updatable = false)
    private LocalDateTime respondedAt;

    public Response() {
    }

    public Response(Long id, Question question, Interview interview, User user, String videoUrl, String transcription,
            Double transcriptionConfidence, Integer videoDuration, LocalDateTime respondedAt) {
        this.id = id;
        this.question = question;
        this.interview = interview;
        this.user = user;
        this.videoUrl = videoUrl;
        this.transcription = transcription;
        this.transcriptionConfidence = transcriptionConfidence;
        this.videoDuration = videoDuration;
        this.respondedAt = respondedAt;
    }

    @PrePersist
    protected void onCreate() {
        respondedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Question getQuestion() {
        return question;
    }

    public void setQuestion(Question question) {
        this.question = question;
    }

    public Interview getInterview() {
        return interview;
    }

    public void setInterview(Interview interview) {
        this.interview = interview;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public String getTranscription() {
        return transcription;
    }

    public void setTranscription(String transcription) {
        this.transcription = transcription;
    }

    public Double getTranscriptionConfidence() {
        return transcriptionConfidence;
    }

    public void setTranscriptionConfidence(Double transcriptionConfidence) {
        this.transcriptionConfidence = transcriptionConfidence;
    }

    public Integer getVideoDuration() {
        return videoDuration;
    }

    public void setVideoDuration(Integer videoDuration) {
        this.videoDuration = videoDuration;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(LocalDateTime respondedAt) {
        this.respondedAt = respondedAt;
    }
}
