package com.interview.platform.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

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

    /**
     * Valid state transitions for the interview lifecycle.
     *
     * <pre>
     * CREATED ──► GENERATING_VIDEOS ──► IN_PROGRESS ──► PROCESSING ──► COMPLETED
     *                    │                    │               │
     *                    └────────────────────┴───────────────┴──────► FAILED
     * </pre>
     */
    private static final Map<InterviewStatus, Set<InterviewStatus>> VALID_TRANSITIONS = Map.of(
            InterviewStatus.CREATED, Set.of(InterviewStatus.GENERATING_VIDEOS, InterviewStatus.FAILED),
            InterviewStatus.GENERATING_VIDEOS, Set.of(InterviewStatus.IN_PROGRESS, InterviewStatus.FAILED),
            InterviewStatus.IN_PROGRESS, Set.of(InterviewStatus.PROCESSING, InterviewStatus.FAILED),
            InterviewStatus.PROCESSING, Set.of(InterviewStatus.COMPLETED, InterviewStatus.FAILED),
            InterviewStatus.COMPLETED, Set.of(),
            InterviewStatus.FAILED, Set.of()
    );

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
        if (status == null) {
            status = InterviewStatus.IN_PROGRESS;
        }
    }

    /**
     * Transition the interview to a new status with state machine enforcement.
     *
     * <p>Validates that the transition is legal according to the state machine
     * before applying it. Throws {@link IllegalStateException} if the transition
     * is invalid, preventing accidental regressions (e.g., COMPLETED → IN_PROGRESS).</p>
     *
     * @param target the desired new status
     * @throws IllegalStateException if the transition is not allowed
     */
    public void transitionTo(InterviewStatus target) {
        Set<InterviewStatus> allowed = VALID_TRANSITIONS.getOrDefault(this.status, Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalStateException(
                    String.format("Invalid interview state transition: %s → %s (allowed: %s)",
                            this.status, target, allowed));
        }
        this.status = target;
    }

    // Getters and Setters
    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

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
