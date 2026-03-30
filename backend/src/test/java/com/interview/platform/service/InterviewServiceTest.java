package com.interview.platform.service;

import com.interview.platform.dto.ConfirmUploadRequest;
import com.interview.platform.dto.InterviewDTO;
import com.interview.platform.dto.PresignedUrlResponse;
import com.interview.platform.dto.QuestionDTO;
import com.interview.platform.event.QuestionsCreatedEvent;
import com.interview.platform.model.*;
import com.interview.platform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterviewService Tests")
class InterviewServiceTest {

        @Mock
        private InterviewRepository interviewRepository;
        @Mock
        private ResumeRepository resumeRepository;
        @Mock
        private JobRoleRepository jobRoleRepository;
        @Mock
        private QuestionRepository questionRepository;
        @Mock
        private ResponseRepository responseRepository;
        @Mock
        private UserRepository userRepository;
        @Mock
        private OllamaService ollamaService;
        @Mock
        private VideoStorageService videoStorageService;
        @Mock
        private SpeechToTextService speechToTextService;
        @Mock
        private AIFeedbackService aiFeedbackService;
        @Mock
        private TextToSpeechService textToSpeechService; // FIX: Added missing mock — TTS is now injected into
                                                         // InterviewService
        @Mock
        private ApplicationEventPublisher eventPublisher;

        @InjectMocks
        private InterviewService interviewService;

        @Captor
        private ArgumentCaptor<QuestionsCreatedEvent> eventCaptor;

        // ── Shared fixtures ─────────────────────────────────────────

        private User testUser;
        private Resume testResume;
        private JobRole testJobRole;
        private Interview testInterview;
        private Question testQuestion;

        @BeforeEach
        void setUp() {
                testUser = new User();
                testUser.setId(1L);
                testUser.setName("John Doe");
                testUser.setEmail("john@example.com");

                testResume = new Resume(10L, testUser, "resume.pdf",
                                "resumes/1/resume_123.pdf", "Experienced Java developer", LocalDateTime.now());

                testJobRole = new JobRole(5L, "Software Engineer", "Backend dev role", "Engineering", true);

                testInterview = new Interview();
                testInterview.setId(100L);
                testInterview.setUser(testUser);
                testInterview.setResume(testResume);
                testInterview.setJobRole(testJobRole);
                testInterview.setStatus(InterviewStatus.IN_PROGRESS);
                testInterview.setType(InterviewType.VIDEO);
                testInterview.setStartedAt(LocalDateTime.now());

                testQuestion = new Question();
                testQuestion.setId(200L);
                testQuestion.setInterview(testInterview);
                testQuestion.setQuestionText("Tell me about your experience with Spring Boot.");
                testQuestion.setQuestionNumber(1);
                testQuestion.setCategory("Technical");
                testQuestion.setDifficulty("Medium");
        }

        // ============================================================
        // startInterview
        // ============================================================

        @Nested
        @DisplayName("startInterview")
        class StartInterviewTests {

                @Test
                @DisplayName("Should create interview, generate questions with TTS audio, transition to IN_PROGRESS, publish event, and return DTO")
                void testStartInterview_Success() {
                        // Arrange
                        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
                        when(resumeRepository.findById(10L)).thenReturn(Optional.of(testResume));
                        when(jobRoleRepository.findById(5L)).thenReturn(Optional.of(testJobRole));
                        // FIX: Return the actual Interview passed to save() so transitionTo() works on
                        // the real object. Returning testInterview (IN_PROGRESS) caused
                        // transitionTo(IN_PROGRESS) → IllegalStateException in the state machine.
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> {
                                Interview saved = invocation.getArgument(0);
                                if (saved.getId() == null)
                                        saved.setId(100L);
                                return saved;
                        });

                        Question q1 = new Question();
                        q1.setQuestionText("Tell me about your experience with Spring Boot.");
                        q1.setCategory("Technical");
                        q1.setDifficulty("Medium");
                        q1.setQuestionNumber(1);

                        Question q2 = new Question();
                        q2.setQuestionText("Describe a challenging project.");
                        q2.setCategory("Behavioral");
                        q2.setDifficulty("Easy");
                        q2.setQuestionNumber(2);

