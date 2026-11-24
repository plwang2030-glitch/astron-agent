package com.iflytek.astron.console.hub.service.agentbuilder;

import org.apache.commons.lang3.StringUtils;

/**
 * Minimal reader for the {@code name} / {@code description} keys of a SKILL.md YAML frontmatter.
 * Same line-based approach as {@code SkillFileService}; only single-line values are supported.
 */
public record SkillFrontmatter(String name, String description) {

    public static SkillFrontmatter parse(String content) {
        if (StringUtils.isBlank(content)) {
            return new SkillFrontmatter(null, null);
        }
        String[] lines = content.replace("\r\n", "\n").strip().split("\n");
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            return new SkillFrontmatter(null, null);
        }
        String name = null;
        String description = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ("---".equals(line.trim())) {
                return new SkillFrontmatter(name, description);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = clean(line.substring(colon + 1));
            if ("name".equals(key)) {
                name = value;
            } else if ("description".equals(key)) {
                description = value;
            }
        }
        // Frontmatter never closed.
        return new SkillFrontmatter(null, null);
    }

    private static String clean(String raw) {
        String value = StringUtils.trimToNull(raw);
        if (value != null && value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            value = StringUtils.trimToNull(value.substring(1, value.length() - 1));
        }
        return value;
    }
}
