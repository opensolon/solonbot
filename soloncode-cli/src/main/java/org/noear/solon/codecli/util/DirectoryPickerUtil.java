/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 系统级“选择目录”工具：在宿主机桌面弹出原生目录选择框，返回用户选中的绝对路径。
 * <p>
 * 与 {@link OsOpenUtil} 同属“CLI 进程直接调宿主 GUI 能力”的场景（soloncode web 本就运行在用户桌面）。
 * <p>
 * <b>为何用子进程：</b>Solon 框架类初始化时会把 {@code java.awt.headless} 默认置为 true，
 * CLI 宿主 JVM 内 AWT 因此永远是 headless、无法直接弹框；且该状态在 Toolkit 首次加载后不可逆。
 * 故在子 JVM 中以 {@code -Djava.awt.headless=false} 运行 {@link DirectoryPickerSubprocess}，
 * 由它弹 {@code JFileChooser(DIRECTORIES_ONLY)}，把结果按协议写 stdout 后退出：
 * <ul>
 *     <li>{@code PICK <abs-path>}：用户选中目录</li>
 *     <li>{@code PICK_NONE}：用户取消</li>
 * </ul>
 * 子进程异常（无显示器、AWT 初始化失败等）时以非 0 退出码结束并输出错误信息。
 * <p>
 * <b>类路径定位：</b>优先系统属性 {@code soloncode.pick.jar}（显式指定）；
 * 开发模式（classes 目录）直接用该目录；fat jar 模式下 classpath 不含 BOOT-INF，
 * 故将子进程类从自身 jar 抽取到临时目录后再启动。
 *
 * @author noear
 * @since 3.9.1
 */
public class DirectoryPickerUtil {
    /**
     * 子进程最长存活（用户慢慢选也够；超时强制销毁）
     */
    public static final long DEFAULT_TIMEOUT_MS = 300_000L;

    /**
     * 子进程主类的全限定名（与 {@link DirectoryPickerSubprocess} 保持一致）
     */
    static final String SUBPROCESS_CLASS = "org.noear.solon.codecli.util.DirectoryPickerSubprocess";

    /**
     * 子进程类在 jar 内的相对路径（fat jar 中带 BOOT-INF/classes 前缀，故用 endsWith 匹配）
     */
    static final String SUBPROCESS_ENTRY = "org/noear/solon/codecli/util/DirectoryPickerSubprocess.class";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(DirectoryPickerUtil.class);

    private static void warn(String msg) {
        try {
            LOG.warn("[DirectoryPicker] " + msg);
        } catch (Throwable ignored) {
            // 日志系统不可用时静默
        }
    }

    private DirectoryPickerUtil() {
    }

    /**
     * 弹出目录选择框（默认超时，起始目录 user.home）
     *
     * @param title 对话框标题
     * @return 选中的目录绝对路径；用户取消或超时返回 null
     * @throws IOException 子进程启动或通信失败
     */
    public static String pick(String title) throws IOException {
        return pick(title, DEFAULT_TIMEOUT_MS, null);
    }

