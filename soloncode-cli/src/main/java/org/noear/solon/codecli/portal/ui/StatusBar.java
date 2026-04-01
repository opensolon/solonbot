/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.ui;

import org.jline.terminal.Attributes;
import org.jline.terminal.Cursor;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.noear.solon.codecli.portal.ui.theme.PortalTheme;
import org.noear.solon.codecli.portal.ui.theme.PortalThemes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JLine Status 的底部栏。
 * 布局顺序：
 * 空行 -> 运行态 -> 空行 -> 列表 -> 输入框 -> 空行 -> 状态栏
 */
public class StatusBar {
    static final String[] ALL_FIELDS = {
            "model", "time", "tokens", "dir", "version", "session", "turns", "mode"
    };
    static final String[] FIELD_DESCRIPTIONS = {
            "当前模型名称", "最近一次任务时长", "Token 用量", "工作目录",
            "CLI 版本号", "会话 ID", "对话轮次", "简约/详细模式"
    };

    private static final String SEP = " | ";
    private static final String GAP = " ";
    private static final String[] SPINNER_FRAMES = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };
    private static final AttributedStyle STYLE_BG = AttributedStyle.DEFAULT;
    private static final String RESET = "\033[0m";

    private final Terminal terminal;
    private PortalTheme theme = PortalThemes.defaultTheme();

    private final Set<String> enabledFields = new LinkedHashSet<String>(
            Arrays.asList("model", "time", "dir"));

    private volatile String currentStatus = "idle";
    private volatile long taskStartTime = 0;
    private volatile long stateStartTime = 0;
    private volatile long lastTaskDuration = 0;
    private volatile long lastTokens = 0;

    private String modelName = "unknown";
    private String workDir = "";
    private String version = "";
    private String sessionId = "";
    private int turns = 0;
    private boolean compactMode = false;

    private volatile List<AttributedString> popupLines = Collections.emptyList();
    private volatile AttributedString inputLine = new AttributedString("");

    private volatile int animationTick = 0;
    private final ScheduledExecutorService animExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "statusbar-anim");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> animTask;
    private volatile int lastRenderedLineCount = 0;
    private java.util.concurrent.locks.ReentrantLock jlineLock;
    private volatile Cursor restoreCursor;

    public StatusBar(Terminal terminal) {
        this.terminal = terminal;
    }

    public void setTheme(PortalTheme theme) {
        this.theme = theme == null ? PortalThemes.defaultTheme() : theme;
        draw();
    }

    public void setModelName(String name) {
        this.modelName = name;
    }

    public void setWorkDir(String dir) {
        this.workDir = dir;
    }

    public void setVersion(String ver) {
        this.version = ver;
    }

    public void setSessionId(String id) {
        this.sessionId = id;
    }

    public void setCompactMode(boolean compact) {
        this.compactMode = compact;
        draw();
    }

    public void incrementTurns() {
        this.turns++;
    }

    public void setup() {
        this.lastRenderedLineCount = 0;
    }

    public void setJLineLock(java.util.concurrent.locks.ReentrantLock lock) {
        this.jlineLock = lock;
    }

    public void setRestoreCursor(Cursor cursor) {
        this.restoreCursor = cursor;
    }

    public void draw() {
        if (jlineLock != null) {
            jlineLock.lock();
            try {
                drawInternal();
            } finally {
                jlineLock.unlock();
            }
        } else {
            drawInternal();
        }
    }

    private void drawInternal() {
        try {
            Cursor savedCursor = readCursorPosition();
            if (savedCursor == null) {
                savedCursor = restoreCursor;
            } else {
                restoreCursor = savedCursor;
            }
            List<AttributedString> lines = new ArrayList<AttributedString>();
            lines.add(buildRuntimeLine());
            lines.add(blankLine());
            lines.addAll(popupLines);
            lines.add(padLine(inputLine == null ? new AttributedString("") : inputLine));
            lines.add(blankLine());
            lines.add(buildStatusLine());
            int currentLineCount = lines.size();
            if (currentLineCount < lastRenderedLineCount) {
                for (int i = currentLineCount; i < lastRenderedLineCount; i++) {
                    lines.add(blankLine());
                }
            }
            int renderLineCount = Math.max(lastRenderedLineCount, currentLineCount);
            int terminalHeight = Math.max(1, terminal.getHeight());
            int startRow = Math.max(1, terminalHeight - renderLineCount + 1);
            terminal.writer().print("\033[?25l");
            for (int i = 0; i < renderLineCount; i++) {
                int row = startRow + i;
                AttributedString line = i < lines.size() ? lines.get(i) : blankLine();
                terminal.writer().print("\033[" + row + ";1H");
                terminal.writer().print("\033[2K");
                terminal.writer().print(line.toAnsi(terminal));
            }
            restoreCursor(savedCursor);
            terminal.writer().print("\033[?25h");
            terminal.flush();
            lastRenderedLineCount = currentLineCount;
        } catch (Throwable ignored) {
            try {
                terminal.writer().print("\033[?25h");
                terminal.flush();
            } catch (Throwable e) {
            }
        }
    }

    public void suspend() {
        try {
            Cursor savedCursor = readCursorPosition();
            if (savedCursor == null) {
                savedCursor = restoreCursor;
            } else {
                restoreCursor = savedCursor;
            }
            int lineCount = lastRenderedLineCount;
            if (lineCount > 0) {
                int terminalHeight = Math.max(1, terminal.getHeight());
                int startRow = Math.max(1, terminalHeight - lineCount + 1);
                terminal.writer().print("\033[?25l");
                for (int i = 0; i < lineCount; i++) {
                    terminal.writer().print("\033[" + (startRow + i) + ";1H");
                    terminal.writer().print("\033[2K");
                }
                restoreCursor(savedCursor);
                terminal.writer().print("\033[?25h");
                terminal.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    public void restore() {
        draw();
    }

    public void updateFooter(List<AttributedString> popupLines, AttributedString inputLine) {
        if (popupLines == null || popupLines.isEmpty()) {
            this.popupLines = Collections.emptyList();
        } else {
            this.popupLines = new ArrayList<AttributedString>(popupLines);
        }
        this.inputLine = inputLine == null ? new AttributedString("") : inputLine;
        draw();
    }

    public void updateStatus(String status) {
        String normalized = normalizeStatus(status);
        if (!normalized.equals(this.currentStatus)) {
            this.stateStartTime = System.currentTimeMillis();
        }
        this.currentStatus = normalized;
        draw();
    }

    public String getStatusText() {
        return currentStatus;
    }

    public String getTaskTimeText() {
        long duration = taskStartTime > 0
                ? Math.max(0, System.currentTimeMillis() - taskStartTime)
                : Math.max(0, lastTaskDuration);
        return formatDuration(duration);
    }

    public void taskStart() {
        this.taskStartTime = System.currentTimeMillis();
        this.stateStartTime = this.taskStartTime;
        this.lastTaskDuration = 0;
        this.lastTokens = 0;
        this.currentStatus = "thinking";
        startAnimation();
        draw();
    }

    public void taskEnd(long tokens) {
        this.lastTokens = tokens;
        if (taskStartTime > 0) {
            this.lastTaskDuration = Math.max(0, System.currentTimeMillis() - taskStartTime);
        }
        this.taskStartTime = 0;
        this.currentStatus = "idle";
        this.stateStartTime = System.currentTimeMillis();
        stopAnimation();
        draw();
    }

    public void updateTokens(long tokens) {
        this.lastTokens = tokens;
        draw();
    }

    public boolean isIdle() {
        return "idle".equals(currentStatus);
    }

    private AttributedString buildRuntimeLine() {
        if (isIdle() || taskStartTime <= 0) {
            return blankLine();
        }

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(STYLE_BG);
        sb.append(" ");

        RuntimeStatusInfo info = RuntimeStatusInfo.from(currentStatus, this);
        sb.style(info.spinnerStyle);
        sb.append(SPINNER_FRAMES[animationTick % SPINNER_FRAMES.length]);
        sb.style(styleMuted());
        sb.append(GAP);
        appendAnimatedText(sb, info.text, info.textStyle, info.peakStyle, info.animated);
        sb.style(styleMuted());
        sb.append(buildRuntimeHint());
        return padLine(sb.toAttributedString());
    }

    private void appendAnimatedText(AttributedStringBuilder sb, String text, AttributedStyle baseStyle,
                                    AttributedStyle peakStyle, boolean animated) {
        if (text == null || text.isEmpty()) {
            return;
        }

        if (!animated) {
            sb.style(baseStyle);
            sb.append(text);
            return;
        }

        int waveCenter = animationTick % (text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            int distance = Math.abs(i - waveCenter);
            AttributedStyle style = baseStyle;
            if (distance == 0) {
                style = peakStyle;
            } else if (distance >= 3) {
                style = styleSoft();
            }
            sb.style(style);
            sb.append(String.valueOf(text.charAt(i)));
        }
    }

    private String buildRuntimeHint() {
        long now = System.currentTimeMillis();
        long phaseDuration = stateStartTime > 0 ? Math.max(0, now - stateStartTime) : 0;
        long totalDuration = taskStartTime > 0 ? Math.max(0, now - taskStartTime) : Math.max(0, lastTaskDuration);
        return " (" + formatDuration(phaseDuration)
                + " / " + formatDuration(totalDuration)
                + " / Esc 停止会话)";
    }

    private AttributedString buildStatusLine() {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(STYLE_BG);
        sb.append(" ");

        boolean first = true;
        for (String field : enabledFields) {
            if (!first) {
                sb.style(styleSeparator());
                sb.append(SEP);
            }

            int before = sb.columnLength();
            appendField(sb, field);
            if (sb.columnLength() == before) {
                if (!first) {
                    trimRight(sb, SEP.length());
                }
                continue;
            }
            first = false;
        }

        return padLine(sb.toAttributedString());
    }

    private void appendField(AttributedStringBuilder sb, String field) {
        if ("model".equals(field)) {
            if (isBlank(modelName)) {
                return;
            }
            sb.style(styleMuted());
            sb.append(modelName);
            return;
        }

        if ("time".equals(field)) {
            sb.style(styleMuted());
            if (taskStartTime > 0) {
                sb.append("--");
            } else if (lastTaskDuration > 0) {
                sb.append(formatDuration(lastTaskDuration));
            } else {
                sb.append("--");
            }
            return;
        }

        if ("tokens".equals(field)) {
            if (lastTokens <= 0) {
                return;
            }
            sb.style(styleMuted());
            sb.append(lastTokens + " tok");
            return;
        }

        if ("dir".equals(field)) {
            if (isBlank(workDir)) {
                return;
            }
            int usedWidth = sb.columnLength();
            int remaining = Math.max(0, terminal.getWidth() - usedWidth - 2);
            if (remaining <= 8) {
                return;
            }
            sb.style(styleMuted());
            sb.append(shortenPath(workDir, remaining));
            return;
        }

        if ("version".equals(field)) {
            if (isBlank(version)) {
                return;
            }
            sb.style(styleMuted());
            sb.append(version);
            return;
        }

        if ("session".equals(field)) {
            if (isBlank(sessionId)) {
                return;
            }
            sb.style(styleMuted());
            sb.append(sessionId);
            return;
        }

        if ("turns".equals(field)) {
            sb.style(styleMuted());
            sb.append("#" + turns);
            return;
        }

        if ("mode".equals(field)) {
            sb.style(styleMuted());
            sb.append(compactMode ? "simplified" : "detailed");
        }
    }

    private void trimRight(AttributedStringBuilder sb, int count) {
        String text = sb.toAnsi(terminal);
        if (count <= 0 || text.length() < count) {
            return;
        }
        sb.setLength(Math.max(0, sb.length() - count));
    }

    public void showConfigUI() {
        suspend();

        Attributes savedAttrs = terminal.getAttributes();
        try {
            terminal.enterRawMode();
            Set<String> tempEnabled = new LinkedHashSet<String>(enabledFields);
            int cursor = 0;

            drawConfigMenu(tempEnabled, cursor);

            while (true) {
                int key = readKey();

                if (key == -1) {
                    break;
                } else if (key == 27) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                    }
                    if (isReaderReady()) {
                        int next = readKey();
                        if (next == '[' || next == 'O') {
                            if (isReaderReady()) {
                                int arrow = readKey();
                                if (arrow == 'A') {
                                    cursor = Math.max(0, cursor - 1);
                                    drawConfigMenu(tempEnabled, cursor);
                                    continue;
                                } else if (arrow == 'B') {
                                    cursor = Math.min(ALL_FIELDS.length - 1, cursor + 1);
                                    drawConfigMenu(tempEnabled, cursor);
                                    continue;
                                }
                            }
                        }
                    }
                    break;
                } else if (key == ' ') {
                    String field = ALL_FIELDS[cursor];
                    if (tempEnabled.contains(field)) {
                        tempEnabled.remove(field);
                    } else {
                        tempEnabled.add(field);
                    }
                    drawConfigMenu(tempEnabled, cursor);
                } else if (key == 'k' || key == 'K') {
                    cursor = Math.max(0, cursor - 1);
                    drawConfigMenu(tempEnabled, cursor);
                } else if (key == 'j' || key == 'J') {
                    cursor = Math.min(ALL_FIELDS.length - 1, cursor + 1);
                    drawConfigMenu(tempEnabled, cursor);
                } else if (key == '\r' || key == '\n') {
                    enabledFields.clear();
                    enabledFields.addAll(tempEnabled);
                    break;
                }
            }
        } finally {
            terminal.setAttributes(savedAttrs);
        }

        clearConfigMenu();
        restore();
    }

    private void drawConfigMenu(Set<String> tempEnabled, int cursor) {
        StringBuilder sb = new StringBuilder();
        sb.append("\r\033[J");
        sb.append("\n");
        sb.append(ansiMuted()).append("  --- ").append(RESET)
                .append(ansiText()).append("Status Bar 配置").append(RESET)
                .append(ansiMuted()).append(" --- ").append(RESET);
        sb.append(ansiMuted()).append("  ↑↓/jk  Space  Enter  Esc").append(RESET);
        sb.append("\n\n");

        for (int i = 0; i < ALL_FIELDS.length; i++) {
            String field = ALL_FIELDS[i];
            boolean enabled = tempEnabled.contains(field);
            boolean current = i == cursor;
            String check = enabled ? ansiSuccess() + "[✔]" + RESET : ansiMuted() + "[ ]" + RESET;
            String name = String.format("%-10s", capitalize(field));
            String desc = ansiMuted() + FIELD_DESCRIPTIONS[i] + RESET;

            if (current) {
                sb.append("  ").append(ansiAccent()).append("▸").append(RESET)
                        .append(check).append(" ").append(ansiAccentBold()).append(name).append(RESET)
                        .append(" ").append(desc);
            } else {
                String nameColor = enabled ? ansiText() : ansiMuted();
                sb.append("    ").append(check).append(" ").append(nameColor).append(name).append(RESET)
                        .append(" ").append(desc);
            }
            sb.append("\n");
        }

        sb.append("\033[").append(ALL_FIELDS.length + 3).append("A");
        terminal.writer().write(sb.toString());
        terminal.flush();
    }

    private void clearConfigMenu() {
        terminal.writer().write("\r\033[J");
        terminal.flush();
    }

    private void startAnimation() {
        stopAnimation();
        animationTick = 0;
        animTask = animExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                animationTick++;
                draw();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void stopAnimation() {
        ScheduledFuture<?> task = animTask;
        if (task != null) {
            task.cancel(false);
            animTask = null;
        }
    }

    private int readKey() {
        try {
            return terminal.reader().read();
        } catch (Throwable e) {
            return -1;
        }
    }

    private boolean isReaderReady() {
        try {
            return terminal.reader().ready();
        } catch (Throwable e) {
            return false;
        }
    }

    private AttributedString blankLine() {
        return padLine(new AttributedString(""));
    }

    private AttributedString padLine(AttributedString line) {
        int width = Math.max(1, terminal.getWidth());
        int visible = line.columnLength();
        if (visible >= width) {
            return line.columnSubSequence(0, width);
        }

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.append(line);
        sb.style(STYLE_BG);
        for (int i = 0; i < width - visible; i++) {
            sb.append(' ');
        }
        return sb.toAttributedString();
    }

    private AttributedStyle styleText() {
        return theme.textPrimary().style();
    }

    private AttributedStyle styleMuted() {
        return theme.textMuted().style();
    }

    private AttributedStyle styleSoft() {
        return theme.textSoft().style();
    }

    private AttributedStyle styleSeparator() {
        return theme.separator().style();
    }

    private AttributedStyle styleAccent() {
        return theme.accent().style();
    }

    private AttributedStyle styleAccentBold() {
        return theme.accentStrong().boldStyle();
    }

    private AttributedStyle styleWarn() {
        return theme.warning().style();
    }

    private AttributedStyle styleWarnBold() {
        return theme.warning().boldStyle();
    }

    private AttributedStyle styleSuccess() {
        return theme.success().style();
    }

    private AttributedStyle styleTool() {
        return theme.toolTitle().style();
    }

    private String ansiText() {
        return theme.textPrimary().ansiFg();
    }

    private String ansiMuted() {
        return theme.textMuted().ansiFg();
    }

    private String ansiAccent() {
        return theme.accent().ansiFg();
    }

    private String ansiAccentBold() {
        return theme.accentStrong().ansiBoldFg();
    }

    private String ansiSuccess() {
        return theme.success().ansiFg();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Cursor readCursorPosition() {
        try {
            return terminal.getCursorPosition(discarded -> {
            });
        } catch (Throwable e) {
            return null;
        }
    }

    private void restoreCursor(Cursor cursor) {
        if (cursor == null) {
            return;
        }
        try {
            terminal.puts(InfoCmp.Capability.cursor_address, cursor.getY(), cursor.getX());
        } catch (Throwable ignored) {
        }
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "idle";
        }
        return value.trim();
    }

    static String formatDuration(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        if (seconds < 60) {
            return seconds + "s";
        }
        long mins = seconds / 60;
        long secs = seconds % 60;
        if (mins < 60) {
            return String.format("%dm%02ds", mins, secs);
        }
        long hours = mins / 60;
        long remMins = mins % 60;
        return String.format("%dh%02dm%02ds", hours, remMins, secs);
    }

    private static String shortenPath(String path, int maxLen) {
        if (path == null || path.length() <= maxLen) {
            return path;
        }
        if (maxLen <= 3) {
            return path.substring(0, Math.max(0, maxLen));
        }
        int headLen = Math.max(1, maxLen / 3);
        int tailLen = Math.max(1, maxLen - headLen - 3);
        if (headLen + tailLen + 3 > path.length()) {
            return path;
        }
        return path.substring(0, headLen) + "..." + path.substring(path.length() - tailLen);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final class RuntimeStatusInfo {
        private final String text;
        private final AttributedStyle spinnerStyle;
        private final AttributedStyle textStyle;
        private final AttributedStyle peakStyle;
        private final boolean animated;

        private RuntimeStatusInfo(String text, AttributedStyle spinnerStyle, AttributedStyle textStyle,
                                  AttributedStyle peakStyle, boolean animated) {
            this.text = text;
            this.spinnerStyle = spinnerStyle;
            this.textStyle = textStyle;
            this.peakStyle = peakStyle;
            this.animated = animated;
        }

        private static RuntimeStatusInfo from(String status, StatusBar owner) {
            if ("thinking".equals(status)) {
                return new RuntimeStatusInfo("thinking", owner.styleWarn(),
                        owner.styleWarn(), owner.styleWarnBold(), true);
            }
            if ("responding".equals(status)) {
                return new RuntimeStatusInfo("responding",
                        owner.styleAccent(), owner.styleAccent(), owner.styleAccentBold(), true);
            }
            if (status != null && status.startsWith("tool:")) {
                String toolName = status.substring("tool:".length()).trim();
                if (toolName.isEmpty()) {
                    toolName = "tool";
                }
                return new RuntimeStatusInfo(toolName, owner.styleAccent(),
                        owner.styleTool(), owner.styleTool(), false);
            }
            if ("idle".equals(status)) {
                return new RuntimeStatusInfo("", owner.styleSuccess(),
                        owner.styleSuccess(), owner.styleSuccess(), false);
            }
            return new RuntimeStatusInfo(status == null ? "" : status,
                    owner.styleMuted(), owner.styleText(), owner.styleText(), false);
        }
    }
}
