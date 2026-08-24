package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 旧版工作区内日志清理的行为校验
 */
public class LogDirUtilLegacyCleanTest {

    @Test
    public void case1_delete_direct_log_files_and_remove_empty_dir() throws Exception {
        Path ws = Files.createTempDirectory("sc-ws-");
        Path logs = ws.resolve(".soloncode/logs");
        Files.createDirectories(logs);

        Files.write(logs.resolve("soloncode.log"), "x".getBytes());
        Files.write(logs.resolve("soloncode_2026-08-01_0.log"), "x".getBytes());

        LogDirUtil.cleanLegacyLogs(ws.toString());

        assertFalse(logs.toFile().exists(), "日志文件全部删除后，空目录应被移除");
        assertTrue(ws.resolve(".soloncode").toFile().isDirectory(), ".soloncode 目录必须保留");
    }

    @Test
    public void case2_keep_non_log_files_and_subdirs() throws Exception {
        Path ws = Files.createTempDirectory("sc-ws-");
        Path logs = ws.resolve(".soloncode/logs");
        Files.createDirectories(logs);

        Files.write(logs.resolve("soloncode.log"), "x".getBytes());
        Files.write(logs.resolve("notes.txt"), "x".getBytes());
        //孙级：子目录内的 .log 不应被触碰
        Path sub = logs.resolve("abc123-myapp");
        Files.createDirectories(sub);
        Files.write(sub.resolve("soloncode.log"), "x".getBytes());

        LogDirUtil.cleanLegacyLogs(ws.toString());

        assertFalse(logs.resolve("soloncode.log").toFile().exists(), "直接子级 .log 应删除");
        assertTrue(logs.resolve("notes.txt").toFile().exists(), "非 .log 文件应保留");
        assertTrue(sub.resolve("soloncode.log").toFile().exists(), "子目录内的日志不应被删除");
        assertTrue(logs.toFile().isDirectory(), "目录非空时应保留");
    }

    @Test
    public void case3_no_logs_dir_is_noop() throws Exception {
        Path ws = Files.createTempDirectory("sc-ws-");
        LogDirUtil.cleanLegacyLogs(ws.toString());
        LogDirUtil.cleanLegacyLogs(null);
        LogDirUtil.cleanLegacyLogs("");
        assertTrue(ws.toFile().isDirectory());
    }

    @Test
    public void case4_skip_when_workspace_is_user_home() throws Exception {
        //工作区 == 用户主目录时，.soloncode/logs 即新版日志根目录，必须整体跳过
        File root = LogDirUtil.logRootDir();
        boolean created = false;
        if (root.exists() == false) {
            created = root.mkdirs();
        }

        Path probe = root.toPath().resolve("legacy-guard-probe.log");
        Files.write(probe, "x".getBytes());
        try {
            LogDirUtil.cleanLegacyLogs(System.getProperty("user.home"));
            assertTrue(probe.toFile().exists(), "新版日志根目录下的文件不应被清理");
        } finally {
            Files.deleteIfExists(probe);
            if (created) {
                root.delete();
            }
        }
    }
}
