package com.interview.platform.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for hybrid interview question generation.
 *
 * <h3>Question Generation Model:</h3>
 * <p>
 * The interview has two modes based on total question count:
 * </p>
 * <ul>
 *   <li><strong>FULL PRE-GEN MODE</strong> (totalQuestions <= 5):
 *       ALL questions are generated BEFORE the interview starts.
 *       Questions are delivered one-by-one to the user.</li>
 *   <li><strong>HYBRID MODE</strong> (totalQuestions > 5):
 *       <ul>
 *         <li>Pre-generate the first 3 questions at interview start</li>
 *         <li>After user answers Q3, analyze their response</li>
 *         <li>Generate 2 dynamic follow-up questions based on user's answer</li>
 *         <li>After dynamic questions, resume with remaining pre-generated questions</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h3>Example for 10 questions:</h3>
 * <pre>
 * Q1-Q3: Pre-generated (role-based)
 * Q4-Q5: Dynamic follow-ups based on Q3 answer
 * Q6-Q10: Pre-generated (remaining role-based questions)
 * </pre>
 *
 * @see com.interview.platform.service.InterviewService
 * @see com.interview.platform.service.OllamaService
 */
@Configuration
public class InterviewConfig {

    private static final Logger log = LoggerFactory.getLogger(InterviewConfig.class);

    /**
     * Number of initial questions to pre-generate in hybrid mode (>5 questions).
     * Default: 3 (first 3 questions are pre-generated)
     */
    @Value("${interview.hybrid.initial-pregen-count:3}")
    private int initialPregenCount;

    /**
     * Number of dynamic follow-up questions to generate after initial zone.
     * Default: 2 (generates 2 follow-up questions based on Q3 answer)
     */
    @Value("${interview.hybrid.dynamic-followup-count:2}")
    private int dynamicFollowupCount;

    /**
     * Timeout in milliseconds for generating a single dynamic question.
     * Default: 15000ms (15 seconds)
     */
    @Value("${interview.hybrid.generation-timeout-ms:15000}")
    private long generationTimeoutMs;

    @PostConstruct
    public void init() {
        log.info("Interview configuration loaded: initialPregenCount={}, dynamicFollowupCount={}, generationTimeoutMs={}",
                initialPregenCount, dynamicFollowupCount, generationTimeoutMs);

        if (initialPregenCount < 1) {
            log.warn("interview.hybrid.initial-pregen-count={} is less than 1; defaulting to 3.", initialPregenCount);
            initialPregenCount = 3;
        }
        if (dynamicFollowupCount < 0) {
            log.warn("interview.hybrid.dynamic-followup-count={} is negative; defaulting to 2.", dynamicFollowupCount);
            dynamicFollowupCount = 2;
        }
    }

    /**
     * Returns the number of initial questions to pre-generate.
     * In hybrid mode (>5 questions), this is typically 3.
     *
     * @return the initial pre-generation count
     */
    public int getInitialPregenCount() {
        return initialPregenCount;
    }

    /**
     * Returns the number of dynamic follow-up questions to generate.
     *
     * @return the dynamic follow-up count (default: 2)
     */
    public int getDynamicFollowupCount() {
        return dynamicFollowupCount;
    }

    /**
     * Returns the timeout in milliseconds for dynamic question generation.
     *
     * @return the generation timeout in milliseconds
     */
    public long getGenerationTimeoutMs() {
        return generationTimeoutMs;
    }

    /**
     * Checks if hybrid mode is enabled for a given total question count.
     * 
     * <p>Hybrid mode is DISABLED when totalQuestions <= 5 (all pre-generated).
     * Hybrid mode is ENABLED when totalQuestions > 5.</p>
     *
     * @param totalQuestions the total number of questions in the interview
     * @return true if hybrid mode is active, false if all questions are pre-generated
     */
    public boolean isHybridModeEnabled(int totalQuestions) {
        return totalQuestions > 5;
    }

    /**
     * Determines how many questions to pre-generate at interview start.
     * 
     * <ul>
     *   <li>If totalQuestions <= 5: pre-generate ALL questions</li>
     *   <li>If totalQuestions > 5: pre-generate first 3 + remaining after dynamic zone</li>
     * </ul>
     * 
     * @param totalQuestions the total number of questions
     * @return count of questions to pre-generate at start
     */
    public int getInitialPregenCount(int totalQuestions) {
        if (totalQuestions <= 5) {
            return totalQuestions; // Pre-generate ALL
        }
        return initialPregenCount; // Pre-generate first 3 in hybrid mode
    }

    /**
     * Alias for backward compatibility.
     */
    public int getPregenCount() {
        return initialPregenCount;
    }

    /**
     * Returns the total number of pre-generated questions (initial + remaining after dynamic).
     * 
     * @param totalQuestions the total number of questions
     * @return count of all pre-generated questions
     */
    public int getTotalPregenCount(int totalQuestions) {
        if (totalQuestions <= 5) {
            return totalQuestions; // All pre-generated
        }
        // Initial 3 + remaining after dynamic zone
        // For 10 questions: 3 initial + 2 dynamic = 5, so remaining pre-gen = 10 - 5 = 5
        int dynamicEnd = initialPregenCount + dynamicFollowupCount;
        int remainingPregen = Math.max(0, totalQuestions - dynamicEnd);
        return initialPregenCount + remainingPregen;
    }

    /**
     * Determines if a specific question number falls in the dynamic generation zone.
     * Dynamic zone starts after initial pre-gen questions and spans dynamicFollowupCount questions.
     *
     * @param questionNumber the 1-based question number
     * @param totalQuestions the total number of questions
     * @return true if the question should be generated dynamically
     */
    public boolean isDynamicQuestion(int questionNumber, int totalQuestions) {
        if (totalQuestions <= 5) {
            return false; // All pre-generated for small interviews
        }
        
        // Dynamic zone: questions (initialPregenCount+1) to (initialPregenCount+dynamicFollowupCount)
        // For default values: questions 4 and 5 are dynamic
        int dynamicStart = initialPregenCount + 1; // 4
        int dynamicEnd = initialPregenCount + dynamicFollowupCount; // 5
        
        return questionNumber >= dynamicStart && questionNumber <= dynamicEnd;
    }

    /**
     * Gets the question number after which dynamic questions should be generated.
     * Dynamic questions are generated after the user answers this question.
     *
     * @param totalQuestions the total number of questions
     * @return the trigger question number (default: 3), or -1 if no dynamic questions
     */
    public int getDynamicTriggerQuestion(int totalQuestions) {
        if (totalQuestions <= 5) {
            return -1; // No dynamic questions
        }
        return initialPregenCount; // Trigger after Q3
    }

    /**
     * Backward compatibility - always returns false for old API.
     */
    public boolean isDynamicQuestion(int questionNumber) {
        return false;
    }

    /**
     * Backward compatibility for old getSmartPregenCount.
     */
    public int getSmartPregenCount(int totalQuestions) {
        return getInitialPregenCount(totalQuestions);
    }
}
