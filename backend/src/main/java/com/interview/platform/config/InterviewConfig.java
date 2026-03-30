package com.interview.platform.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for hybrid interview question generation.
 *
 * <h3>Hybrid Question Generation Model:</h3>
 * <p>
 * The interview has two zones:
 * </p>
 * <ul>
 *   <li><strong>PRE-GENERATED ZONE</strong> (questions 1 to {@code pregenCount}):
 *       Questions and TTS audio are generated BEFORE the interview starts.
 *       This ensures the interview can begin immediately without latency.</li>
 *   <li><strong>DYNAMIC ZONE</strong> (questions {@code pregenCount+1} to totalQuestions):
 *       Questions are generated dynamically AFTER each answer using Ollama,
 *       informed by the job role, resume, and ALL previous Q&A pairs.
 *       This creates a more natural, adaptive interview experience.</li>
 * </ul>
 *
 * <h3>Fallback to Original Mode:</h3>
 * <p>
 * Set {@code interview.hybrid.pregen-count} to a value greater than or equal to
 * the total number of questions to disable hybrid mode entirely. In this case,
 * all questions are pre-generated before the interview starts (original behavior).
 * </p>
 *
 * <h3>Properties:</h3>
 * <ul>
 *   <li>{@code interview.hybrid.pregen-count} — Number of questions to pre-generate
 *       before interview starts (default: 1)</li>
 *   <li>{@code interview.hybrid.generation-timeout-ms} — Timeout for generating
 *       a single dynamic question (default: 15000ms = 15 seconds)</li>
 * </ul>
 *
 * @see com.interview.platform.service.InterviewService
 * @see com.interview.platform.service.OllamaService
 */
@Configuration
public class InterviewConfig {

    private static final Logger log = LoggerFactory.getLogger(InterviewConfig.class);

    /**
     * Number of questions to pre-generate before the interview starts.
     *
     * <p>Questions 1 through {@code pregenCount} will be generated with their
     * TTS audio during interview initialization. Questions after this threshold
     * are generated dynamically based on candidate answers.</p>
     *
     * <p>Default: 1 (only the first question is pre-generated)</p>
     */
    @Value("${interview.hybrid.pregen-count:1}")
    private int pregenCount;

    /**
     * Timeout in milliseconds for generating a single dynamic question.
     *
     * <p>This timeout applies to the Ollama call for generating the next
     * adaptive question during the interview. If the timeout is exceeded,
     * a fallback generic question is used to prevent the interview from stalling.</p>
     *
     * <p>Default: 15000ms (15 seconds)</p>
     */
    @Value("${interview.hybrid.generation-timeout-ms:15000}")
    private long generationTimeoutMs;

    @PostConstruct
    public void init() {
        log.info("Hybrid interview configuration loaded: pregenCount={}, generationTimeoutMs={}",
                pregenCount, generationTimeoutMs);

        if (pregenCount < 1) {
            log.warn("interview.hybrid.pregen-count={} is less than 1; at least 1 question must be " +
                    "pre-generated to start the interview. Defaulting to 1.", pregenCount);
            pregenCount = 1;
        }
    }

    /**
     * Returns the number of questions to pre-generate before the interview starts.
     *
     * @return the pre-generation count (minimum 1)
     */
    public int getPregenCount() {
        return pregenCount;
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
     * <p>Hybrid mode is enabled when {@code pregenCount < totalQuestions},
     * meaning some questions will be generated dynamically during the interview.</p>
     *
     * @param totalQuestions the total number of questions in the interview
     * @return true if hybrid mode is active, false if all questions are pre-generated
     */
    public boolean isHybridModeEnabled(int totalQuestions) {
        return pregenCount < totalQuestions;
    }

    /**
     * Determines if a specific question number falls in the dynamic generation zone.
     *
     * @param questionNumber the 1-based question number
     * @return true if the question should be generated dynamically
     */
    public boolean isDynamicQuestion(int questionNumber) {
        return questionNumber > pregenCount;
    }
}
