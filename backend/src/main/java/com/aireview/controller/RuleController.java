package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.RuleDTO;
import com.aireview.service.RuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;

    @PostMapping("/upload")
    public ApiResponse<RuleDTO> uploadRule(@RequestParam("file") MultipartFile file,
                                           Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            RuleDTO rule = ruleService.uploadRule(file, userId);
            return ApiResponse.success("Rule uploaded successfully", rule);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Rule upload failed", e);
            return ApiResponse.error("Rule upload failed: " + e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<RuleDTO>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            PageResponse<RuleDTO> result = ruleService.listRules(page, size, userId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list rules", e);
            return ApiResponse.error("Failed to list rules: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<RuleDTO> getRule(@PathVariable Long id) {
        try {
            RuleDTO rule = ruleService.getRuleById(id);
            return ApiResponse.success(rule);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get rule", e);
            return ApiResponse.error("Failed to get rule: " + e.getMessage());
        }
    }
}
