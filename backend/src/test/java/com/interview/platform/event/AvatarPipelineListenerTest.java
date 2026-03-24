package com.interview.platform.event;

import com.interview.platform.model.Interview;
import com.interview.platform.model.InterviewStatus;
import com.interview.platform.model.InterviewType;
import com.interview.platform.model.Question;
import com.interview.platform.repository.InterviewRepository;
import com.interview.platform.repository.QuestionRepository;
import com.interview.platform.service.CachedAvatarService;
import com.interview.platform.service.TextToSpeechService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AvatarPipelineListener Tests")
class AvatarPipelineListenerTest {

        @Mock
        private QuestionRepository questionRepository;

        @Mock
        private InterviewRepository interviewRepository;

        @Mock
        private CachedAvatarService cachedAvatarService;

        @Mock
        private TextToSpeechService textToSpeechService;

        @Mock
        private com.interview.platform.service.SseEmitterService sseEmitterService;

        @InjectMocks
        private AvatarPipelineListener listener;

        // ── Shared fixtures ─────────────────────────────────────────

        private Interview testInterview;
        private Question question1;
        private Question question2;
        private Question question3;

        @BeforeEach
        void setUp() {
                testInterview = new Interview();
                testInterview.setId(100L);
                testInterview.setStatus(InterviewStatus.GENERATING_VIDEOS);
                testInterview.setType(InterviewType.VIDEO);

                question1 = new Question();
                question1.setId(201L);
                question1.setInterview(testInterview);
                question1.setQuestionText("Tell me about your experience with Spring Boot.");
                question1.setQuestionNumber(1);

                question2 = new Question();
                question2.setId(202L);
                question2.setInterview(testInterview);
                question2.setQuestionText("Describe a challenging project you worked on.");
                question2.setQuestionNumber(2);

                question3 = new Question();
                question3.setId(203L);
                question3.setInterview(testInterview);
                question3.setQuestionText("What are your strengths as a developer?");
                question3.setQuestionNumber(3);
        }

        // ============================================================
        // onQuestionsCreated — full pipeline
        // ============================================================

        @Nested
        @DisplayName("onQuestionsCreated")
        class OnQuestionsCreatedTests {

                @Test
                @DisplayName("Should process all questions and transition interview to IN_PROGRESS on success")
                void testOnQuestionsCreated_AllSuccess() {
                        // Arrange
                        QuestionsCreatedEvent event = new QuestionsCreatedEvent(
                                        this, 100L, List.of(201L, 202L));

                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(questionRepository.findById(202L)).thenReturn(Optional.of(question2));
                        when(textToSpeechService.generateAndSaveAudio("Tell me about your experience with Spring Boot.",
                                        201L))
                                        .thenReturn("tts-audio/abc123.mp3");
                        when(textToSpeechService.generateAndSaveAudio("Describe a challenging project you worked on.",
                                        202L))
                                        .thenReturn("tts-audio/def456.mp3");
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        listener.onQuestionsCreated(event);

                        // Assert — both questions processed
                        verify(textToSpeechService).generateAndSaveAudio(
                                        "Tell me about your experience with Spring Boot.", 201L);
                        verify(textToSpeechService).generateAndSaveAudio(
                                        "Describe a challenging project you worked on.", 202L);
                        verify(questionRepository, times(2)).save(any(Question.class));

                        // Assert — interview transitioned to IN_PROGRESS
                        verify(interviewRepository).save(
                                        argThat(interview -> interview.getStatus() == InterviewStatus.IN_PROGRESS));
                }