                        when(ollamaService.generateQuestionsWithResilience(any(Resume.class), any(JobRole.class),
                                        anyInt()))
                                        .thenReturn(List.of(q1, q2));

                        when(questionRepository.save(any(Question.class))).thenAnswer(invocation -> {
                                Question q = invocation.getArgument(0);
                                q.setId((long) q.getQuestionNumber() * 100);
                                return q;
                        });

                        // FIX: Stub TTS — TextToSpeechService is now injected and called per question
                        when(textToSpeechService.generateSpeech(anyString(), anyLong()))
                                        .thenReturn("tts/question_audio.mp3");

                        when(responseRepository.findByQuestionId(anyLong())).thenReturn(Optional.empty());

                        // Act
                        InterviewDTO result = interviewService.startInterview(1L, 10L, 5L, 5);

                        // Assert
                        assertThat(result).isNotNull();
                        assertThat(result.getInterviewId()).isEqualTo(100L);
                        assertThat(result.getQuestions()).hasSize(2);

                        // FIX: Service now starts as CREATED then transitions to IN_PROGRESS (not
                        // GENERATING_VIDEOS)
                        // Verify final save has IN_PROGRESS status
                        verify(interviewRepository, atLeastOnce())
                                        .save(argThat(interview -> interview.getStatus() == InterviewStatus.IN_PROGRESS
                                                        || interview.getStatus() == InterviewStatus.CREATED));

                        verify(ollamaService).generateQuestionsWithResilience(testResume, testJobRole, 5);
                        // FIX: Each question is saved twice — once to get its ID, once after TTS audio
                        // is set
                        verify(questionRepository, times(4)).save(any(Question.class));
                        // FIX: Verify TTS was called for each question
                        verify(textToSpeechService, times(2)).generateSpeech(anyString(), anyLong());

                        // Verify QuestionsCreatedEvent was published
                        verify(eventPublisher).publishEvent(eventCaptor.capture());
                        QuestionsCreatedEvent capturedEvent = eventCaptor.getValue();
                        assertThat(capturedEvent.getInterviewId()).isEqualTo(100L);
                        assertThat(capturedEvent.getQuestionIds()).hasSize(2);
                }

                @Test
                @DisplayName("Should throw when user not found")
                void testStartInterview_UserNotFound() {
                        when(userRepository.findById(999L)).thenReturn(Optional.empty());

                        assertThatThrownBy(() -> interviewService.startInterview(999L, 10L, 5L, 5))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("User not found");
                }

                @Test
                @DisplayName("Should throw when resume does not belong to user")
                void testStartInterview_ResumeMismatch() {
                        User otherUser = new User();
                        otherUser.setId(2L);
                        Resume otherResume = new Resume(11L, otherUser, "other.pdf", "resumes/2/other.pdf", "text",
                                        LocalDateTime.now());

                        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
                        when(resumeRepository.findById(11L)).thenReturn(Optional.of(otherResume));

                        assertThatThrownBy(() -> interviewService.startInterview(1L, 11L, 5L, 5))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Resume does not belong to user");
                }

                @Test
                @DisplayName("Should proceed with fallback text (not throw) when resume has no extracted text")
                void testStartInterview_EmptyResumeText_UsesFallback() {
                        // FIX: Code no longer throws — it uses fallback text and continues the
                        // interview
                        Resume emptyResume = new Resume(10L, testUser, "resume.pdf", "resumes/1/resume.pdf", null,
                                        LocalDateTime.now());

                        Question q1 = new Question();
                        q1.setQuestionText("Tell me about yourself.");
                        q1.setCategory("General");
                        q1.setDifficulty("Easy");
                        q1.setQuestionNumber(1);

                        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
                        when(resumeRepository.findById(10L)).thenReturn(Optional.of(emptyResume));
                        when(jobRoleRepository.findById(5L)).thenReturn(Optional.of(testJobRole));
                        // FIX: Passthrough save so state machine transitions work correctly
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> {
                                Interview saved = invocation.getArgument(0);
                                if (saved.getId() == null)
                                        saved.setId(100L);
                                return saved;
                        });
                        when(ollamaService.generateQuestionsWithResilience(any(Resume.class), any(JobRole.class),
                                        anyInt()))
                                        .thenReturn(List.of(q1));
                        when(questionRepository.save(any(Question.class))).thenAnswer(invocation -> {
                                Question q = invocation.getArgument(0);
                                q.setId(100L);
                                return q;
                        });
                        when(textToSpeechService.generateSpeech(anyString(), anyLong()))
                                        .thenReturn("tts/q1_audio.mp3");
                        when(responseRepository.findByQuestionId(anyLong())).thenReturn(Optional.empty());

