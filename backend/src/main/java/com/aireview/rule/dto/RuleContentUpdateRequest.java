package com.aireview.rule.dto;

import lombok.Data;

import java.util.List;

/**
 * 编辑已上传规则的「内容」请求（三条管线共用）。
 *
 * <ul>
 *   <li>{@code content} 为 null 时不改动规则正文；非 null 时整体替换。</li>
 *   <li>{@code checks} 为 null 时不改动原子检查项；非 null 时（含空列表）整体替换该规则的检查项。</li>
 * </ul>
 */
@Data
public class RuleContentUpdateRequest {
    private String content;
    private List<RuleCheckDTO> checks;
}
