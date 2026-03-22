package com.aireview.service;

import com.aireview.websocket.TaskProgressHandler;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for pushing task progress updates to connected WebSocket clients.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final TaskProgressHandler taskProgressHandler;

    /**
     * Send a task status update to all connected clients.
     *
     * @param taskId  the review task ID
     * @param status  the current status (PENDING, PROCESSING, COMPLETED, FAILED)
     * @param message a human-readable progress message
     */
    public void sendTaskUpdate(String taskId, String status, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("status", status);
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());

        String json = JSON.toJSONString(payload);
        taskProgressHandler.broadcast(json);
        log.debug("WebSocket broadcast: taskId={}, status={}", taskId, status);
    }

    /**
     * Send a task progress update with percentage.
     *
     * @param taskId   the review task ID
     * @param status   the current status
     * @param message  progress message
     * @param progress percentage (0-100)
     */
    public void sendTaskProgress(String taskId, String status, String message, int progress) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("status", status);
        payload.put("message", message);
        payload.put("progress", progress);
        payload.put("timestamp", System.currentTimeMillis());

        String json = JSON.toJSONString(payload);
        taskProgressHandler.broadcast(json);
        log.debug("WebSocket broadcast: taskId={}, status={}, progress={}%", taskId, status, progress);
    }
}
