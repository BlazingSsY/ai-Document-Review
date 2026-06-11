package com.aireview.dto;

import lombok.Data;

@Data
public class ManualCheckDecisionRequest {

    private String checkCode;

    private Integer sourceChunk;

    private String finalStatus;

    private Boolean accepted;

    private String comment;
}
