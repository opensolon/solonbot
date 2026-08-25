package org.noear.solon.codecli.portal.web.pipeline;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.ToolEndPayload;
import org.noear.solon.codecli.portal.web.event.payload.ToolLspDiagnostic;
import org.noear.solon.codecli.portal.web.event.payload.ToolLspInfo;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LSP 诊断结构化下发的回归测试。
 *
 * <p>诊断文本是给模型看的，展示层必须把它解析成结构化字段并从 result 中剥离，
 * 否则 write 路径会因 args.content 覆盖 result 而彻底丢失诊断（历史缺陷）。
 *
 * @author noear
 */
public class ToolPresentationFilterLspTest {

    private static final String DIAG_TEXT = "LSP errors detected in this file, please fix:\n"
            + "<diagnostics file=\"src/App.java\">\n"
            + "ERROR [12:5] cannot find symbol: variable foo (javac)\n"
            + "ERROR [20:1] ';' expected\n"
            + "</diagnostics>";

    private WebEvent<?> applyFilter(ToolPresentationFilter filter, ToolEndPayload payload) {
        return filter.apply(WebEvent.of(WebEventNames.TOOL_END, payload));
    }

    private ToolEndPayload editPayload(String result) {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/App.java");
        return ToolEndPayload.builder()
                .callId("c1")
                .name("edit")
                .title("edit")
                .result(result)
                .args(args)
                .build();
    }

    @Test
    public void edit_diagnosticsParsedAndStrippedFromResult() {
        ToolEndPayload payload = editPayload("文件 src/App.java 成功完成 1 处修改。\n\n" + DIAG_TEXT);
        applyFilter(new ToolPresentationFilter(), payload);

        ToolLspInfo lsp = payload.getLsp();
        assertNotNull(lsp, "诊断应被解析为结构化字段");
        assertEquals("src/App.java", lsp.getFile());
        assertEquals(2, lsp.getErrorCount());
        assertFalse(lsp.isTruncated());
        assertEquals(2, lsp.getItems().size());

        ToolLspDiagnostic first = lsp.getItems().get(0);
        assertEquals(12, first.getLine());
        assertEquals(5, first.getColumn());
        assertEquals("cannot find symbol: variable foo", first.getMessage());
        assertEquals("javac", first.getSource());

        //无 source 标注时不应把 message 截断
        assertEquals("';' expected", lsp.getItems().get(1).getMessage());
        assertNull(lsp.getItems().get(1).getSource());

        //展示结果里不应再出现给模型的祈使句与 XML 块
        assertEquals("文件 src/App.java 成功完成 1 处修改。", payload.getResult());
    }

    @Test
    public void write_diagnosticsSurviveContentOverwrite() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "src/App.java");
        args.put("content", "class App {}");
        ToolEndPayload payload = ToolEndPayload.builder()
                .name("write")
                .result("文件成功写入: src/App.java\n\n" + DIAG_TEXT)
                .args(args)
                .build();

        applyFilter(new ToolPresentationFilter(), payload);

        assertNotNull(payload.getLsp(), "write 的诊断必须在 content 覆盖 result 之前抽取");
        assertEquals(2, payload.getLsp().getErrorCount());
        //result 仍按既有行为被替换为文件内容（供语法高亮）
        assertEquals("class App {}", payload.getResult());
    }

    @Test
    public void truncatedBlockKeepsTotalCount() {
        String text = "LSP errors detected in this file, please fix:\n"
                + "<diagnostics file=\"src/App.java\">\n"
                + "ERROR [1:1] e1\n"
                + "... and 8 more\n"
                + "</diagnostics>";
        ToolEndPayload payload = editPayload("文件 src/App.java 成功完成 1 处修改。\n\n" + text);
        applyFilter(new ToolPresentationFilter(), payload);

        assertTrue(payload.getLsp().isTruncated());
        assertEquals(9, payload.getLsp().getErrorCount());
        assertEquals(1, payload.getLsp().getItems().size());
    }

    @Test
    public void noDiagnostics_coveredFileReportsCheckedClean() {
        ToolEndPayload payload = editPayload("文件 src/App.java 成功完成 1 处修改。");
        applyFilter(new ToolPresentationFilter(path -> true), payload);

        ToolLspInfo lsp = payload.getLsp();
        assertNotNull(lsp, "被语言服务器覆盖的文件应上报已检查");
        assertEquals(0, lsp.getErrorCount());
        assertTrue(lsp.getItems().isEmpty());
    }

    @Test
    public void noDiagnostics_uncoveredFileReportsNothing() {
        ToolEndPayload payload = editPayload("文件 src/App.java 成功完成 1 处修改。");
        applyFilter(new ToolPresentationFilter(path -> false), payload);
        assertNull(payload.getLsp(), "无语言服务器覆盖时不应出现 lsp 字段");
    }

    @Test
    public void coveragePredicateThrows_doesNotBreakEvent() {
        ToolEndPayload payload = editPayload("文件 src/App.java 成功完成 1 处修改。");
        WebEvent<?> event = applyFilter(new ToolPresentationFilter(path -> {
            throw new IllegalStateException("boom");
        }), payload);

        assertNotNull(event);
        assertNull(payload.getLsp());
        assertEquals("文件 src/App.java 成功完成 1 处修改。", payload.getResult());
    }

    @Test
    public void otherTools_untouched() {
        Map<String, Object> args = new HashMap<>();
        args.put("command", "ls");
        ToolEndPayload payload = ToolEndPayload.builder()
                .name("bash")
                .result("a.txt")
                .args(args)
                .build();

        applyFilter(new ToolPresentationFilter(path -> true), payload);
        assertNull(payload.getLsp());
        assertEquals("a.txt", payload.getResult());
    }
}
