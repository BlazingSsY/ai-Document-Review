package com.aireview.controller;

import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.dto.ScenarioCreateRequest;
import com.aireview.dto.ScenarioDTO;
import com.aireview.service.ScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/scenarios")
@RequiredArgsConstructor
public class ScenarioController {

    private final ScenarioService scenarioService;

    @PostMapping
    public ApiResponse<ScenarioDTO> createScenario(@Valid @RequestBody ScenarioCreateRequest request,
                                                    Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ScenarioDTO scenario = scenarioService.createScenario(request, userId);
            return ApiResponse.success("Scenario created", scenario);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create scenario", e);
            return ApiResponse.error("Failed to create scenario: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<ScenarioDTO> getScenario(@PathVariable Long id) {
        try {
            ScenarioDTO scenario = scenarioService.getScenarioById(id);
            return ApiResponse.success(scenario);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get scenario", e);
            return ApiResponse.error("Failed to get scenario: " + e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<ScenarioDTO>> listScenarios(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            PageResponse<ScenarioDTO> result = scenarioService.listScenarios(page, size, userId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list scenarios", e);
            return ApiResponse.error("Failed to list scenarios: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<ScenarioDTO> updateScenario(@PathVariable Long id,
                                                    @Valid @RequestBody ScenarioCreateRequest request,
                                                    Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            ScenarioDTO scenario = scenarioService.updateScenario(id, request, userId);
            return ApiResponse.success("Scenario updated", scenario);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update scenario", e);
            return ApiResponse.error("Failed to update scenario: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteScenario(@PathVariable Long id, Authentication authentication) {
        try {
            Long userId = (Long) authentication.getPrincipal();
            scenarioService.deleteScenario(id, userId);
            return ApiResponse.success("Scenario deleted", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete scenario", e);
            return ApiResponse.error("Failed to delete scenario: " + e.getMessage());
        }
    }
}
