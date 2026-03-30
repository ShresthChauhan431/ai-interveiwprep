package com.interview.platform.repository;

import com.interview.platform.model.Response;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResponseRepository extends JpaRepository<Response, Long> {

    List<Response> findByInterviewId(Long interviewId);

    @Query("SELECT r FROM Response r JOIN FETCH r.question WHERE r.interview.id = :interviewId ORDER BY r.question.questionNumber")
    List<Response> findByInterviewIdOrderByQuestionId(Long interviewId);

    Optional<Response> findByQuestionId(Long questionId);

    Optional<Response> findByVideoUrl(String videoUrl);
}
