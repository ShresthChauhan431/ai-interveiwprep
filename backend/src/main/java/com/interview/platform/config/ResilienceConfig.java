package com.interview.platform.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Programmatic Resilience4j configuration for external API integrations.
 *
 * <h3>Why programmatic instead of annotation-based?</h3>
 * <p>
 * The existing services (AvatarVideoService, TextToSpeechService, etc.) make
 * retryable HTTP calls in private methods. Spring AOP annotations like
 * {@code @Retry} and {@code @CircuitBreaker} only work on public methods called
 * through the Spring proxy (i.e., from outside the bean). Refactoring every
 * service to extract client classes would be a large structural change for
 * minimal benefit.
 * </p>
 *
 * <p>
 * Instead, this configuration creates named {@link Retry} and
 * {@link CircuitBreaker}
 * instances that services inject and use programmatically via
 * {@code Decorators.ofSupplier(...).withRetry(retry).withCircuitBreaker(cb).get()}.
 * This approach:
 * </p>
 * <ul>
 * <li>Works with private methods (no proxy needed)</li>
 * <li>Replaces the hand-rolled retry loops cleanly</li>
 * <li>Provides circuit breaker protection that the manual loops lacked</li>
 * <li>Is fully configurable via application.properties</li>
 * <li>Exposes metrics to the actuator automatically</li>
 * </ul>
 *
 * <h3>Retry Strategy:</h3>
 * <p>
 * All external API retries use exponential backoff. The retry predicate
 * distinguishes between retryable errors (5xx, 429 Too Many Requests, network
 * errors) and non-retryable errors (4xx client errors other than 429). This
 * matches the behavior of the previous manual retry loops.
 * </p>
 *
 * <h3>Circuit Breaker Strategy:</h3>
 * <p>
 * Each external API has its own circuit breaker with independent state.
 * When an API's failure rate exceeds the threshold (default: 50% over a
 * sliding window of 10 calls), the circuit opens for a configurable duration
 * (default: 60 seconds). During the open state, calls fail fast with
 * {@code CallNotPermittedException} instead of making HTTP requests that
 * will likely fail. After the wait duration, the circuit enters half-open
 * state and permits a few trial calls to check if the API has recovered.
 * </p>
 *
 * <h3>Named Instances:</h3>
 * <ul>
 * <li><strong>ollama</strong> — Ollama Local LLM (question generation,
 * feedback)</li>
 * <li><strong>elevenlabs</strong> — ElevenLabs TTS (text-to-speech)</li>
 * <li><strong>did</strong> — D-ID (avatar video generation, NOT polling)</li>
 * <li><strong>assemblyai</strong> — AssemblyAI (transcription submission, NOT
 * polling)</li>
 * </ul>
 *
 * <h3>Important: Polling loops are NOT wrapped.</h3>
 * <p>
 * The D-ID and AssemblyAI services have polling loops that repeatedly check
 * async job status. These are NOT retries — they are checking if an async job
 * has finished. Wrapping them with Resilience4j Retry would cause incorrect
 * behavior (retrying a "not ready yet" response as if it were a failure).
 * Only the initial job submission calls are wrapped.
 * </p>
 *
 * @see com.interview.platform.service.AvatarVideoService
 * @see com.interview.platform.service.TextToSpeechService
 * @see com.interview.platform.service.SpeechToTextService
 * @see com.interview.platform.service.OllamaService
 * @see com.interview.platform.service.AIFeedbackService
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    // ── Retry configuration ──────────────────────────────────────

    @Value("${resilience4j.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${resilience4j.retry.wait-duration-ms:1000}")
    private long retryWaitDurationMs;

    @Value("${resilience4j.retry.exponential-backoff-multiplier:2.0}")
    private double retryBackoffMultiplier;

    // ── Circuit breaker configuration ────────────────────────────

    @Value("${resilience4j.circuitbreaker.failure-rate-threshold:50}")
    private float cbFailureRateThreshold;

    @Value("${resilience4j.circuitbreaker.slow-call-duration-seconds:30}")
    private long cbSlowCallDurationSeconds;

    @Value("${resilience4j.circuitbreaker.slow-call-rate-threshold:80}")
    private float cbSlowCallRateThreshold;

    @Value("${resilience4j.circuitbreaker.sliding-window-size:10}")
    private int cbSlidingWindowSize;

    @Value("${resilience4j.circuitbreaker.minimum-number-of-calls:5}")
    private int cbMinimumNumberOfCalls;

    @Value("${resilience4j.circuitbreaker.wait-duration-open-state-seconds:60}")
    private long cbWaitDurationOpenStateSeconds;

    @Value("${resilience4j.circuitbreaker.permitted-calls-half-open:3}")
    private int cbPermittedCallsHalfOpen;

    // ════════════════════════════════════════════════════════════════
    // Predicate: which exceptions are retryable?
    // ════════════════════════════════════════════════════════════════

    /**
     * Predicate that determines if an exception should trigger a retry.
     *
     * <p>
     * Retryable:
     * </p>
     * <ul>
     * <li>{@code HttpServerErrorException} — 5xx server errors</li>
     * <li>{@code HttpClientErrorException.TooManyRequests} — 429 rate limit</li>
     * <li>{@code ResourceAccessException} — network/timeout errors</li>
     * </ul>
     *
     * <p>
     * NOT retryable:
     * </p>
     * <ul>
     * <li>All other {@code HttpClientErrorException} subtypes (400, 401, 403, 404,
     * etc.)
     * — these are client errors that will fail again on retry</li>
     * <li>Any other exception type — likely a programming error, not transient</li>
     * </ul>
     */
    private static final Predicate<Throwable> RETRYABLE_PREDICATE = throwable -> {
        if (throwable instanceof HttpServerErrorException) {
            return true;
        }
        if (throwable instanceof HttpClientErrorException.TooManyRequests) {
            return true;
        }
        if (throwable instanceof ResourceAccessException) {
            return true;
        }
        // Check for wrapped exceptions (e.g., CompletionException wrapping the real
        // cause)
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            if (cause instanceof HttpServerErrorException)
                return true;
            if (cause instanceof HttpClientErrorException.TooManyRequests)
                return true;
            if (cause instanceof ResourceAccessException)
                return true;
        }
        return false;
    };

    // ════════════════════════════════════════════════════════════════
    // Retry Registry & Named Instances
    // ════════════════════════════════════════════════════════════════

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig defaultConfig = RetryConfig.custom()
                .maxAttempts(retryMaxAttempts)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialBackoff(retryWaitDurationMs, retryBackoffMultiplier))
                .retryOnException(RETRYABLE_PREDICATE)
                .failAfterMaxAttempts(true)
                .build();

        RetryRegistry registry = RetryRegistry.of(defaultConfig);

        // Register named instances — they inherit the default config
        // but can be customized individually if needed via properties
        registry.retry("ollama");
        registry.retry("elevenlabs");
        registry.retry("did");
        registry.retry("assemblyai");

        log.info("Resilience4j RetryRegistry initialized: maxAttempts={}, waitDuration={}ms, backoffMultiplier={}",
                retryMaxAttempts, retryWaitDurationMs, retryBackoffMultiplier);

        // Add event consumers for observability
        registry.getAllRetries().forEach(retry -> {
            retry.getEventPublisher()
                    .onRetry(event -> log.warn("Retry [{}]: attempt #{} due to {}",
                            event.getName(), event.getNumberOfRetryAttempts(),
                            event.getLastThrowable() != null
                                    ? event.getLastThrowable().getClass().getSimpleName()
                                            + ": " + event.getLastThrowable().getMessage()
                                    : "unknown"))
                    .onError(event -> log.error("Retry [{}]: EXHAUSTED after {} attempts, last error: {}",
                            event.getName(), event.getNumberOfRetryAttempts(),
                            event.getLastThrowable() != null
                                    ? event.getLastThrowable().getMessage()
                                    : "unknown"))
                    .onSuccess(event -> {
                        if (event.getNumberOfRetryAttempts() > 0) {
                            log.info("Retry [{}]: succeeded after {} retries",
                                    event.getName(), event.getNumberOfRetryAttempts());
                        }
                    });
        });

        return registry;
    }

    // ════════════════════════════════════════════════════════════════
    // Circuit Breaker Registry & Named Instances
    // ════════════════════════════════════════════════════════════════

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbFailureRateThreshold)
                .slowCallDurationThreshold(Duration.ofSeconds(cbSlowCallDurationSeconds))
                .slowCallRateThreshold(cbSlowCallRateThreshold)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbSlidingWindowSize)
                .minimumNumberOfCalls(cbMinimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(cbWaitDurationOpenStateSeconds))
                .permittedNumberOfCallsInHalfOpenState(cbPermittedCallsHalfOpen)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(throwable -> {
                    // Record as failure: server errors, network errors, rate limits
                    if (throwable instanceof HttpServerErrorException)
                        return true;
                    if (throwable instanceof ResourceAccessException)
                        return true;
                    if (throwable instanceof HttpClientErrorException.TooManyRequests)
                        return true;
                    // Don't record client errors (4xx) as circuit breaker failures
                    // — those are likely bad requests, not service degradation
                    return !(throwable instanceof HttpClientErrorException);
                })
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultConfig);

        // Register named instances with the default config
        // D-ID gets a more lenient config because it's inherently slow (video
        // generation)
        registry.circuitBreaker("ollama");
        registry.circuitBreaker("elevenlabs");
        registry.circuitBreaker("assemblyai");

        // D-ID: higher slow-call threshold because video generation calls are slow by
        // nature
        CircuitBreakerConfig didConfig = CircuitBreakerConfig.from(defaultConfig)
                .slowCallDurationThreshold(Duration.ofSeconds(120))
                .slowCallRateThreshold(90)
                .build();
        registry.circuitBreaker("did", didConfig);

        log.info("Resilience4j CircuitBreakerRegistry initialized: failureRateThreshold={}%, "
                + "slidingWindowSize={}, waitDurationInOpenState={}s",
                cbFailureRateThreshold, cbSlidingWindowSize, cbWaitDurationOpenStateSeconds);

        // Add event consumers for observability
        registry.getAllCircuitBreakers().forEach(cb -> {
            cb.getEventPublisher()
                    .onStateTransition(event -> {
                        String message = String.format("CircuitBreaker [%s]: %s → %s",
                                event.getCircuitBreakerName(),
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState());
                        if (event.getStateTransition().getToState().toString().equals("OPEN")) {
                            log.error(message);
                        } else {
                            log.warn(message);
                        }
                    })
                    .onCallNotPermitted(event -> log.warn(
                            "CircuitBreaker [{}]: call rejected (circuit OPEN)",
                            event.getCircuitBreakerName()))
                    .onError(event -> log.debug(
                            "CircuitBreaker [{}]: recorded failure: {}",
                            event.getCircuitBreakerName(),
                            event.getThrowable() != null
                                    ? event.getThrowable().getClass().getSimpleName()
                                    : "unknown"))
                    .onSuccess(event -> log.trace(
                            "CircuitBreaker [{}]: recorded success (duration={}ms)",
                            event.getCircuitBreakerName(),
                            event.getElapsedDuration().toMillis()));
        });

        return registry;
    }

    // ════════════════════════════════════════════════════════════════
    // Convenience beans for direct injection
    // ════════════════════════════════════════════════════════════════

    @Bean
    public Retry ollamaRetry(RetryRegistry registry) {
        return registry.retry("ollama");
    }

    @Bean
    public Retry elevenlabsRetry(RetryRegistry registry) {
        return registry.retry("elevenlabs");
    }

    @Bean
    public Retry didRetry(RetryRegistry registry) {
        return registry.retry("did");
    }

    @Bean
    public Retry assemblyaiRetry(RetryRegistry registry) {
        return registry.retry("assemblyai");
    }

    @Bean
    public CircuitBreaker ollamaCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("ollama");
    }

    @Bean
    public CircuitBreaker elevenlabsCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("elevenlabs");
    }

    @Bean
    public CircuitBreaker didCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("did");
    }

    @Bean
    public CircuitBreaker assemblyaiCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("assemblyai");
    }
}
