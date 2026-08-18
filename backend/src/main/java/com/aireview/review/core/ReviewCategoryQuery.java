package com.aireview.review.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

/**
 * 按审查类别（业务域）收敛任务查询的公共谓词。
 *
 * <p>每个审查功能（环境试验大纲、试验报告……）都是一个独立的业务域，各自的任务列表与
 * 统计必须互不可见——否则新增一个功能就会把它的任务混进已有功能的列表里，并把数字卡
 * 撑大。CHUNK 与 SAR 两条管线的任务表结构一致，故谓词写成泛型放在这里共用。
 *
 * <p>历史任务的 {@code review_category} 为 NULL，语义上属于默认类别（见
 * {@code ReviewFeatureRegistry#resolveStoredCategory}）。因此筛选默认类别时必须把 NULL
 * 一并算进来，不然老任务会在自己的列表里凭空消失。
 */
public final class ReviewCategoryQuery {

    private ReviewCategoryQuery() {
    }

    /**
     * 给查询追加类别条件。
     *
     * @param category        目标类别；null / 空白表示不限类别（跨类别视图）
     * @param defaultCategory 注册表里的默认类别，用于判断是否需要兼容 NULL 历史数据
     */
    public static <T> void filterByCategory(LambdaQueryWrapper<T> query,
                                            SFunction<T, String> categoryColumn,
                                            String category,
                                            String defaultCategory) {
        if (category == null || category.isBlank()) {
            return;
        }
        if (category.equals(defaultCategory)) {
            query.and(wrapper -> wrapper.eq(categoryColumn, category).or().isNull(categoryColumn));
        } else {
            query.eq(categoryColumn, category);
        }
    }
}