    /**
     * 弹出目录选择框，阻塞直到用户选择/取消或超时
     *
     * @param title     对话框标题（null 视为空）
     * @param timeoutMs 最长等待毫秒数；超时销毁子进程并返回 null
     * @param startDir  起始目录（null 或不存在时由子进程回落 user.home）
     * @return 选中的目录绝对路径；用户取消或超时返回 null
     * @throws IOException 子进程启动或通信失败
     */
    public static String pick(String title, long timeoutMs, File startDir) throws IOException {
        String cp = resolveClasspath();
        if (cp == null) {
            throw new IOException("Cannot locate directory-picker classpath (try -Dsoloncode.pick.jar=<path>)");
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin());
        cmd.add("-Djava.awt.headless=false");
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(SUBPROCESS_CLASS);
        cmd.add(title == null ? "" : title);
        cmd.add(startDir != null && startDir.isDirectory() ? startDir.getAbsolutePath() : "");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        final Process p = pb.start();

        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被销毁时流关闭，正常
                }
            }
        }, "soloncode-pick-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                // 超时：用户可能走开了，杀掉子进程（对话框随之消失），本次视为取消
                p.destroyForcibly();
                return null;
            }
            reader.join(3000);

            String text;
            synchronized (out) {
                text = out.toString();
            }
            for (String line : text.split("\n")) {
                if (line.startsWith("PICK ")) {
                    return line.substring("PICK ".length()).trim();
                }
                if (line.equals("PICK_NONE")) {
                    return null;
                }
            }
            // 未输出协议行即退出：视为失败，透传子进程输出便于诊断
            throw new IOException("Directory picker subprocess failed (exit=" + p.exitValue() + "): " + text.trim());
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 判断当前运行环境是否可能弹框（宿主 JVM 自身 headless 不可信，用环境启发式判断）：
     * Windows/macOS 视为有桌面；Linux 需 DISPLAY 或 WAYLAND_DISPLAY。
     * 误报（如 SSH 无 GUI 的 mac）会在真正弹框时以子进程错误暴露。
     */
    public static boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return true;
        }
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isEmpty()) {
            return true;
        }
        String wayland = System.getenv("WAYLAND_DISPLAY");
        return wayland != null && !wayland.isEmpty();
    }

    /**
     * 当前 JVM 的 java 可执行文件绝对路径
     */
    private static String javaBin() throws IOException {
        File javaHome = new File(System.getProperty("java.home"));
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        File bin = new File(javaHome, "bin" + File.separator + name);
        if (bin.isFile()) {
            return bin.getAbsolutePath();
        }
        throw new IOException("Cannot locate java executable under " + javaHome.getAbsolutePath());
    }

    /**
     * 解析子进程类路径：
     * <ol>
     *     <li>系统属性 {@code soloncode.pick.jar} 指定的文件/jar</li>
     *     <li>当前类 code source 为目录（开发模式）→ 该目录即 classpath 根</li>
     *     <li>当前类 code source 为 fat jar → 从中抽出子进程类到临时目录</li>
     * </ol>
     *
     * @return 可作 {@code -cp} 的路径；定位失败返回 null
     */
    static String resolveClasspath() {
        String explicit = System.getProperty("soloncode.pick.jar");
        if (explicit != null) {
            File f = new File(explicit);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }

        File codeSource;
        try {
            codeSource = new File(DirectoryPickerUtil.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Throwable e) {
            codeSource = null;
        }
        // fat-jar 自定义 ClassLoader 下 codeSource 可能拿不到：改用类资源 URL 解析（jar:file:/..!/path）
        if (codeSource == null || !codeSource.exists()) {
            codeSource = locateSelfJarByResource();
        }
        if (codeSource == null || !codeSource.exists()) {
            warn("resolveClasspath: codeSource and resource-locate both failed");
            return null;
        }
        if (codeSource.isDirectory()) {
            // 开发模式：classes 目录本身即 classpath 根
            return codeSource.getAbsolutePath();
        }

        // fat jar 模式：jar 不能直接作 classpath（类在 BOOT-INF/classes 下），抽取到临时目录
        ZipFile zip = null;
        try {
            zip = new ZipFile(codeSource);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(SUBPROCESS_ENTRY)) {
                    continue;
                }
                Path tmp = Files.createTempDirectory("soloncode-pick");
                tmp.toFile().deleteOnExit();
                Path target = tmp.resolve(SUBPROCESS_ENTRY);
                Files.createDirectories(target.getParent());
                InputStream in = zip.getInputStream(entry);
                try {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    in.close();
                }
                return tmp.toAbsolutePath().toString();
            }
            warn("resolveClasspath: no entry ends with " + SUBPROCESS_ENTRY + " in " + codeSource.getAbsolutePath());
            return null;
        } catch (Exception e) {
            warn("resolveClasspath: scan jar failed: " + e);
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 通过类资源 URL 反解自身 jar 路径：{@code jar:file:/path/to.jar!/BOOT-INF/classes/…}
     * <p>
     * fat jar 中类资源的真实条目路径带 {@code BOOT-INF/classes/} 前缀（2026-09-05 线上报障：
     * 只查根路径导致定位失败、对话框从未弹出），两个位置都尝试。
     */
    private static File locateSelfJarByResource() {
        for (String prefix : new String[]{"", "BOOT-INF/classes/"}) {
            try {
                java.net.URL url = DirectoryPickerUtil.class.getResource(
                        "/" + prefix + SUBPROCESS_ENTRY);
                if (url == null || !"jar".equals(url.getProtocol())) {
                    continue;
                }
                String spec = url.toString();
                int bang = spec.indexOf("!/");
                if (bang <= 0) {
                    continue;
                }
                // jar:file:/path → 去掉 jar:file: 前缀后还原为文件路径（兼容空格等已编码字符）
                java.net.URL fileUrl = new java.net.URL(spec.substring(4, bang));
                File jar = new File(fileUrl.toURI());
                if (jar.isFile()) {
                    return jar;
                }
            } catch (Throwable ignored) {
                // 换下一个前缀继续尝试
            }
        }
        return null;
    }
}
