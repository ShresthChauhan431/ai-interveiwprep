package com.interview.platform.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * HTTP request interceptor that enforces per-user token-bucket rate limiting
 * on expensive endpoints.
 *
 * <h3>Why rate limiting?</h3>
 * <p>Several endpoints trigger costly external API calls:</p>
 * <ul>
 *   <li>{@code POST /api/interviews/start} — generates 10 OpenAI questions
 *       (~$0.02), 10 ElevenLabs TTS calls (~$0.01), and 10 D-ID avatar
 *       videos (~$0.50). A single interview costs roughly $0.53 in API fees.</li>
 *   <li>{@code POST /api/interviews/{id}/complete} — triggers OpenAI feedback
 *       generation (~$0.02).</li>
 * </ul>
 *
 * <p>Without rate limiting, a malicious or buggy client could create hundreds
 * of interviews per minute, incurring thousands of dollars in API costs and
 * potentially exhausting API rate limits for all users.</p>
 *
 * <h3>Token Bucket Algorithm:</h3>
 * <p>Each authenticated user gets their own {@link Bucket} instance identified
 * by their user ID (extracted from the JWT-authenticated request attribute).
 * The bucket uses a <em>greedy refill</em> strategy: tokens are added gradually
 * over the refill period rather than all at once.</p>
 *
 * <p>Default configuration (configurable via application.properties):</p>
 * <ul>
 *   <li><strong>Capacity:</strong> 5 tokens (burst limit)</li>
 *   <li><strong>Refill:</strong> 5 tokens per 60 seconds (sustained rate)</li>
 * </ul>
 *
 * <p>This means a user can create up to 5 interviews in quick succession
 * (burst), then must wait for tokens to refill at a rate of ~1 per 12 seconds.
 * This is generous for legitimate usage but prevents automated abuse.</p>
 *
 * <h3>Unauthenticated Requests:</h3>
 * <p>If the user ID is not present in the request (e.g., the JWT filter
 * hasn't run yet or the endpoint is public), the interceptor uses the
 * client's IP address as the bucket key. This provides basic protection
 * against unauthenticated abuse.</p>
 *
 * <h3>Response on Rate Limit Exceeded:</h3>
 * <p>Returns HTTP 429 (Too Many Requests) with a JSON error body and
 * a {@code Retry-After} header indicating how many seconds the client
 * should wait before retrying.</p>
 *
 * <h3>Memory Management:</h3>
 * <p>Buckets are stored in a {@link ConcurrentHashMap}. Since each bucket
 * is lightweight (~100 bytes) and the number of concurrent users is bounded,
 * memory usage is negligible. Stale buckets (from users who haven't made
 * requests recently) are not actively evicted — this is acceptable for the
 * expected user base. For high-scale deployments, consider using a distributed
 * rate limiter (e.g., Redis-backed Bucket4j) and adding a scheduled cleanup
 * of stale entries.</p>
 *
 * <h3>Registration:</h3>
 * <p>This interceptor is registered in {@link WebMvcConfig} and applied
 * only to specific expensive endpoint patterns, not to all requests.</p>
 *
 * @see WebMvcConfig
 * @see io.github.bucket4j.Bucket
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /**
     * Per-user (or per-IP) bucket cache with TTL eviction (P2-8).
     *
     * <p>Key: user ID (as string) or IP address for unauthenticated requests.
     * Value: the user's token bucket.</p>
     *
     * <p><strong>P2-8 Fix:</strong> Previously used a plain {@link java.util.concurrent.ConcurrentHashMap}
     * that never evicted stale entries. Over weeks of operation, the map would
     * accumulate entries for every unique user and IP address (~100 bytes per
     * bucket). With 100K unique visitors that's ~10MB, but the growth was
     * unbounded.</p>
     *
     * <p>Now uses a Caffeine cache with:</p>
     * <ul>
     *   <li>{@code expireAfterAccess(10 minutes)} — buckets for inactive users
     *       are automatically evicted after 10 minutes of no requests</li>
     *   <li>{@code maximumSize(50,000)} — hard cap to prevent memory exhaustion
     *       even under sustained unique-IP abuse</li>
     * </ul>
     */
    private final Cache<String, Bucket> bucketCache = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .maximumSize(50_000)
            .build();

    /**
     * Maximum number of tokens in the bucket (burst capacity).
     * Default: 5 — allows a short burst of 5 requests.
     */
    @Value("${app.rate-limit.capacity:5}")
    private int capacity;

    /**
     * Number of tokens refilled per period.
     * Default: 5 — refills the full capacity over the refill period.
     */
    @Value("${app.rate-limit.refill-tokens:5}")
    private int refillTokens;

    /**
     * Refill period in seconds.
     * Default: 60 — combined with refillTokens=5, this gives ~1 request per 12 seconds sustained.
     */
    @Value("${app.rate-limit.refill-period-seconds:60}")
    private int refillPeriodSeconds;

    /**
     * Check if the request should be allowed based on the user's rate limit bucket.
     *
     * <p>Consumes one token from the user's bucket. If the bucket is empty,
     * the request is rejected with HTTP 429.</p>
     *
     * @param request  the incoming HTTP request
     * @param response the HTTP response (used to write 429 if rate limited)
     * @param handler  the matched handler (unused)
     * @return {@code true} if the request is allowed, {@code false} if rate limited
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String bucketKey = resolveBucketKey(request);
        Bucket bucket = bucketCache.get(bucketKey, this::createBucket);

        if (bucket.tryConsume(1)) {
            // Request allowed — add remaining tokens header for client visibility
            long availableTokens = bucket.getAvailableTokens();
            response.addHeader("X-Rate-Limit-Remaining", String.valueOf(availableTokens));
            return true;
        }

        // Rate limit exceeded
        log.warn("Rate limit exceeded for key={}, endpoint={} {}",
                bucketKey, request.getMethod(), request.getRequestURI());

        // Calculate retry-after based on refill rate
        long retryAfterSeconds = Math.max(1, refillPeriodSeconds / Math.max(1, refillTokens));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.addHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.addHeader("X-Rate-Limit-Remaining", "0");

        String errorBody = String.format(
                "{\"timestamp\":\"%s\",\"status\":429,\"error\":\"Too Many Requests\","
                        + "\"message\":\"Rate limit exceeded. Please wait %d seconds before retrying.\","
                        + "\"retryAfterSeconds\":%d}",
                java.time.Instant.now().toString(),
                retryAfterSeconds,
                retryAfterSeconds
        );
        response.getWriter().write(errorBody);
        response.getWriter().flush();

        return false;
    }

    /**
     * Resolve the bucket key for the incoming request.
     *
     * <p>Prefers the authenticated user ID (set by {@code JwtAuthenticationFilter}
     * as a request attribute). Falls back to the client IP address for
     * unauthenticated requests.</p>
     *
     * @param request the HTTP request
     * @return the bucket key (user ID or IP address)
     */
    private String resolveBucketKey(HttpServletRequest request) {
        // Try authenticated user ID first
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr != null) {
            return "user:" + userIdAttr;
        }

        // Fall back to IP address
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // X-Forwarded-For can contain multiple IPs; use the first (client IP)
            return "ip:" + forwardedFor.split(",")[0].trim();
        }

        return "ip:" + request.getRemoteAddr();
    }

    /**
     * Create a new token bucket for the given key.
     *
     * <p>Uses greedy refill — tokens are added gradually over the refill period
     * rather than all at once. This provides smoother rate limiting behavior.</p>
     *
     * @param key the bucket key (used for logging, not for bucket behavior)
     * @return a new configured Bucket instance
     */
    private Bucket createBucket(String key) {
        Bandwidth bandwidth = Bandwidth.classic(
                capacity,
                Refill.greedy(refillTokens, Duration.ofSeconds(refillPeriodSeconds))
        );

        log.debug("Created rate limit bucket for key={}: capacity={}, refill={}/{} sec",
                key, capacity, refillTokens, refillPeriodSeconds);

        return Bucket.builder()
                .addLimit(bandwidth)
                .build();
    }

    /**
     * Get the current number of cached buckets (exposed for monitoring/testing).
     *
     * <p>P2-8: Returns an approximate count since Caffeine performs eviction
     * lazily. Call {@code bucketCache.cleanUp()} first if an exact count is needed.</p>
     *
     * @return the approximate number of active bucket entries
     */
    public long getActiveBucketCount() {
        return bucketCache.estimatedSize();
    }

    /**
     * Clear all cached buckets (useful for testing or administrative reset).
     */
    public void clearBuckets() {
        bucketCache.invalidateAll();
        log.info("Rate limit buckets cleared");
    }
}
