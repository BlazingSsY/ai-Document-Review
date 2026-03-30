package com.aireview.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScenarioDTO {

    private Long id;
    private String name;
    private String description;
    private Long creatorId;
    private List<Long> libraryIds;
}
