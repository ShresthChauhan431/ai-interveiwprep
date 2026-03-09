package com.interview.platform.repository;

import com.interview.platform.model.Interview;
import com.interview.platform.model.InterviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewRepository extends JpaRepository<Interview, Long> {

    List<Interview> findByUserId(Long userId);

    /**
     * Paginated interview history query, ordered by startedAt descending (P1-2).
     *
     * <p>Replaces unbounded {@link #findByUserId(Long)} for the interview
     * history endpoint. A power user or automated abuse could create thousands
     * of interviews; without pagination the full list would be loaded into
     * memory, risking OOM.</p>
     *
     * @param userId   the user whose interviews to fetch
     * @param pageable pagination and sorting parameters
     * @return a page of interviews
     */
    Page<Interview> findByUserIdOrderByStartedAtDesc(Long userId, Pageable pageable);

    List<Interview> findByUserIdAndStatus(Long userId, InterviewStatus status);

    Optional<Interview> findByIdAndUserId(Long id, Long userId);

    /**
     * Count the total number of interviews for a user (P2-9).
     *
     * <p>Replaces the previous approach of loading the entire interview list
     * into memory just to call {@code .size()}. This generates a
     * {@code SELECT COUNT(*)} query instead of fetching all entities.</p>
     *
     * @param userId the user whose interviews to count
     * @return the number of interviews belonging to the user
     */
    long countByUserId(Long userId);

    /**
     * Find interviews stuck in a given status since before the cutoff time.
     *
     * <p>Used by the P1 scheduled recovery task to detect interviews that have
     * been in {@code GENERATING_VIDEOS} or {@code PROCESSING} for too long
     * (e.g., due to a server restart losing async event listeners, or a
     * circuit breaker staying open). The recovery task transitions them to
     * {@code IN_PROGRESS} (with text-only fallback) or {@code FAILED}.</p>
     *
     * @param status the status to search for (e.g., GENERATING_VIDEOS)
     * @param before interviews with startedAt before this time are considered stuck
     * @return list of stuck interviews ordered by startedAt ascending
     */
    List<Interview> findByStatusAndStartedAtBeforeOrderByStartedAtAsc(
            InterviewStatus status, LocalDateTime before);
}
