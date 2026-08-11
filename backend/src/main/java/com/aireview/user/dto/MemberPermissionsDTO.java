package com.aireview.user.dto;

import lombok.Data;

import java.util.List;

@Data
public class MemberPermissionsDTO {
    private Long userId;
    private String role;
    private List<String> featureCodes;
    private List<Long> libraryIds;
}
