package com.aireview.dto;

import lombok.Data;

@Data
public class ManualCheckDecisionRequest {

    private String checkCode;

    /**
     * 优先定位键：RAG 一个检查项可展开成多条违规，每条带稳定 finding_id。
     * 传了它就按 finding_id 精确命中；不传（chunk 侧旧调用）回退 checkCode + sourceChunk。
     */
    private String findingId;

    private Integer sourceChunk;

    private String finalStatus;

    private Boolean accepted;

    private String comment;
}
