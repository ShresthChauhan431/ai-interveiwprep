package com.interview.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration for registering interceptors.
 *
 * <h3>Rate Limiting (P1):</h3>
 * <p>Registers the {@link RateLimitInterceptor} on endpoint patterns that trigger
 * expensive external API calls. Each matched request consumes a token from the
 * authenticated user's (or IP-based) bucket. When the bucket is empty, the
 * request is rejected with HTTP 429 (Too Many Requests).</p>
 *
 * <h3>Protected Endpoints:</h3>
 * <table>
 *   <caption>Rate-limited endpoint patterns</caption>
 *   <tr><th>Pattern</th><th>Why</th><th>Approx. Cost per Call</th></tr>
 *   <tr>
 *     <td>{@code POST /api/interviews/start}</td>
 *     <td>Triggers OpenAI question generation (10 questions), ElevenLabs TTS
 *         (10 calls), and D-ID avatar video generation (10 calls)</td>
 *     <td>~$0.53</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST /api/interviews/{id}/complete}</td>
 *     <td>Triggers OpenAI feedback generation</td>
 *     <td>~$0.02</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST /api/resumes/upload}</td>
 *     <td>Uploads to S3 and triggers PDF/DOCX parsing (CPU intensive)</td>
 *     <td>Minimal $ but resource intensive</td>
 *   </tr>
 * </table>
 *
 * <h3>Endpoints NOT rate-limited:</h3>
 * <ul>
 *   <li>{@code GET /api/interviews/*} — read-only, cheap, frequent polling</li>
 *   <li>{@code POST /api/auth/*} — has its own security controls (BCrypt cost, lockout)</li>
 *   <li>{@code GET /api/resumes/*} — read-only, S3 presigned URL generation is cheap</li>
 *   <li>{@code POST /api/interviews/{id}/upload-url} — generates a presigned URL (cheap),
 *       and the actual upload goes directly to S3</li>
 *   <li>{@code POST /api/interviews/{id}/confirm-upload} — S3 HEAD check + DB write (cheap)</li>
 * </ul>
 *
 * <h3>Configuration:</h3>
 * <p>Rate limit parameters (capacity, refill rate) are configured in
 * {@code application.properties} via {@code app.rate-limit.*} properties.
 * See {@link RateLimitInterceptor} for details.</p>
 *
 * @see RateLimitInterceptor
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /**
     * Register the rate limit interceptor for expensive endpoint patterns.
     *
     * <p>Path patterns use Spring's {@code AntPathMatcher} syntax. The interceptor
     * is only invoked for requests matching these patterns — all other endpoints
     * pass through without rate limit checks.</p>
     *
     * <p>Note: The interceptor runs AFTER the Spring Security filter chain,
     * so the JWT authentication filter has already set the {@code userId}
     * request attribute by the time the interceptor executes. This allows
     * per-user rate limiting based on the authenticated identity.</p>
     *
     * @param registry the interceptor registry to add patterns to
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        // Interview start — most expensive endpoint (~$0.53 per call)
                        "/api/interviews/start",

                        // Interview completion — triggers feedback generation (~$0.02)
                        "/api/interviews/*/complete",

                        // Resume upload — S3 upload + document parsing
                        "/api/resumes/upload",

                        // Communication Live API — Ollama chat and analysis
                        "/api/communication/**"
                );
    }
}
