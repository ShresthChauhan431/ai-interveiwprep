package com.interview.platform.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "interviews", indexes = {
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_status", columnList = "status")
})
public class Interview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_role_id", nullable = false)
    private JobRole jobRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private InterviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, columnDefinition = "VARCHAR(10)")
    private InterviewType type;

    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "interview", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Question> questions = new ArrayList<>();

    @OneToMany(mappedBy = "interview", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Response> responses = new ArrayList<>();

    @OneToOne(mappedBy = "interview", cascade = CascadeType.ALL, orphanRemoval = true)
    private Feedback feedback;

    public Interview() {
    }

    public Interview(Long id, User user, Resume resume, JobRole jobRole, InterviewStatus status, InterviewType type,
            Integer overallScore, LocalDateTime startedAt, LocalDateTime completedAt) {
        this.id = id;
        this.user = user;
        this.resume = resume;
        this.jobRole = jobRole;
        this.status = status;
        this.type = type;
        this.overallScore = overallScore;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
        if (status == null) {
            status = InterviewStatus.IN_PROGRESS;
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Resume getResume() {
        return resume;
    }

    public void setResume(Resume resume) {
        this.resume = resume;
    }

    public JobRole getJobRole() {
        return jobRole;
    }

    public void setJobRole(JobRole jobRole) {
        this.jobRole = jobRole;
    }

    public InterviewStatus getStatus() {
        return status;
    }

    public void setStatus(InterviewStatus status) {
        this.status = status;
    }

    public InterviewType getType() {
        return type;
    }

    public void setType(InterviewType type) {
        this.type = type;
    }

    public Integer getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public void setQuestions(List<Question> questions) {
        this.questions = questions;
    }

    public List<Response> getResponses() {
        return responses;
    }

    public void setResponses(List<Response> responses) {
        this.responses = responses;
    }

    public Feedback getFeedback() {
        return feedback;
    }

    public void setFeedback(Feedback feedback) {
        this.feedback = feedback;
    }
}
