package com.aireview.user.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MemberPermissionUpdateRequest {
    private String role;
    private List<String> featureCodes = new ArrayList<>();
    private List<Long> libraryIds = new ArrayList<>();
}
