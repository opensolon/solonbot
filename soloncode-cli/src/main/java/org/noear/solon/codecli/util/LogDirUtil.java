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

import org.noear.solon.Utils;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.core.util.JavaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 日志目录工具：统一计算工作区日志目录（~/.soloncode/logs/&lt;工作区标识&gt;/），并清理旧版遗留日志。
 * <p>
 * 打开目录请用 OsOpenUtil.openDirectory(File)。
 *
 * @author noear
 * @since 3.9.1
 */
public class LogDirUtil {
    private static final Logger log = LoggerFactory.getLogger(LogDirUtil.class);

    /**
     * 用户主目录下的日志根目录名
     */
    private static final String LOG_ROOT = ".soloncode/logs";

    /**
     * 日志文件后缀（与 app.yml 中 solon.logging.appender.file.extension 一致）
     */
    private static final String LOG_EXTENSION = ".log";

    /**
     * 日志目录标识的系统属性名（app.yml 中 file.name 引用 ${soloncode.logkey}）
     */
    public static final String LOG_KEY_PROP = "soloncode.logkey";

    /**
     * 计算进程启动目录的日志目录标识（供启动阶段写入 {@link #LOG_KEY_PROP}）
     *
     * @see #workspaceLogKey(String)
     */
    public static String workspaceLogKey() {
        return workspaceLogKey(AgentFlags.getUserDir());
    }

    /**
     * 计算指定工作区的日志目录标识：md5(工作区目录) + "-" + 可读目录名
     *
     * @param workspacePath 工作区目录（为空时回退到进程启动目录）
     */
    public static String workspaceLogKey(String workspacePath) {
        String pathStr = Utils.isEmpty(workspacePath) ? AgentFlags.getUserDir() : workspacePath;
        Path dir = Paths.get(pathStr).toAbsolutePath().normalize();

        //Windows 文件系统大小写不敏感：统一小写后再哈希，避免 "D:\\Work\\MyApp" 与 "D:\\work\\myapp" 生成两个日志目录
        String hashSource = JavaUtil.IS_WINDOWS ? dir.toString().toLowerCase() : dir.toString();
        String userDirMd5 = Utils.md5(hashSource);

        return userDirMd5 + "-" + readableDirName(dir);
    }

    /**
     * 生成可读的目录名片段。
     * 根目录（如 "C:\" 或 "/"）时 getFileName() 为 null，退化为清洗后的完整路径（如 "C_"）。
     */
    private static String readableDirName(Path dir) {
        String name;
        Path fileName = dir.getFileName();
        if (fileName != null) {
            name = fileName.toString();
        } else {
            //根目录：去掉末尾分隔符，清洗非法字符后作为名称（如 "C:" -> "C_"，"/" -> "root"）
            name = dir.toString().replace("\\", "/");
            if (name.endsWith("/")) {
                name = name.substring(0, name.length() - 1);
            }
        }

        name = name.replaceAll("[:/*?\"<>|\\s]", "_");
        if (name.isEmpty()) {
            name = "root";
        }
        //目录名长度兜底（各文件系统名称上限多在 255，这里留足余量）
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        return name;
    }

    /**
     * 获取当前 JVM 实际写入的日志目录（~/.soloncode/logs/&lt;工作区标识&gt;/）
     * <p>logback 的 file appender 是 JVM 全局的：路径在启动时由 {@link #LOG_KEY_PROP} 一次性固定，
     * 与运行期通过 Web 端切换的工作区无关。故这里以该系统属性为准（单一事实来源），
     * 不按传入的工作区路径重新推算，避免指向一个并未写入日志的目录。</p>
     */
    public static File logDir() {
        String logKey = System.getProperty(LOG_KEY_PROP);
        if (Utils.isEmpty(logKey)) {
            //兜底：未经 App.main 启动（如单元测试）时按启动目录推算
            logKey = workspaceLogKey();
        }

        return new File(logRootDir(), logKey);
    }

    /**
     * 获取日志根目录（~/.soloncode/logs/）
     */
    public static File logRootDir() {
        return Paths.get(AgentFlags.getUserHome(), LOG_ROOT).toFile();
    }

    /**
     * 清理旧版本遗留在工作区目录下的日志（&lt;工作区&gt;/.soloncode/logs/*.log）。
     * <p>
     * 旧版本日志写在工作区内，会污染 IDE 全文搜索；新版本已统一改到 ~/.soloncode/logs/&lt;工作区标识&gt;/。
     * 清理策略（保守）：
     * <ul>
     *     <li>仅删除该目录<b>直接子级</b>的 .log 文件，不递归子目录（子目录即新版按工作区标识分的日志目录）</li>
     *     <li>其它类型文件一律保留；删除后目录为空则顺带移除空目录</li>
     *     <li>任何失败（文件被占用、无权限等）静默跳过，绝不影响工作区创建</li>
     * </ul>
     *
     * @param workspacePath 工作区目录
     */
    public static void cleanLegacyLogs(String workspacePath) {
        if (Utils.isEmpty(workspacePath)) {
            return;
        }

        try {
            File legacyDir = Paths.get(workspacePath, LOG_ROOT).toFile();

            if (legacyDir.isDirectory() == false) {
                return;
            }

            //工作区恰好就是用户主目录时，该目录即新版日志根目录：直接子级为各工作区日志子目录，不做清理
            if (isSameDir(legacyDir, logRootDir())) {
                return;
            }

            File[] files = legacyDir.listFiles();
            if (files == null) {
                return;
            }

            int deleted = 0;
            int remained = 0;
            for (File file : files) {
                //只处理直接子级的普通文件，子目录（孙级）一概不动
                if (file.isFile() && file.getName().toLowerCase().endsWith(LOG_EXTENSION)) {
                    if (file.delete()) {
                        deleted++;
                    } else {
                        //可能被旧实例占用（Windows 常见），保留即可
                        remained++;
                    }
                } else {
                    remained++;
                }
            }

            //目录已空则移除，避免残留空目录继续出现在项目树里
            if (remained == 0) {
                legacyDir.delete();
            }

            if (deleted > 0) {
                log.debug("Cleaned {} legacy log file(s) under: {}", deleted, legacyDir.getAbsolutePath());
            }
        } catch (Throwable e) {
            //清理属于best-effort，任何异常都不应影响工作区创建
            log.debug("Clean legacy logs failed: {}", workspacePath, e);
        }
    }

    private static boolean isSameDir(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Exception e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }
}
