package com.aireview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("rag_user_library_assignment")
public class RagUserRuleAssignment {

    private Long userId;

    private Long libraryId;
}