                @Test
                @DisplayName("Should continue processing remaining questions when one fails")
                void testOnQuestionsCreated_PartialFailure() {
                        // Arrange — question 201 fails, 202 succeeds
                        QuestionsCreatedEvent event = new QuestionsCreatedEvent(
                                        this, 100L, List.of(201L, 202L));

                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(questionRepository.findById(202L)).thenReturn(Optional.of(question2));
                        when(textToSpeechService.generateAndSaveAudio(
                                        eq("Tell me about your experience with Spring Boot."), eq(201L)))
                                        .thenThrow(new RuntimeException("D-ID API timeout"));
                        when(textToSpeechService.generateAndSaveAudio(
                                        eq("Describe a challenging project you worked on."), eq(202L)))
                                        .thenReturn("tts-audio/def456.mp3");
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(i -> i.getArgument(0));

                        // Act — should NOT throw
                        assertThatCode(() -> listener.onQuestionsCreated(event))
                                        .doesNotThrowAnyException();

                        // Assert — question 202 was still processed despite 201's failure
                        verify(textToSpeechService).generateAndSaveAudio(anyString(), eq(202L));
                        // Only question 202 should be saved (201 failed before save)
                        verify(questionRepository, times(1)).save(any(Question.class));

                        // Assert — interview still transitions to IN_PROGRESS (partial success is OK)
                        verify(interviewRepository).save(
                                        argThat(interview -> interview.getStatus() == InterviewStatus.IN_PROGRESS));
                }

                @Test
                @DisplayName("Should still transition to IN_PROGRESS when all questions fail")
                void testOnQuestionsCreated_AllFail() {
                        // Arrange — all questions fail
                        QuestionsCreatedEvent event = new QuestionsCreatedEvent(
                                        this, 100L, List.of(201L, 202L));

                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(questionRepository.findById(202L)).thenReturn(Optional.of(question2));
                        when(textToSpeechService.generateAndSaveAudio(anyString(), anyLong()))
                                        .thenThrow(new RuntimeException("Service unavailable"));
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        listener.onQuestionsCreated(event);

                        // Assert — interview still transitions to IN_PROGRESS (text-only fallback)
                        verify(interviewRepository).save(
                                        argThat(interview -> interview.getStatus() == InterviewStatus.IN_PROGRESS));
                        // No questions saved (all failed)
                        verify(questionRepository, never()).save(any(Question.class));
                }

                @Test
                @DisplayName("Should handle interview transition failure gracefully")
                void testOnQuestionsCreated_TransitionFailure() {
                        // Arrange — questions succeed but interview transition fails
                        QuestionsCreatedEvent event = new QuestionsCreatedEvent(
                                        this, 100L, List.of(201L));

                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(textToSpeechService.generateAndSaveAudio(anyString(), eq(201L)))
                                        .thenReturn("tts-audio/abc123.mp3");
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));
                        when(interviewRepository.findById(100L))
                                        .thenThrow(new RuntimeException("DB connection lost"));

                        // Act — should NOT throw (recovery task will handle it)
                        assertThatCode(() -> listener.onQuestionsCreated(event))
                                        .doesNotThrowAnyException();

