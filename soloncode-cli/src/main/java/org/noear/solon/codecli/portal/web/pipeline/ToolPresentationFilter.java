package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.ToolEndPayload;
import org.noear.solon.core.util.Assert;

import java.util.*;

/**
 * 工具输出专用格式化过滤器（处理 Git Diff 重建、参数瘦身）
 *
 * @author noear
 */
public class ToolPresentationFilter {

    public WebEvent<?> apply(WebEvent<?> event) {
        if (event == null || !WebEventNames.TOOL_END.equals(event.getEvent())) {
            return event;
        }

        if (!(event.getPayload() instanceof ToolEndPayload)) {
            return event;
        }

        ToolEndPayload payload = (ToolEndPayload) event.getPayload();
        Map<String, Object> args = payload.getArgs();

        if (args != null) {
            // 如果 args 是不可变 Map（如 Collections.unmodifiableMap），包装为可变 HashMap 避免修改报错
            if (!(args instanceof HashMap)) {
                args = new HashMap<>(args);
                payload.setArgs(args);
            }

            if (TodoTalent.TOOL_TODOWRITE.equals(payload.getName())) {
                String todos = (String) args.get(TodoTalent.PARAM_TODOS);
                if (Assert.isNotEmpty(todos)) {
                    payload.setResult(todos);
                    args.remove(TodoTalent.PARAM_TODOS);
                }
            }

            if (TerminalTalent.TOOL_WRITE.equals(payload.getName())) {
                String content = (String) args.get(TerminalTalent.PARAM_CONTENT);
                if (Assert.isNotEmpty(content)) {
                    payload.setResult(content);
                    args.remove(TerminalTalent.PARAM_CONTENT);
                }
            }

            fillEditDiff(payload, args);
        }

        return event;
    }

    @SuppressWarnings("unchecked")
    private void fillEditDiff(ToolEndPayload payload, Map<String, Object> args) {
        if (args == null || !(args.get(TerminalTalent.PARAM_EDITS) instanceof List)) {
            return;
        }

        List<?> edits = (List<?>) args.get(TerminalTalent.PARAM_EDITS);
        if (edits.isEmpty()) {
            return;
        }

        StringBuilder diff = new StringBuilder();
        for (Object item : edits) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> edit = (Map<String, Object>) item;

            int startLine = asInt(edit.get("old_StrStartLine"), 0);
            List<String> oldLines = splitLines(asString(edit.get("old_str")));
            List<String> newLines = splitLines(asString(edit.get("new_str")));

            diff.append("@@ -").append(startLine).append(',').append(oldLines.size())
                    .append(" +").append(startLine).append(',').append(newLines.size())
                    .append(" @@\n");

            for (String line : oldLines) {
                diff.append('-').append(line).append('\n');
            }
            for (String line : newLines) {
                diff.append('+').append(line).append('\n');
            }
        }

        if (diff.length() > 0) {
            String diffStr = diff.toString();
            payload.setDiff(diffStr);
            args.put("diff", diffStr);
            args.remove(TerminalTalent.PARAM_EDITS);
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String[] raw = text.split("\r?\n", -1);
        List<String> list = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            if (i == raw.length - 1 && raw[i].isEmpty()) {
                break;
            }
            list.add(raw[i]);
        }
        return list;
    }
}
