package com.aireview.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_feature_assignment")
public class UserFeatureAssignment {

    private Long userId;

    private String featureCode;
}
