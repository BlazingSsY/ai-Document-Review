package com.aireview.dto;

import lombok.Data;

@Data
public class PageRequest {

    private int page = 1;
    private int size = 10;

    public int getOffset() {
        return (page - 1) * size;
    }
}
