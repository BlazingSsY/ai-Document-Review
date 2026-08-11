package com.aireview.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SystemFeatureDTO {
    private String code;
    private String name;
    private String description;
}
