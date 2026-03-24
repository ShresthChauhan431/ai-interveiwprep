package com.interview.platform.service;

import com.interview.platform.config.JwtTokenProvider;
import com.interview.platform.dto.*;
import com.interview.platform.exception.EmailAlreadyExistsException;
import com.interview.platform.exception.InvalidCredentialsException;
import com.interview.platform.exception.UserNotFoundException;
import com.interview.platform.model.User;
import com.interview.platform.repository.InterviewRepository;
import com.interview.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Tests")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private InterviewRepository interviewRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private UserService userService;

    // ── Shared fixtures ─────────────────────────────────────────

    private User testUser;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setName("John Doe");
        testUser.setEmail("john@example.com");
        testUser.setPassword("encodedPassword123");
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setUpdatedAt(LocalDateTime.now());

        registerRequest = new RegisterRequest("John Doe", "john@example.com", "Password@123");
        loginRequest = new LoginRequest("john@example.com", "Password@123");
    }

    // ============================================================
    // registerUser
    // ============================================================

    @Nested
    @DisplayName("registerUser")
    class RegisterUserTests {

        @Test
        @DisplayName("Should register user successfully with valid data")
        void testRegisterUser_Success() {
            // Arrange
            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(passwordEncoder.encode("Password@123")).thenReturn("encodedPassword123");
            when(userRepository.save(any(User.class))).thenReturn(testUser);
            when(jwtTokenProvider.generateToken("john@example.com", 1L, "USER")).thenReturn("jwt-token-123");

            // Act
            AuthResponse response = userService.registerUser(registerRequest);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getToken()).isEqualTo("jwt-token-123");
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getName()).isEqualTo("John Doe");
            assertThat(response.getEmail()).isEqualTo("john@example.com");
            assertThat(response.getMessage()).isEqualTo("Registration successful");

            verify(userRepository).existsByEmail("john@example.com");
            verify(passwordEncoder).encode("Password@123");
            verify(userRepository).save(any(User.class));
            verify(jwtTokenProvider).generateToken("john@example.com", 1L, "USER");
        }

        @Test
        @DisplayName("Should throw EmailAlreadyExistsException for duplicate email")
        void testRegisterUser_DuplicateEmail() {
            // Arrange
            when(userRepository.existsByEmail("john@example.com")).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> userService.registerUser(registerRequest))
                    .isInstanceOf(EmailAlreadyExistsException.class);

            verify(userRepository).existsByEmail("john@example.com");
            verify(userRepository, never()).save(any(User.class));
            verify(passwordEncoder, never()).encode(anyString());
        }

        @Test
        @DisplayName("Should hash the password before saving")
        void testRegisterUser_PasswordIsEncoded() {
            // Arrange
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode("Password@123")).thenReturn("$2a$10$hashedValue");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User saved = invocation.getArgument(0);
                saved.setId(1L);
                return saved;
            });
            when(jwtTokenProvider.generateToken(anyString(), anyLong(), anyString())).thenReturn("token");

            // Act
            userService.registerUser(registerRequest);

            // Assert — verify the user persisted has the encoded password
            verify(userRepository).save(argThat(user -> "$2a$10$hashedValue".equals(user.getPassword())));
        }
    }

    // ============================================================
    // authenticateUser
    // ============================================================

    @Nested
    @DisplayName("authenticateUser")
    class AuthenticateUserTests {

        @Test
        @DisplayName("Should authenticate successfully with valid credentials")
        void testAuthenticateUser_Success() {
            // Arrange
            when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("Password@123", "encodedPassword123")).thenReturn(true);
            when(jwtTokenProvider.generateToken("john@example.com", 1L, "USER")).thenReturn("jwt-token-456");

            // Act
            AuthResponse response = userService.authenticateUser(loginRequest);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.getToken()).isEqualTo("jwt-token-456");
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getEmail()).isEqualTo("john@example.com");
            assertThat(response.getMessage()).isEqualTo("Login successful");

            verify(userRepository).findByEmail("john@example.com");
            verify(passwordEncoder).matches("Password@123", "encodedPassword123");
        }

        @Test
        @DisplayName("Should throw InvalidCredentialsException when email not found")
        void testAuthenticateUser_EmailNotFound() {
            // Arrange
            when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
            LoginRequest badRequest = new LoginRequest("unknown@example.com", "password");

            // Act & Assert
            assertThatThrownBy(() -> userService.authenticateUser(badRequest))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(passwordEncoder, never()).matches(anyString(), anyString());
        }

        @Test
        @DisplayName("Should throw InvalidCredentialsException when password is wrong")
        void testAuthenticateUser_WrongPassword() {
            // Arrange
            when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));
            when(passwordEncoder.matches("WrongPass", "encodedPassword123")).thenReturn(false);
            LoginRequest badRequest = new LoginRequest("john@example.com", "WrongPass");

            // Act & Assert
            assertThatThrownBy(() -> userService.authenticateUser(badRequest))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(jwtTokenProvider, never()).generateToken(anyString(), anyLong(), anyString());
        }
    }

    // ============================================================
    // getUserProfile
    // ============================================================

    @Nested
    @DisplayName("getUserProfile")
    class GetUserProfileTests {

        @Test
        @DisplayName("Should return user profile with interview count")
        void testGetUserProfile_Success() {
            // Arrange
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(interviewRepository.countByUserId(1L)).thenReturn(3L);

            // Act
            UserProfile profile = userService.getUserProfile(1L);

            // Assert
            assertThat(profile).isNotNull();
            assertThat(profile.getId()).isEqualTo(1L);
            assertThat(profile.getName()).isEqualTo("John Doe");
            assertThat(profile.getEmail()).isEqualTo("john@example.com");
            assertThat(profile.getTotalInterviews()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should throw UserNotFoundException for unknown user ID")
        void testGetUserProfile_UserNotFound() {
            // Arrange
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> userService.getUserProfile(999L))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    // ============================================================
    // updateUserProfile
    // ============================================================

    @Nested
    @DisplayName("updateUserProfile")
    class UpdateUserProfileTests {

        @Test
        @DisplayName("Should update user name and return updated profile")
        void testUpdateUserProfile_Success() {
            // Arrange
            UpdateProfileRequest updateReq = new UpdateProfileRequest();
            updateReq.setName("Jane Doe");

            User updatedUser = new User();
            updatedUser.setId(1L);
            updatedUser.setName("Jane Doe");
            updatedUser.setEmail("john@example.com");
            updatedUser.setCreatedAt(testUser.getCreatedAt());

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(updatedUser);
            when(interviewRepository.countByUserId(1L)).thenReturn(0L);

            // Act
            UserProfile profile = userService.updateUserProfile(1L, updateReq);

            // Assert
            assertThat(profile.getName()).isEqualTo("Jane Doe");
            assertThat(profile.getTotalInterviews()).isZero();
            verify(userRepository).save(argThat(u -> "Jane Doe".equals(u.getName())));
        }

        @Test
        @DisplayName("Should throw UserNotFoundException when updating a non-existent user")
        void testUpdateUserProfile_UserNotFound() {
            // Arrange
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> userService.updateUserProfile(999L, new UpdateProfileRequest()))
                    .isInstanceOf(UserNotFoundException.class);

            verify(userRepository, never()).save(any());
        }
    }
}
