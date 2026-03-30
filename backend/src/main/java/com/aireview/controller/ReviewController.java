package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
     * Re-review: create a new task using the same file, scenario, and model.
     */
    @PostMapping("/tasks/{taskId}/re-review")
    public ApiResponse<ReviewTaskDTO> reReview(@PathVariable String taskId,
                                                Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO newTask = reviewService.reReview(taskId, userId);
            return ApiResponse.success("Re-review task submitted", newTask);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to re-review task", e);
            return ApiResponse.error("Failed to re-review: " + e.getMessage());
        }
    }

    /**
     * Cancel a review task.
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<Void> cancelTask(@PathVariable String taskId,
                                         Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            reviewService.cancelTask(taskId, userId);
            return ApiResponse.success("Task cancelled", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to cancel task", e);
            return ApiResponse.error("Failed to cancel task: " + e.getMessage());
        }
    }

    /**
     * Delete a review task.
     */
    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable String taskId,
                                         Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            reviewService.deleteTask(taskId, userId);
            return ApiResponse.success("Task deleted", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete task", e);
            return ApiResponse.error("Failed to delete task: " + e.getMessage());
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

    /**
     * Export review results as Excel file.
     */
    @GetMapping("/tasks/{taskId}/export")
    public ResponseEntity<byte[]> exportResult(@PathVariable String taskId,
                                                Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            byte[] excelBytes = reviewService.exportResultToExcel(taskId, userId);
            String fileName = URLEncoder.encode("审查意见_" + taskId.substring(0, 8) + ".xlsx",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(excelBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to export result", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
