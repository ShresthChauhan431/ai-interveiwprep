package com.interview.platform.controller;

import com.interview.platform.model.JobRole;
import com.interview.platform.repository.JobRoleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for job role operations.
 */
@RestController
@RequestMapping("/api/job-roles")
public class JobRoleController {

    private final JobRoleRepository jobRoleRepository;

    public JobRoleController(JobRoleRepository jobRoleRepository) {
        this.jobRoleRepository = jobRoleRepository;
    }

    /**
     * Get all active job roles.
     *
     * @return list of active job roles
     */
    @GetMapping
    public ResponseEntity<List<JobRole>> getActiveJobRoles() {
        List<JobRole> roles = jobRoleRepository.findAllByActiveTrue();
        return ResponseEntity.ok(roles);
    }
}
