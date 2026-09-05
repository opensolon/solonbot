package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DirectoryPickerUtil 单测（不弹真框）：覆盖类路径解析与常量可达性。
 *
 * @author noear
 */
public class DirectoryPickerUtilTest {

    /**
     * 开发模式（surefire 下 classes 目录）能解析出非空 classpath，
     * 且该位置确实包含子进程类
     */
    @Test
    void resolveClasspath_devMode_found() {
        String cp = DirectoryPickerUtil.resolveClasspath();
        assertNotNull("classpath should resolve under test (classes dir mode)", cp);

        File base = new File(cp);
        assertTrue(base.exists(), "resolved classpath should exist");
        File sub = new File(base, DirectoryPickerUtil.SUBPROCESS_ENTRY);
        assertTrue(sub.isFile(), "subprocess class should be reachable from resolved classpath");
    }

    /**
     * isAvailable 不抛异常（返回值随环境变化，不强断言）
     */
    @Test
    void isAvailable_noThrow() {
        DirectoryPickerUtil.isAvailable();
    }

    @Test
    void outputPath_onlyStripsLineBreaks() {
        assertEquals("/Users/test/project/", DirectoryPickerUtil.outputPath("/Users/test/project/\r\n"));
        assertEquals("/Users/test/space ", DirectoryPickerUtil.outputPath("/Users/test/space \n"));
        assertNull(DirectoryPickerUtil.outputPath("\r\n"));
    }

    @Test
    void protocolPath_distinguishesPickAndCancel() throws IOException {
        assertEquals("C:\\work\\目录 ", DirectoryPickerUtil.protocolPath("PICK C:\\work\\目录 \r\n"));
        assertNull(DirectoryPickerUtil.protocolPath("PICK_NONE\n"));
        assertThrows(IOException.class, () -> DirectoryPickerUtil.protocolPath("PICK \n"));
        assertThrows(IOException.class, () -> DirectoryPickerUtil.protocolPath("unexpected\n"));
    }

    @Test
    void macCommand_usesFinderAndStartDirectory() {
        List<String> command = DirectoryPickerUtil.macCommand("Choose \"workspace\"", new File("/Users/test"));
        assertEquals("osascript", command.get(0));
        assertTrue(command.get(2).contains("choose folder"));
        assertTrue(command.get(2).contains("default location"));
        assertTrue(command.get(2).contains("/Users/test"));
        assertTrue(command.get(2).contains("\\\"workspace\\\""));
    }

    @Test
    void windowsCommand_usesStaAndEscapesTitle() {
        List<String> command = DirectoryPickerUtil.windowsCommand("Choose Bob's folder");
        assertEquals("powershell.exe", command.get(0));
        assertTrue(command.contains("-STA"));
        String script = command.get(command.size() - 1);
        assertTrue(script.contains("Choose Bob''s folder"));
        assertTrue(script.contains("BrowseForFolder"));
        assertTrue(script.contains("UTF8Encoding"));
        assertTrue(script.contains("PICK_NONE"));
    }

    @Test
    void unsupportedPlatform_requestsSwingFallback() throws Exception {
        DirectoryPickerUtil.NativePickResult result =
                DirectoryPickerUtil.pickNative("Plan 9", "Choose", 1000L, null);
        assertFalse(result.supported);
        assertNull(result.path);
    }
}
