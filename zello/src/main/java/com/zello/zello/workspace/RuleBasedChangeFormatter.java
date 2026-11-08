package com.zello.zello.workspace;

import java.util.Locale;
import org.springframework.stereotype.Component;

/** Deterministic wording only. An analyzer can later replace this formatter without changing event handling. */
@Component
public class RuleBasedChangeFormatter {
    public String teamSummary(String title, String body, String provided) {
        if (provided != null && !provided.isBlank()) return provided.strip();
        return body == null || body.isBlank() ? title.strip() : summary(body);
    }
    public String category(String title) {
        String value = title.strip().toLowerCase(Locale.ROOT);
        if (value.matches("^(fix|bugfix|hotfix)(\\([^)]*\\))?[!:].*") || value.startsWith("오류 수정")) return "FIX";
        if (value.matches("^feat(\\([^)]*\\))?[!:].*") || value.startsWith("새 기능")) return "FEATURE";
        return "IMPROVE";
    }
    public String summary(String description) {
        if (description == null || description.isBlank()) return "등록된 상세 설명이 없습니다.";
        String cleaned = description.replaceAll("(?s)<!--.*?-->", "").strip();
        if (cleaned.isEmpty()) return "등록된 상세 설명이 없습니다.";
        return cleaned.length() > 1200 ? cleaned.substring(0, 1200) + "…" : cleaned;
    }
}
