package com.interview.platform.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * User entity for authentication and profile management.
 *
 * <h3>P2-13 Fix:</h3>
 * <p>Added {@code role} field to support role-based access control (RBAC).
 * Previously, {@code JwtAuthenticationFilter} created authentication tokens
 * with {@code Collections.emptyList()} (no granted authorities), making the
 * {@code ADMIN} role referenced in {@code SecurityConfig} unassignable.
 * Now the role is stored on the user entity, included as a JWT claim during
 * token generation, and extracted in the authentication filter to populate
 * {@code GrantedAuthority}.</p>
 *
 * <p>Default role is {@code "USER"}. Promotion to {@code "ADMIN"} must be
 * done via direct database update or a future admin API endpoint.</p>
 */
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_email", columnList = "email")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    @Column(nullable = false, length = 100)
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Column(nullable = false)
    private String password;

    /**
     * P2-13: User role for RBAC. Defaults to "USER".
     *
     * <p>Valid values: {@code USER}, {@code ADMIN}.</p>
     * <p>The role is included as a JWT claim during token generation
     * ({@code JwtTokenProvider.generateToken()}) and extracted in
     * {@code JwtAuthenticationFilter} to populate Spring Security's
     * {@code GrantedAuthority} with {@code ROLE_<role>}.</p>
     *
     * <p>The {@code ADMIN} role grants access to:</p>
     * <ul>
     *   <li>Actuator endpoints ({@code /actuator/**})</li>
     *   <li>Future admin CRUD endpoints (e.g., job role management)</li>
     * </ul>
     */
    @Column(nullable = false, length = 20)
    private String role = "USER";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Resume> resumes = new ArrayList<>();

    public User() {
    }

    public User(Long id, String name, String email, String password, LocalDateTime createdAt, LocalDateTime updatedAt,
            List<Resume> resumes) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.resumes = resumes;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Resume> getResumes() {
        return resumes;
    }

    public void setResumes(List<Resume> resumes) {
        this.resumes = resumes;
    }
}
