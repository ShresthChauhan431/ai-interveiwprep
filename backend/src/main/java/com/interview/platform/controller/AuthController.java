package com.interview.platform.controller;

import com.interview.platform.dto.*;
import com.interview.platform.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = userService.registerUser(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = userService.authenticateUser(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/profile")
    public ResponseEntity<UserProfile> getProfile(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        UserProfile profile = userService.getUserProfile(userId);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/profile")
    public ResponseEntity<UserProfile> updateProfile(
            HttpServletRequest request,
            @Valid @RequestBody UpdateProfileRequest updateRequest) {
        Long userId = getUserIdFromRequest(request);
        UserProfile profile = userService.updateUserProfile(userId, updateRequest);
        return ResponseEntity.ok(profile);
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        // UserId is set by JwtAuthenticationFilter
        Object userIdAttr = request.getAttribute("userId");
        if (userIdAttr == null) {
            throw new RuntimeException("User not authenticated");
        }
        return (Long) userIdAttr;
    }
}
