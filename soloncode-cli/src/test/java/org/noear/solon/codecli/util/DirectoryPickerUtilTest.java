package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import java.io.File;

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
}
