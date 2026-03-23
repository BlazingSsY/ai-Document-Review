package com.aireview.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserDTO {
    private Long id;
    private String email;
    private String name;
    private String role;
    private LocalDateTime createdAt;
}
