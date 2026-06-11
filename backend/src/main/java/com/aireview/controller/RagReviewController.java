package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.ManualCheckDecisionRequest;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ReviewTaskDTO;
import com.aireview.service.RagReviewService;
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
 * RAG 侧审查 REST 入口。与 {@link ReviewController} 结构对称，所有数据走
 * rag_review_tasks / rag_review_audit_logs。前端按 task 的 reviewMode='RAG' 调到这里。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag/reviews")
@RequiredArgsConstructor
public class RagReviewController {

    private final RagReviewService ragReviewService;

    @PostMapping("/execute")
    public ApiResponse<ReviewTaskDTO> executeReview(
            @RequestParam("file") MultipartFile file,
            @RequestParam("scenarioId") Long scenarioId,
            @RequestParam("selectedModel") String selectedModel,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = ragReviewService.submitReview(file, scenarioId, selectedModel, userId);
            return ApiResponse.success("RAG review task submitted", task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to submit RAG review", e);
            return ApiResponse.error("Failed to submit review: " + e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ReviewTaskDTO> getTask(@PathVariable String taskId,
                                              Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = ragReviewService.getTask(taskId, userId);
            return ApiResponse.success(task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get RAG task", e);
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
            PageResponse<ReviewTaskDTO> result = ragReviewService.listTasks(userId, page, size, status);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list RAG tasks", e);
            return ApiResponse.error("Failed to list tasks: " + e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/re-review")
    public ApiResponse<ReviewTaskDTO> reReview(@PathVariable String taskId,
                                                Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO newTask = ragReviewService.reReview(taskId, userId);
            return ApiResponse.success("RAG re-review task submitted", newTask);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to re-review RAG task", e);
            return ApiResponse.error("Failed to re-review: " + e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/retry-failed-chunks")
    public ApiResponse<ReviewTaskDTO> retryFailedChunks(@PathVariable String taskId,
                                                        Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = ragReviewService.retryFailedChunks(taskId, userId);
            return ApiResponse.success("RAG retry submitted", task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to retry RAG task", e);
            return ApiResponse.error("Failed to retry: " + e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<Void> cancelTask(@PathVariable String taskId,
                                         Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ragReviewService.cancelTask(taskId, userId);
            return ApiResponse.success("Task cancelled", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to cancel RAG task", e);
            return ApiResponse.error("Failed to cancel task: " + e.getMessage());
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable String taskId,
                                         Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ragReviewService.deleteTask(taskId, userId);
            return ApiResponse.success("Task deleted", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete RAG task", e);
            return ApiResponse.error("Failed to delete task: " + e.getMessage());
        }
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats(Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            Map<String, Object> stats = ragReviewService.getStats(userId);
            return ApiResponse.success(stats);
        } catch (Exception e) {
            log.error("Failed to get RAG stats", e);
            return ApiResponse.error("Failed to get stats: " + e.getMessage());
        }
    }

    @PutMapping("/tasks/{taskId}/check-decisions")
    public ApiResponse<ReviewTaskDTO> updateCheckDecision(@PathVariable String taskId,
                                                          @RequestBody ManualCheckDecisionRequest request,
                                                          Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ReviewTaskDTO task = ragReviewService.updateManualCheckDecision(taskId, userId, request);
            return ApiResponse.success("Manual decision saved", task);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update RAG manual check decision", e);
            return ApiResponse.error("Failed to update manual decision: " + e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskId}/audit")
    public ApiResponse<List<Map<String, Object>>> listAuditLogs(@PathVariable String taskId,
                                                                Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            return ApiResponse.success(ragReviewService.listAuditLogs(taskId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to list RAG audit logs", e);
            return ApiResponse.error("Failed to list audit logs: " + e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskId}/export")
    public ResponseEntity<byte[]> exportResult(@PathVariable String taskId,
                                                Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            byte[] excelBytes = ragReviewService.exportResultToExcel(taskId, userId);
            String fileName = URLEncoder.encode("RAG审查意见_" + taskId.substring(0, 8) + ".xlsx",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(excelBytes);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to export RAG result", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/tasks/{taskId}/audit/export")
    public ResponseEntity<byte[]> exportAudit(@PathVariable String taskId,
                                              Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            byte[] json = ragReviewService.exportAuditJson(taskId, userId);
            String fileName = URLEncoder.encode("RAG审计日志_" + taskId.substring(0, 8) + ".json",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to export RAG audit logs", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/tasks/{taskId}/report")
    public ResponseEntity<byte[]> exportReport(@PathVariable String taskId,
                                               Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            byte[] docx = ragReviewService.exportReviewReportDocx(taskId, userId);
            String fileName = URLEncoder.encode("RAG审查报告_" + taskId.substring(0, 8) + ".docx",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + fileName)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    .body(docx);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to export RAG review report", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
