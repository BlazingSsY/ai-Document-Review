package com.aireview.controller;

import com.aireview.dto.AiModelConfigDTO;
import com.aireview.dto.ApiResponse;
import com.aireview.dto.PageResponse;
import com.aireview.service.AiModelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-models")
@RequiredArgsConstructor
public class AiModelConfigController {

    private final AiModelService aiModelService;

    @PostMapping
    public ApiResponse<AiModelConfigDTO> create(@Valid @RequestBody AiModelConfigDTO dto) {
        try {
            AiModelConfigDTO result = aiModelService.createConfig(dto);
            return ApiResponse.success("AI model config created", result);
        } catch (Exception e) {
            log.error("Failed to create AI model config", e);
            return ApiResponse.error("Failed to create AI model config: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<AiModelConfigDTO> update(@PathVariable Long id,
                                                 @Valid @RequestBody AiModelConfigDTO dto) {
        try {
            AiModelConfigDTO result = aiModelService.updateConfig(id, dto);
            return ApiResponse.success("AI model config updated", result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update AI model config", e);
            return ApiResponse.error("Failed to update AI model config: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            aiModelService.deleteConfig(id);
            return ApiResponse.success("AI model config deleted", null);
        } catch (Exception e) {
            log.error("Failed to delete AI model config", e);
            return ApiResponse.error("Failed to delete AI model config: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<AiModelConfigDTO> getById(@PathVariable Long id) {
        try {
            AiModelConfigDTO result = aiModelService.getConfigById(id);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get AI model config", e);
            return ApiResponse.error("Failed to get AI model config: " + e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<PageResponse<AiModelConfigDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            PageResponse<AiModelConfigDTO> result = aiModelService.listConfigs(page, size);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Failed to list AI model configs", e);
            return ApiResponse.error("Failed to list AI model configs: " + e.getMessage());
        }
    }
}
