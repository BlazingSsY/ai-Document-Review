package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_library_assignment")
public class UserRuleAssignment {

    private Long userId;

    private Long libraryId;
}