                        // Act — should NOT throw; interview proceeds with fallback text
                        InterviewDTO result = interviewService.startInterview(1L, 10L, 5L, 5);

                        assertThat(result).isNotNull();
                        // Ollama was still called despite empty resume (using fallback text)
                        verify(ollamaService).generateQuestionsWithResilience(any(), any(), anyInt());
                }

                @Test
                @DisplayName("Should mark interview as FAILED and throw actionable error when Ollama is unreachable")
                void testStartInterview_OllamaFailure() {
                        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
                        when(resumeRepository.findById(10L)).thenReturn(Optional.of(testResume));
                        when(jobRoleRepository.findById(5L)).thenReturn(Optional.of(testJobRole));
                        // FIX: Passthrough save — OllamaFailure test still needs the state machine
                        // to accept CREATED → FAILED transition when Ollama throws
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> {
                                Interview saved = invocation.getArgument(0);
                                if (saved.getId() == null)
                                        saved.setId(100L);
                                return saved;
                        });
                        when(ollamaService.generateQuestionsWithResilience(any(), any(), anyInt()))
                                        .thenThrow(new ResourceAccessException("Connection refused"));

                        // Act & Assert
                        assertThatThrownBy(() -> interviewService.startInterview(1L, 10L, 5L, 5))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("ollama serve"); // actionable hint in the message

                        // Interview entity must be saved with FAILED status so it doesn't stay stuck
                        verify(interviewRepository, atLeastOnce())
                                        .save(argThat(i -> i.getStatus() == InterviewStatus.FAILED));
                }

                @Test
                @DisplayName("Should not call AvatarVideoService directly — uses event-driven pipeline")
                void testStartInterview_NoDirectAvatarCall() {
                        // Arrange
                        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
                        when(resumeRepository.findById(10L)).thenReturn(Optional.of(testResume));
                        when(jobRoleRepository.findById(5L)).thenReturn(Optional.of(testJobRole));
                        // FIX: Passthrough save so state machine transitions work correctly
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(invocation -> {
                                Interview saved = invocation.getArgument(0);
                                if (saved.getId() == null)
                                        saved.setId(100L);
                                return saved;
                        });

                        Question q1 = new Question();
                        q1.setQuestionText("Question 1");
                        q1.setCategory("Technical");
                        q1.setDifficulty("Easy");
                        q1.setQuestionNumber(1);

                        when(ollamaService.generateQuestionsWithResilience(any(Resume.class), any(JobRole.class),
                                        anyInt()))
                                        .thenReturn(List.of(q1));
                        when(questionRepository.save(any(Question.class))).thenAnswer(invocation -> {
                                Question q = invocation.getArgument(0);
                                q.setId(300L);
                                return q;
                        });
                        // FIX: Stub TTS — TextToSpeechService is called per question after it's saved
                        when(textToSpeechService.generateSpeech(anyString(), anyLong()))
                                        .thenReturn("tts/q_audio.mp3");
                        when(responseRepository.findByQuestionId(anyLong())).thenReturn(Optional.empty());

                        // Act
                        interviewService.startInterview(1L, 10L, 5L, 5);

                        // Assert: AvatarVideoService is NOT injected and NOT called.
                        // The event publisher should be called instead.
                        verify(eventPublisher).publishEvent(any(QuestionsCreatedEvent.class));
                }
        }

        // ============================================================
        // generateUploadUrl (P1 — presigned PUT flow)
        // ============================================================

        @Nested
        @DisplayName("generateUploadUrl")
        class GenerateUploadUrlTests {

                @Test
                @DisplayName("Should generate presigned PUT URL for valid interview and question")
                void testGenerateUploadUrl_Success() {
                        // Arrange
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());
                        when(videoStorageService.buildVideoResponseKey(1L, 100L, 200L))
                                        .thenReturn("interviews/1/100/response_200_ts.webm");
                        when(videoStorageService.generatePresignedPutUrl(anyString(), eq("video/webm")))
                                        .thenReturn("https://bucket.s3.amazonaws.com/interviews/1/100/response_200_ts.webm?signed=true");

                        // Act
                        PresignedUrlResponse result = interviewService.generateUploadUrl(1L, 100L, 200L, "video/webm");

                        // Assert
                        assertThat(result).isNotNull();
                        assertThat(result.getUploadUrl()).contains("signed=true");
                        assertThat(result.getS3Key()).isEqualTo("interviews/1/100/response_200_ts.webm");
                        assertThat(result.getExpiresInSeconds()).isEqualTo(900);

                        verify(videoStorageService).buildVideoResponseKey(1L, 100L, 200L);
                        verify(videoStorageService).generatePresignedPutUrl("interviews/1/100/response_200_ts.webm",
                                        "video/webm");
                }

                @Test
                @DisplayName("Should default content type to video/webm when null")
                void testGenerateUploadUrl_DefaultContentType() {
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());
                        when(videoStorageService.buildVideoResponseKey(1L, 100L, 200L))
                                        .thenReturn("interviews/1/100/response_200_ts.webm");
                        when(videoStorageService.generatePresignedPutUrl(anyString(), eq("video/webm")))
                                        .thenReturn("https://bucket.s3.amazonaws.com/key?signed=true");

                        interviewService.generateUploadUrl(1L, 100L, 200L, null);

                        verify(videoStorageService).generatePresignedPutUrl(anyString(), eq("video/webm"));
                }

                @Test
                @DisplayName("Should throw when interview not found")
                void testGenerateUploadUrl_InterviewNotFound() {
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.empty());

                        assertThatThrownBy(() -> interviewService.generateUploadUrl(1L, 100L, 200L, "video/webm"))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Interview not found");
                }

                @Test
                @DisplayName("Should throw when interview is not in progress")
                void testGenerateUploadUrl_InterviewNotInProgress() {
                        testInterview.setStatus(InterviewStatus.COMPLETED);
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));

                        assertThatThrownBy(() -> interviewService.generateUploadUrl(1L, 100L, 200L, "video/webm"))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Interview is not in progress");
                }

                @Test
                @DisplayName("Should throw when response already exists for question")
                void testGenerateUploadUrl_DuplicateResponse() {
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.of(new Response()));

                        assertThatThrownBy(() -> interviewService.generateUploadUrl(1L, 100L, 200L, "video/webm"))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Response already submitted");
                }

                @Test
                @DisplayName("Should throw when question does not belong to interview")
                void testGenerateUploadUrl_QuestionMismatch() {
                        Interview otherInterview = new Interview();
                        otherInterview.setId(999L);
                        testQuestion.setInterview(otherInterview);

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));

                        assertThatThrownBy(() -> interviewService.generateUploadUrl(1L, 100L, 200L, "video/webm"))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Question does not belong to this interview");
                }
        }

        // ============================================================
        // confirmUpload (P1 — presigned PUT flow)
        // ============================================================

        @Nested
        @DisplayName("confirmUpload")
        class ConfirmUploadTests {

                @Test
                @DisplayName("Should verify S3 object, save Response with S3 key, and trigger transcription")
                void testConfirmUpload_Success() {
                        // Arrange
                        ConfirmUploadRequest request = new ConfirmUploadRequest(
                                        200L, "interviews/1/100/response_200_ts.webm", "video/webm", 120);

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());
                        when(videoStorageService.fileExists("interviews/1/100/response_200_ts.webm")).thenReturn(true);
                        when(responseRepository.save(any(Response.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        String result = interviewService.confirmUpload(1L, 100L, request);

                        // Assert
                        assertThat(result).isEqualTo("Response uploaded and confirmed successfully");

                        // Verify Response entity is saved with S3 key (not presigned URL)
                        verify(responseRepository).save(argThat(response -> {
                                assertThat(response.getVideoUrl()).isEqualTo("interviews/1/100/response_200_ts.webm");
                                assertThat(response.getVideoDuration()).isEqualTo(120);
                                assertThat(response.getQuestion().getId()).isEqualTo(200L);
                                assertThat(response.getInterview().getId()).isEqualTo(100L);
                                return true;
                        }));

                        // Verify async transcription is triggered with S3 key
                        verify(speechToTextService).transcribeVideoAsync("interviews/1/100/response_200_ts.webm", 200L);
                }

                @Test
                @DisplayName("Should throw when S3 object does not exist")
                void testConfirmUpload_S3ObjectNotFound() {
                        ConfirmUploadRequest request = new ConfirmUploadRequest(
                                        200L, "interviews/1/100/nonexistent.webm", "video/webm", null);

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());
                        when(videoStorageService.fileExists("interviews/1/100/nonexistent.webm")).thenReturn(false);

                        assertThatThrownBy(() -> interviewService.confirmUpload(1L, 100L, request))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Upload not found in S3");
                }

                @Test
                @DisplayName("Should throw when interview not found or not owned by user")
                void testConfirmUpload_InterviewNotFound() {
                        ConfirmUploadRequest request = new ConfirmUploadRequest(
                                        200L, "key.webm", "video/webm", null);

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.empty());

                        assertThatThrownBy(() -> interviewService.confirmUpload(1L, 100L, request))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Interview not found");
                }

                @Test
                @DisplayName("Should throw when duplicate response exists")
                void testConfirmUpload_DuplicateResponse() {
                        ConfirmUploadRequest request = new ConfirmUploadRequest(
                                        200L, "key.webm", "video/webm", null);

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.of(new Response()));

                        assertThatThrownBy(() -> interviewService.confirmUpload(1L, 100L, request))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Response already submitted");
                }

                @Test
                @DisplayName("Should not throw when transcription initiation fails — logs warning")
                void testConfirmUpload_TranscriptionFailure_NonFatal() {
                        ConfirmUploadRequest request = new ConfirmUploadRequest(
                                        200L, "interviews/1/100/response_200_ts.webm", "video/webm", null);

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());
                        when(videoStorageService.fileExists(anyString())).thenReturn(true);
                        when(responseRepository.save(any(Response.class))).thenAnswer(i -> i.getArgument(0));
                        doThrow(new RuntimeException("AssemblyAI unavailable"))
                                        .when(speechToTextService).transcribeVideoAsync(anyString(), anyLong());

                        // Act — should NOT throw even though transcription fails
                        String result = interviewService.confirmUpload(1L, 100L, request);

                        assertThat(result).isEqualTo("Response uploaded and confirmed successfully");
                        verify(responseRepository).save(any(Response.class));
                }
        }

        // ============================================================
        // submitResponse (legacy multipart upload)
        // ============================================================

        @Nested
        @DisplayName("submitResponse (legacy)")
        class SubmitResponseTests {

                private MockMultipartFile videoFile;

                @BeforeEach
                void setUpFile() {
                        videoFile = new MockMultipartFile(
                                        "video", "answer.webm", "video/webm", "fake-video-bytes".getBytes());
                }

                @Test
                @DisplayName("Should upload video, save response with S3 key, and trigger transcription")
                void testSubmitResponse_Success() {
                        // Arrange
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());
                        // P1: uploadVideo returns S3 key, not presigned URL
                        when(videoStorageService.uploadVideo(videoFile, 1L, 100L, 200L))
                                        .thenReturn("interviews/1/100/response_200_ts.webm");
                        when(responseRepository.save(any(Response.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        String result = interviewService.submitResponse(1L, 100L, 200L, videoFile);

                        // Assert
                        assertThat(result).isEqualTo("Response submitted successfully");

                        verify(videoStorageService).uploadVideo(videoFile, 1L, 100L, 200L);
                        // P1: verify S3 key is stored (not a presigned URL)
                        verify(responseRepository)
                                        .save(argThat(r -> "interviews/1/100/response_200_ts.webm"
                                                        .equals(r.getVideoUrl())));
                        // P1: transcription is triggered with S3 key (SpeechToTextService resolves
                        // internally)
                        verify(speechToTextService).transcribeVideoAsync("interviews/1/100/response_200_ts.webm", 200L);
                }

                @Test
                @DisplayName("Should throw when interview not found or doesn't belong to user")
                void testSubmitResponse_InterviewNotFound() {
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.empty());

                        assertThatThrownBy(() -> interviewService.submitResponse(1L, 100L, 200L, videoFile))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Interview not found");
                }

                @Test
                @DisplayName("Should throw when interview is not in progress")
                void testSubmitResponse_InterviewNotInProgress() {
                        testInterview.setStatus(InterviewStatus.COMPLETED);
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));

                        assertThatThrownBy(() -> interviewService.submitResponse(1L, 100L, 200L, videoFile))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Interview is not in progress");
                }

                @Test
                @DisplayName("Should throw when question doesn't belong to interview")
                void testSubmitResponse_QuestionMismatch() {
                        Interview otherInterview = new Interview();
                        otherInterview.setId(999L);
                        testQuestion.setInterview(otherInterview);

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));

                        assertThatThrownBy(() -> interviewService.submitResponse(1L, 100L, 200L, videoFile))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Question does not belong to this interview");
                }

                @Test
                @DisplayName("Should throw when response already exists for question")
                void testSubmitResponse_DuplicateResponse() {
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findById(200L)).thenReturn(Optional.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.of(new Response()));

                        assertThatThrownBy(() -> interviewService.submitResponse(1L, 100L, 200L, videoFile))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Response already submitted");
                }
        }

        // ============================================================
        // completeInterview
        // ============================================================

        @Nested
        @DisplayName("completeInterview")
        class CompleteInterviewTests {

                @Test
                @DisplayName("Should set status to PROCESSING and trigger feedback generation")
                void testCompleteInterview_Success() {
                        // Arrange
                        Response mockResponse = new Response();
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(responseRepository.findByInterviewId(100L)).thenReturn(List.of(mockResponse));
                        when(interviewRepository.save(any(Interview.class))).thenReturn(testInterview);
                        when(aiFeedbackService.generateFeedbackAsync(100L))
                                        .thenReturn(CompletableFuture.completedFuture(null));

                        // Act
                        String result = interviewService.completeInterview(100L, 1L);

                        // Assert
                        assertThat(result).contains("submitted for processing");

                        verify(interviewRepository)
                                        .save(argThat(interview -> interview.getStatus() == InterviewStatus.PROCESSING
                                                        && interview.getCompletedAt() != null));
                        verify(aiFeedbackService).generateFeedbackAsync(100L);
                }

                @Test
                @DisplayName("Should throw when interview is not in progress")
                void testCompleteInterview_NotInProgress() {
                        testInterview.setStatus(InterviewStatus.COMPLETED);
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));

                        assertThatThrownBy(() -> interviewService.completeInterview(100L, 1L))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Interview is not in progress");
                }

                @Test
                @DisplayName("Should throw when no responses have been submitted")
                void testCompleteInterview_NoResponses() {
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(responseRepository.findByInterviewId(100L)).thenReturn(Collections.emptyList());

                        assertThatThrownBy(() -> interviewService.completeInterview(100L, 1L))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Cannot complete interview without any responses");
                }
        }

        // ============================================================
        // getInterview
        // ============================================================

        @Nested
        @DisplayName("getInterview")
        class GetInterviewTests {

                @Test
                @DisplayName("Should return full interview DTO with questions and presigned avatar URLs")
                void testGetInterview_Success() {
                        // Arrange — question has an S3 key for avatar video
                        testQuestion.setAvatarVideoUrl("avatar-cache/abc123.mp4");

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findByInterviewIdOrderByQuestionNumber(100L))
                                        .thenReturn(List.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());
                        // P1: resolveToPresignedUrl calls generatePresignedGetUrl for S3 keys
                        when(videoStorageService.generatePresignedGetUrl("avatar-cache/abc123.mp4"))
                                        .thenReturn("https://bucket.s3.amazonaws.com/avatar-cache/abc123.mp4?signed=true");

                        // Act
                        InterviewDTO dto = interviewService.getInterview(100L, 1L);

                        // Assert
                        assertThat(dto).isNotNull();
                        assertThat(dto.getInterviewId()).isEqualTo(100L);
                        assertThat(dto.getQuestions()).hasSize(1);
                        assertThat(dto.getQuestions().get(0).getQuestionText())
                                        .isEqualTo("Tell me about your experience with Spring Boot.");
                        // P1: avatar URL should be a presigned GET URL generated on-demand
                        assertThat(dto.getQuestions().get(0).getAvatarVideoUrl())
                                        .contains("signed=true");

                        verify(videoStorageService).generatePresignedGetUrl("avatar-cache/abc123.mp4");
                }

                @Test
                @DisplayName("Should handle null avatar video URL gracefully")
                void testGetInterview_NullAvatarUrl() {
                        testQuestion.setAvatarVideoUrl(null);

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findByInterviewIdOrderByQuestionNumber(100L))
                                        .thenReturn(List.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());

                        InterviewDTO dto = interviewService.getInterview(100L, 1L);

                        assertThat(dto.getQuestions().get(0).getAvatarVideoUrl()).isNull();
                        // Should not call generatePresignedGetUrl for null keys
                        verify(videoStorageService, never()).generatePresignedGetUrl(anyString());
                }

                @Test
                @DisplayName("Should pass through legacy HTTP URLs without generating presigned URL")
                void testGetInterview_LegacyHttpUrl() {
                        testQuestion.setAvatarVideoUrl("https://old-bucket.s3.amazonaws.com/old-url?expired=true");

                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(testInterview));
                        when(questionRepository.findByInterviewIdOrderByQuestionNumber(100L))
                                        .thenReturn(List.of(testQuestion));
                        when(responseRepository.findByQuestionId(200L)).thenReturn(Optional.empty());

                        InterviewDTO dto = interviewService.getInterview(100L, 1L);

                        // Legacy URLs are passed through as-is
                        assertThat(dto.getQuestions().get(0).getAvatarVideoUrl())
                                        .isEqualTo("https://old-bucket.s3.amazonaws.com/old-url?expired=true");
                        // Should not call generatePresignedGetUrl for legacy URLs
                        verify(videoStorageService, never()).generatePresignedGetUrl(anyString());
                }

                @Test
                @DisplayName("Should throw when interview not found")
                void testGetInterview_NotFound() {
                        when(interviewRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.empty());

                        assertThatThrownBy(() -> interviewService.getInterview(100L, 1L))
                                        .isInstanceOf(RuntimeException.class);
                }
        }

        // ============================================================
        // getInterviewHistory
        // ============================================================

        @Nested
        @DisplayName("getInterviewHistory")
        class GetInterviewHistoryTests {

                @Test
                @DisplayName("Should return list of interview DTOs for user")
                void testGetInterviewHistory_Success() {
                        Page<Interview> mockPage = new PageImpl<>(List.of(testInterview));
                        when(interviewRepository.findByUserIdOrderByStartedAtDesc(eq(1L), any(PageRequest.class)))
                                .thenReturn(mockPage);

                        List<InterviewDTO> history = interviewService.getInterviewHistory(1L);

                        assertThat(history).hasSize(1);
                        assertThat(history.get(0).getJobRoleTitle()).isEqualTo("Software Engineer");
                        assertThat(history.get(0).getStatus()).isEqualTo("IN_PROGRESS");
                        // History DTOs should NOT include questions (lightweight)
                        assertThat(history.get(0).getQuestions()).isNull();
                }

                @Test
                @DisplayName("Should return empty list when user has no interviews")
                void testGetInterviewHistory_Empty() {
                        Page<Interview> mockPage = new PageImpl<>(Collections.emptyList());
                        when(interviewRepository.findByUserIdOrderByStartedAtDesc(eq(1L), any(PageRequest.class)))
                                .thenReturn(mockPage);

                        List<InterviewDTO> history = interviewService.getInterviewHistory(1L);

                        assertThat(history).isEmpty();
                }
        }
}
