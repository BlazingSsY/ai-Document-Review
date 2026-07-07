package com.aireview.common.websocket;

import com.aireview.common.websocket.TaskProgressHandler;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for pushing task progress updates to connected WebSocket clients.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final TaskProgressHandler taskProgressHandler;

    /**
     * 进行中任务的最近进度（taskId → 0~100），内存态。
     *
     * <p>用途：前端整页硬刷新后内存全没，仅靠 WebSocket 要等下一帧（间隔数秒）才显示
     * 进度。后端进程还活着、还在跑审查，这里就握着当前进度，列表/详情接口据此回填
     * {@code progress} 字段，刷新后立即显示。
     *
     * <p>为什么用内存而非 DB 列：后端一旦重启，审查线程也随之消失（任务实际已死），
     * 此时不应再显示陈旧进度——内存表随重启自然清空，反而比持久化的 DB 列更准确。
     * 终态（COMPLETED/FAILED/CANCELLED）会即时移除，map 只保留真正在跑的任务。
     */
    private final Map<String, Integer> progressByTask = new ConcurrentHashMap<>();

    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED");

    /**
     * Send a task status update to all connected clients.
     *
     * @param taskId  the review task ID
     * @param status  the current status (PENDING, PROCESSING, COMPLETED, FAILED)
     * @param message a human-readable progress message
     */
    public void sendTaskUpdate(String taskId, String status, String message) {
        // 终态即从进度表移除，避免内存无界增长 + 防止已完成任务残留进度。
        if (status != null && TERMINAL_STATUSES.contains(status.toUpperCase())) {
            progressByTask.remove(taskId);
        }
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

        if (progress >= 0) {
            progressByTask.put(taskId, progress);
        }
        String json = JSON.toJSONString(payload);
        taskProgressHandler.broadcast(json);
        log.debug("WebSocket broadcast: taskId={}, status={}, progress={}%", taskId, status, progress);
    }

    /** 最近已知进度（0~100）；非进行中任务返回 null。供列表/详情接口回填，硬刷新后立即显示。 */
    public Integer getProgress(String taskId) {
        return taskId == null ? null : progressByTask.get(taskId);
    }
}
