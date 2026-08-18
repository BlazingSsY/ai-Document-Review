package com.aireview.review.core;

import com.aireview.review.chunk.entity.ReviewTask;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类别隔离是「新增审查功能不影响既有功能」的底线：新功能的任务不能出现在旧功能的
 * 列表里，也不能把旧功能的数字卡撑大。这里锁住谓词的三种形态。
 */
class ReviewCategoryQueryTest {

    private static final String DEFAULT_CATEGORY = "ENV_TEST_OUTLINE";

    /** Lambda 列名解析平时由 mapper 扫描在 Spring 启动时建好；纯单测里手工初始化一次。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
    }

    @Test
    void scopesTasksToTheRequestedCategory() {
        LambdaQueryWrapper<ReviewTask> query = new LambdaQueryWrapper<>();
        ReviewCategoryQuery.filterByCategory(query, ReviewTask::getReviewCategory,
                "TEST_REPORT", DEFAULT_CATEGORY);

        String sql = query.getSqlSegment();
        assertThat(sql).contains("review_category");
        // 非默认类别不放行 NULL，否则历史任务会漏进新功能的列表。
        assertThat(sql).doesNotContain("IS NULL");
        assertThat(query.getParamNameValuePairs()).containsValue("TEST_REPORT");
    }

    @Test
    void treatsLegacyNullCategoryAsTheDefaultFeature() {
        LambdaQueryWrapper<ReviewTask> query = new LambdaQueryWrapper<>();
        ReviewCategoryQuery.filterByCategory(query, ReviewTask::getReviewCategory,
                DEFAULT_CATEGORY, DEFAULT_CATEGORY);

        // 迁移前入库的任务 review_category 为 NULL，语义上属于默认类别；筛默认类别时
        // 必须把它们算进来，不然老任务会在自己的列表里凭空消失。
        assertThat(query.getSqlSegment()).contains("IS NULL");
        assertThat(query.getParamNameValuePairs()).containsValue(DEFAULT_CATEGORY);
    }

    @Test
    void addsNoConditionWhenCategoryIsAbsent() {
        LambdaQueryWrapper<ReviewTask> blank = new LambdaQueryWrapper<>();
        ReviewCategoryQuery.filterByCategory(blank, ReviewTask::getReviewCategory,
                "  ", DEFAULT_CATEGORY);
        assertThat(blank.getSqlSegment()).isEmpty();

        LambdaQueryWrapper<ReviewTask> missing = new LambdaQueryWrapper<>();
        ReviewCategoryQuery.filterByCategory(missing, ReviewTask::getReviewCategory,
                null, DEFAULT_CATEGORY);
        assertThat(missing.getSqlSegment()).isEmpty();
    }
}
