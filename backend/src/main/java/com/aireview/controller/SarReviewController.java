package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.ManualCheckDecisionRequest;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.service.SarReviewService;
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
import java.util.List;
import java.util.Map;

/**
 * SAR 侧审查 REST 入口。与 {@link ReviewController} 结构对称，所有数据走
 * sar_review_tasks / sar_review_audit_logs。前端按 task 的 reviewMode='SAR' 调到这里。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sar/reviews")
@RequiredArgsConstructor
public class SarReviewController {

    private final SarReviewService sarReviewService;

    @PostMapping("/execute")
    public ApiResponse<ReviewTaskDTO> executeReview(
            @RequestParam("file") MultipartFile file,
            @RequestParam("scenarioId") Long scenarioId,
            @RequestParam("selectedModel") String selectedModel,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = sarReviewService.submitReview(file, scenarioId, selectedModel, userId);
            return ApiResponse.success("SAR review task submitted", task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to submit SAR review", e);
            return ApiResponse.error("Failed to submit review: " + e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ReviewTaskDTO> getTask(@PathVariable String taskId,
                                              Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = sarReviewService.getTask(taskId, userId);
            return ApiResponse.success(task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get SAR task", e);
            return ApiResponse.error("Failed to get task: " + e.getMessage());
        }
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResponse<ReviewTaskDTO>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            PageResponse<ReviewTaskDTO> result = sarReviewService.listTasks(userId, page, size, status);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list SAR tasks", e);
            return ApiResponse.error("Failed to list tasks: " + e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/re-review")
    public ApiResponse<ReviewTaskDTO> reReview(@PathVariable String taskId,
                                                Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO newTask = sarReviewService.reReview(taskId, userId);
            return ApiResponse.success("SAR re-review task submitted", newTask);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to re-review SAR task", e);
            return ApiResponse.error("Failed to re-review: " + e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/retry-failed-chunks")
    public ApiResponse<ReviewTaskDTO> retryFailedChunks(@PathVariable String taskId,
                                                        Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = sarReviewService.retryFailedChunks(taskId, userId);
            return ApiResponse.success("SAR retry submitted", task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to retry SAR task", e);
            return ApiResponse.error("Failed to retry: " + e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<Void> cancelTask(@PathVariable String taskId,
                                         Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            sarReviewService.cancelTask(taskId, userId);
            return ApiResponse.success("Task cancelled", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to cancel SAR task", e);
            return ApiResponse.error("Failed to cancel task: " + e.getMessage());
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable String taskId,
                                         Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            sarReviewService.deleteTask(taskId, userId);
            return ApiResponse.success("Task deleted", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete SAR task", e);
            return ApiResponse.error("Failed to delete task: " + e.getMessage());
        }
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats(Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            Map<String, Object> stats = sarReviewService.getStats(userId);
            return ApiResponse.success(stats);
        } catch (Exception e) {
            log.error("Failed to get SAR stats", e);
            return ApiResponse.error("Failed to get stats: " + e.getMessage());
        }
    }

    @PutMapping("/tasks/{taskId}/check-decisions")
    public ApiResponse<ReviewTaskDTO> updateCheckDecision(@PathVariable String taskId,
                                                          @RequestBody ManualCheckDecisionRequest request,
                                                          Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = sarReviewService.updateManualCheckDecision(taskId, userId, request);
            return ApiResponse.success("Manual decision saved", task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update SAR manual check decision", e);
            return ApiResponse.error("Failed to update manual decision: " + e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskId}/audit")
    public ApiResponse<List<Map<String, Object>>> listAuditLogs(@PathVariable String taskId,
                                                                Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            return ApiResponse.success(sarReviewService.listAuditLogs(taskId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to list SAR audit logs", e);
            return ApiResponse.error("Failed to list audit logs: " + e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskId}/export")
    public ResponseEntity<byte[]> exportResult(@PathVariable String taskId,
                                                Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            byte[] excelBytes = sarReviewService.exportResultToExcel(taskId, userId);
            String fileName = URLEncoder.encode("SAR审查意见_" + taskId.substring(0, 8) + ".xlsx",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(excelBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to export SAR result", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/tasks/{taskId}/audit/export")
    public ResponseEntity<byte[]> exportAudit(@PathVariable String taskId,
                                              Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            byte[] json = sarReviewService.exportAuditJson(taskId, userId);
            String fileName = URLEncoder.encode("SAR审计日志_" + taskId.substring(0, 8) + ".json",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to export SAR audit logs", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/tasks/{taskId}/report")
    public ResponseEntity<byte[]> exportReport(@PathVariable String taskId,
                                               Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            byte[] docx = sarReviewService.exportReviewReportDocx(taskId, userId);
            String fileName = URLEncoder.encode("SAR审查报告_" + taskId.substring(0, 8) + ".docx",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    .body(docx);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to export SAR review report", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
