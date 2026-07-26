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
package org.noear.solon.codecli.portal.web;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.memory.MemorySearchResult;
import org.noear.solon.ai.talents.memory.MemorySolution;
import org.noear.solon.ai.talents.memory.MemorySolutionProvider;
import org.noear.solon.ai.talents.memory.MemorySearcher;
import org.noear.solon.ai.talents.memory.MemoryStorer;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆管理控制器 —— 为 Web UI 的「长期记忆」面板提供 CRUD 接口。
 *
 * <p>与 {@link org.noear.solon.ai.talents.memory.MemoryTalent} 共享同一
 * {@link MemorySolutionProvider}（取自 {@link HarnessEngine#getMemoryProvider()}），
 * 保证 UI 侧的读写与 Agent 侧的记忆索引实时一致。</p>
 *
 * <p>userId 与 MemoryTalent 保持对齐：引擎默认 {@code sessionIsolation(false)}，
 * 故此处固定使用 {@code "shared"}。__cwd 交由 MemoryProvider 内部按
 * memoryIsolation 决策（非隔离时替换为 userHome）。</p>
 *
 * @author noear
 */
public class MemoryController {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryController.class);

    /** 与 MemoryTalent.getUserId(sessionIsolation=false) 对齐 */
    private static final String SHARED_USER = "shared";
    /** 列表返回上限，与 MemoryTalent.LIST_ALL_LIMIT 一致 */
    private static final int LIST_ALL_LIMIT = 100;

    private final HarnessEngine engine;

    public MemoryController(HarnessEngine engine) {
        this.engine = engine;
    }

    private MemorySolution solution() {
        MemorySolutionProvider provider = engine.getMemoryProvider();
        if (provider == null) {
            return null;
        }
        // __cwd 传当前工作区；非隔离时 MemoryProvider 内部会替换为 userHome
        return provider.get(engine.getWorkspace());
    }

    private String getNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 列表：返回全部记忆条目（按重要度倒序、时间倒序）。
     */
    @Mapping("/web/chat/memory/list")
    public Result<List<Map<String, Object>>> list() {
        MemorySolution ms = solution();
        if (ms == null || ms.getSearcher() == null) {
            return Result.succeed(new ArrayList<>());
        }

        List<MemorySearchResult> all = ms.getSearcher().listAll(SHARED_USER, LIST_ALL_LIMIT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MemorySearchResult r : all) {
            Map<String, Object> m = new HashMap<>();
            m.put("key", r.getKey());
            m.put("content", r.getContent());
            m.put("importance", r.getImportance());
            m.put("time", r.getTime());
            out.add(m);
        }
        return Result.succeed(out);
    }

    /**
     * 详情：按 key 读取单条记忆的完整内容。
     */
    @Mapping("/web/chat/memory/get")
    public Result<Map<String, Object>> get(@Param("key") String key) {
        if (Assert.isEmpty(key)) {
            return Result.failure("key 不能为空");
        }

        MemorySolution ms = solution();
        if (ms == null || ms.getStorer() == null) {
            return Result.failure("记忆存储未配置");
        }

        String val = ms.getStorer().get(SHARED_USER, key);
        if (Assert.isEmpty(val)) {
            return Result.failure("未找到记忆条目：" + key);
        }

        ONode node = ONode.ofJson(val);
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("content", node.get("content").getString());
        m.put("time", node.get("time").getString());
        m.put("importance", node.get("importance").getInt());
        return Result.succeed(m);
    }

    /**
     * 保存：新建或覆盖一条记忆。TTL 规则与 MemoryTalent.extract 对齐。
     */
    @Mapping("/web/chat/memory/save")
    public Result save(@Param("key") String key,
                       @Param("content") String content,
                       @Param("importance") int importance) {
        if (Assert.isEmpty(key)) {
            return Result.failure("key 不能为空");
        }
        if (Assert.isEmpty(content)) {
            return Result.failure("content 不能为空");
        }
        if (importance < 1 || importance > 10) {
            return Result.failure("importance 需在 1-10 之间");
        }

        MemorySolution ms = solution();
        if (ms == null || ms.getStorer() == null) {
            return Result.failure("记忆存储未配置");
        }

        try {
            String now = getNow();

            Map<String, Object> data = new HashMap<>();
            data.put("content", content);
            data.put("time", now);
            data.put("importance", importance);

            // 动态 TTL：>=10 永久，>=5 保留 30 天，其余 7 天（与 MemoryTalent.extract 一致）
            int ttl;
            if (importance >= 10) {
                ttl = -1;
            } else if (importance >= 5) {
                ttl = 2592000;
            } else {
                ttl = 604800;
            }

            MemoryStorer storer = ms.getStorer();
            storer.put(SHARED_USER, key, ONode.serialize(data), ttl);

            // 同步搜索索引，保证 Agent 侧检索一致
            MemorySearcher searcher = ms.getSearcher();
            if (searcher != null) {
                searcher.updateIndex(SHARED_USER, key, content, importance, now);
            }

            return Result.succeed();
        } catch (Exception e) {
            LOG.error("MemoryController save error, key={}", key, e);
            return Result.failure("保存失败：" + e.getMessage());
        }
    }

    /**
     * 删除：按 key 移除一条记忆及其索引。
     */
    @Mapping("/web/chat/memory/remove")
    public Result remove(@Param("key") String key) {
        if (Assert.isEmpty(key)) {
            return Result.failure("key 不能为空");
        }

        MemorySolution ms = solution();
        if (ms == null || ms.getStorer() == null) {
            return Result.failure("记忆存储未配置");
        }

        try {
            ms.getStorer().remove(SHARED_USER, key);

            MemorySearcher searcher = ms.getSearcher();
            if (searcher != null) {
                searcher.removeIndex(SHARED_USER, key);
            }

            return Result.succeed();
        } catch (Exception e) {
            LOG.error("MemoryController remove error, key={}", key, e);
            return Result.failure("删除失败：" + e.getMessage());
        }
    }
}
