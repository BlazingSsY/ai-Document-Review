package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * Execute a review: upload a Word document and start AI review.
     */
    @PostMapping("/execute")
    public ApiResponse<ReviewTaskDTO> executeReview(
            @RequestParam("file") MultipartFile file,
            @RequestParam("scenarioId") Long scenarioId,
            @RequestParam("selectedModel") String selectedModel,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = reviewService.submitReview(file, scenarioId, selectedModel, userId);
            return ApiResponse.success("Review task submitted", task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to submit review", e);
            return ApiResponse.error("Failed to submit review: " + e.getMessage());
        }
    }

    /**
     * Get a review task by ID.
     */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ReviewTaskDTO> getTask(@PathVariable String taskId,
                                              Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = reviewService.getTask(taskId, userId);
            return ApiResponse.success(task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get task", e);
            return ApiResponse.error("Failed to get task: " + e.getMessage());
        }
    }

    /**
     * List review tasks for the current user.
     */
    @GetMapping("/tasks")
    public ApiResponse<PageResponse<ReviewTaskDTO>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            PageResponse<ReviewTaskDTO> result = reviewService.listTasks(userId, page, size, status);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list tasks", e);
            return ApiResponse.error("Failed to list tasks: " + e.getMessage());
        }
    }

    /**
     * Get review statistics for the current user.
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats(Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            Map<String, Object> stats = reviewService.getStats(userId);
            return ApiResponse.success(stats);
        } catch (Exception e) {
            log.error("Failed to get stats", e);
            return ApiResponse.error("Failed to get stats: " + e.getMessage());
        }
    }
}