                        // Assert — question was still processed
                        verify(questionRepository).save(any(Question.class));
                }
        }

        // ============================================================
        // processQuestion
        // ============================================================

        @Nested
        @DisplayName("processQuestion")
        class ProcessQuestionTests {

                @Test
                @DisplayName("Should generate avatar via CachedAvatarService and save S3 key to question")
                void testProcessQuestion_Success() {
                        // Arrange
                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(textToSpeechService.generateAndSaveAudio(
                                        "Tell me about your experience with Spring Boot.", 201L))
                                        .thenReturn("tts-audio/abc123.mp3");
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        listener.processQuestion(201L);

                        // Assert
                        verify(textToSpeechService).generateAndSaveAudio(
                                        "Tell me about your experience with Spring Boot.", 201L);
                        verify(questionRepository).save(argThat(
                                        question -> "tts-audio/abc123.mp3".equals(question.getAudioUrl())));
                }

                @Test
                @DisplayName("Should skip processing when question already has avatar video (idempotency)")
                void testProcessQuestion_AlreadyHasAvatar() {
                        // Arrange — question already has an avatar video
                        question1.setAudioUrl("tts-audio/existing.mp3");
                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));

                        // Act
                        listener.processQuestion(201L);

                        // Assert — should NOT call TextToSpeechService or save
                        verify(textToSpeechService, never()).generateAndSaveAudio(anyString(), anyLong());
                        verify(questionRepository, never()).save(any(Question.class));
                }

                @Test
                @DisplayName("Should skip processing when avatar video URL is non-blank (legacy URL)")
                void testProcessQuestion_AlreadyHasLegacyUrl() {
                        // Arrange — question has a legacy HTTP URL
                        question1.setAudioUrl("https://bucket.s3.amazonaws.com/old-audio.mp3");
                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));

                        // Act
                        listener.processQuestion(201L);

                        // Assert
                        verify(textToSpeechService, never()).generateAndSaveAudio(anyString(), anyLong());
                        verify(questionRepository, never()).save(any(Question.class));
                }

                @Test
                @DisplayName("Should process question when avatarVideoUrl is blank string")
                void testProcessQuestion_BlankAvatarUrl() {
                        // Arrange — blank string is treated as absent
                        question1.setAudioUrl("   ");
                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(textToSpeechService.generateAndSaveAudio(anyString(), eq(201L)))
                                        .thenReturn("tts-audio/new.mp3");
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        listener.processQuestion(201L);

                        // Assert — should generate and save
                        verify(textToSpeechService).generateAndSaveAudio(anyString(), eq(201L));
                        verify(questionRepository)
                                        .save(argThat(q -> "tts-audio/new.mp3".equals(q.getAudioUrl())));
                }

                @Test
                @DisplayName("Should throw RuntimeException when question is not found")
                void testProcessQuestion_QuestionNotFound() {
                        when(questionRepository.findById(999L)).thenReturn(Optional.empty());

                        assertThatThrownBy(() -> listener.processQuestion(999L))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Question not found during avatar pipeline");
                }

                @Test
                @DisplayName("Should propagate CachedAvatarService exceptions")
                void testProcessQuestion_CachedAvatarServiceError() {
                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(textToSpeechService.generateAndSaveAudio(anyString(), eq(201L)))
                                        .thenThrow(new RuntimeException("ElevenLabs rate limit exceeded"));

                        assertThatThrownBy(() -> listener.processQuestion(201L))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("ElevenLabs rate limit exceeded");
                }
        }

        // ============================================================
        // transitionToInProgress
        // ============================================================

        @Nested
        @DisplayName("transitionToInProgress")
        class TransitionToInProgressTests {

                @Test
                @DisplayName("Should transition interview from GENERATING_VIDEOS to IN_PROGRESS")
                void testTransition_Success() {
                        testInterview.setStatus(InterviewStatus.GENERATING_VIDEOS);
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        listener.transitionToInProgress(100L);

                        // Assert
                        verify(interviewRepository).save(
                                        argThat(interview -> interview.getStatus() == InterviewStatus.IN_PROGRESS));
                }

                @Test
                @DisplayName("Should be a no-op when interview is already IN_PROGRESS (recovery task already ran)")
                void testTransition_AlreadyInProgress() {
                        testInterview.setStatus(InterviewStatus.IN_PROGRESS);
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));

                        // Act
                        listener.transitionToInProgress(100L);

                        // Assert — should NOT save (already in target state)
                        verify(interviewRepository, never()).save(any(Interview.class));
                }

                @Test
                @DisplayName("Should be a no-op when interview status is COMPLETED")
                void testTransition_AlreadyCompleted() {
                        testInterview.setStatus(InterviewStatus.COMPLETED);
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));

                        // Act
                        listener.transitionToInProgress(100L);

                        // Assert — should NOT save
                        verify(interviewRepository, never()).save(any(Interview.class));
                }

                @Test
                @DisplayName("Should be a no-op when interview status is FAILED")
                void testTransition_AlreadyFailed() {
                        testInterview.setStatus(InterviewStatus.FAILED);
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));

                        // Act
                        listener.transitionToInProgress(100L);

                        // Assert — should NOT save
                        verify(interviewRepository, never()).save(any(Interview.class));
                }

                @Test
                @DisplayName("Should be a no-op when interview status is PROCESSING")
                void testTransition_AlreadyProcessing() {
                        testInterview.setStatus(InterviewStatus.PROCESSING);
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));

                        // Act
                        listener.transitionToInProgress(100L);

                        // Assert — should NOT save
                        verify(interviewRepository, never()).save(any(Interview.class));
                }

                @Test
                @DisplayName("Should throw when interview is not found")
                void testTransition_InterviewNotFound() {
                        when(interviewRepository.findById(999L)).thenReturn(Optional.empty());

                        assertThatThrownBy(() -> listener.transitionToInProgress(999L))
                                        .isInstanceOf(RuntimeException.class)
                                        .hasMessageContaining("Interview not found during avatar pipeline completion");
                }
        }

        // ============================================================
        // Event integration — verifies event immutability / structure
        // ============================================================

        @Nested
        @DisplayName("QuestionsCreatedEvent integration")
        class EventIntegrationTests {

                @Test
                @DisplayName("Should process questions in the order provided by the event")
                void testProcessesQuestionsInOrder() {
                        // Arrange — 3 questions
                        QuestionsCreatedEvent event = new QuestionsCreatedEvent(
                                        this, 100L, List.of(201L, 202L, 203L));

                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(questionRepository.findById(202L)).thenReturn(Optional.of(question2));
                        when(questionRepository.findById(203L)).thenReturn(Optional.of(question3));
                        when(textToSpeechService.generateAndSaveAudio(anyString(), anyLong()))
                                        .thenReturn("tts-audio/audio.mp3");
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        listener.onQuestionsCreated(event);

                        // Assert — all 3 questions processed
                        verify(textToSpeechService, times(3)).generateAndSaveAudio(anyString(), anyLong());
                        verify(questionRepository, times(3)).save(any(Question.class));
                        verify(interviewRepository).save(any(Interview.class));
                }

                @Test
                @DisplayName("Should handle single-question event correctly")
                void testSingleQuestion() {
                        QuestionsCreatedEvent event = new QuestionsCreatedEvent(
                                        this, 100L, List.of(201L));

                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(textToSpeechService.generateAndSaveAudio(anyString(), eq(201L)))
                                        .thenReturn("tts-audio/single.mp3");
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        listener.onQuestionsCreated(event);

                        // Assert
                        verify(textToSpeechService, times(1)).generateAndSaveAudio(anyString(), anyLong());
                        verify(interviewRepository).save(argThat(i -> i.getStatus() == InterviewStatus.IN_PROGRESS));
                }
        }

        // ============================================================
        // Edge cases
        // ============================================================

        @Nested
        @DisplayName("Edge cases")
        class EdgeCaseTests {

                @Test
                @DisplayName("Should handle question not found during pipeline gracefully in onQuestionsCreated")
                void testQuestionNotFoundDuringPipeline() {
                        QuestionsCreatedEvent event = new QuestionsCreatedEvent(
                                        this, 100L, List.of(201L, 999L));

                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(questionRepository.findById(999L)).thenReturn(Optional.empty());
                        when(textToSpeechService.generateAndSaveAudio(anyString(), eq(201L)))
                                        .thenReturn("tts-audio/abc.mp3");
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));
                        when(interviewRepository.findById(100L)).thenReturn(Optional.of(testInterview));
                        when(interviewRepository.save(any(Interview.class))).thenAnswer(i -> i.getArgument(0));

                        // Act — should NOT throw (question 999 fails, but 201 succeeds)
                        assertThatCode(() -> listener.onQuestionsCreated(event))
                                        .doesNotThrowAnyException();

                        // Assert — question 201 was processed, interview transitioned
                        verify(questionRepository, times(1)).save(any(Question.class));
                        verify(interviewRepository).save(argThat(i -> i.getStatus() == InterviewStatus.IN_PROGRESS));
                }

                @Test
                @DisplayName("Should handle CachedAvatarService returning null S3 key")
                void testNullS3KeyFromCachedAvatarService() {
                        when(questionRepository.findById(201L)).thenReturn(Optional.of(question1));
                        when(textToSpeechService.generateAndSaveAudio(anyString(), eq(201L)))
                                        .thenReturn(null);
                        when(questionRepository.save(any(Question.class))).thenAnswer(i -> i.getArgument(0));

                        // Act
                        listener.processQuestion(201L);

                        // Assert — saves with null (frontend will show text-only fallback)
                        verify(questionRepository).save(argThat(q -> q.getAudioUrl() == null));
                }
        }
}
