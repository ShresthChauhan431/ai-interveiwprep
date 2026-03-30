package com.interview.platform.repository;

import com.interview.platform.model.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuestionRepository extends JpaRepository<Question, Long> {

    List<Question> findByInterviewIdOrderByQuestionNumber(Long interviewId);

    Long countByInterviewId(Long interviewId);

    /**
     * Find a specific question by interview ID and question number.
     * Used in hybrid mode to check if a question already exists before generating.
     */
    Optional<Question> findByInterviewIdAndQuestionNumber(Long interviewId, Integer questionNumber);
}
