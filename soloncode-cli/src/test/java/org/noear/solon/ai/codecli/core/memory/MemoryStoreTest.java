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
package org.noear.solon.ai.codecli.core.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryStore 测试
 *
 * 测试简单的 POJO + JSON 持久化方案
 *
 * @author bai
 * @since 3.9.5
 */
public class MemoryStoreTest {

    private static final String TEST_WORK_DIR = "work/test/memory";

    @AfterEach
    public void cleanup() {
        // 清理测试数据
        MemoryStore store = new MemoryStore(TEST_WORK_DIR);
        store.clear();
    }

    @Test
    public void testStoreAndLoadShortTermMemory() {
        System.out.println("=== 测试短期记忆存储和加载 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 创建短期记忆
        ShortTermMemory original = new ShortTermMemory(
            "explore",
            "发现3个Controller文件",
            "task-explore-001"
        );
        original.setId("test-stm-001");

        // 存储（异步，等待完成）
        store.store(original);
        waitForAsync();

        // 加载
        Memory loaded = store.load(original.getId(), Memory.MemoryType.SHORT_TERM);
        assertNotNull(loaded, "加载的记忆不应为 null");
        assertTrue(loaded instanceof ShortTermMemory, "应为 ShortTermMemory 类型");

        ShortTermMemory stmLoaded = (ShortTermMemory) loaded;
        assertEquals(original.getId(), stmLoaded.getId());
        assertEquals(original.getAgentId(), stmLoaded.getAgentId());
        assertEquals(original.getContext(), stmLoaded.getContext());
        assertEquals(original.getTaskId(), stmLoaded.getTaskId());

        System.out.println("✓ 短期记忆加载成功，数据一致");
    }

    @Test
    public void testStoreAndLoadLongTermMemory() {
        System.out.println("=== 测试长期记忆存储和加载 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 创建长期记忆
        LongTermMemory original = new LongTermMemory(
            "项目采用JWT认证",
            "plan",
            Arrays.asList("auth", "jwt", "security")
        );
        original.setId("test-ltm-001");
        original.setImportance(0.9);

        // 存储
        store.store(original);
        waitForAsync();

        // 加载
        Memory loaded = store.load(original.getId(), Memory.MemoryType.LONG_TERM);
        assertNotNull(loaded);
        assertTrue(loaded instanceof LongTermMemory);

        LongTermMemory ltmLoaded = (LongTermMemory) loaded;
        assertEquals(original.getId(), ltmLoaded.getId());
        assertEquals(original.getSummary(), ltmLoaded.getSummary());
        assertEquals(original.getSourceAgent(), ltmLoaded.getSourceAgent());
        assertEquals(0.9, ltmLoaded.getImportance(), 0.001);
        assertEquals(original.getTags(), ltmLoaded.getTags());

        System.out.println("✓ 长期记忆加载成功，数据一致");
    }

    @Test
    public void testStoreAndLoadKnowledgeMemory() {
        System.out.println("=== 测试知识库存储和加载 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 创建知识库记忆
        KnowledgeMemory original = new KnowledgeMemory(
            "Solon框架配置",
            "Solon使用@Configuration注解进行配置",
            "framework",
            Arrays.asList("solon", "config", "ioc")
        );
        original.setId("test-km-001");

        // 存储
        store.store(original);
        waitForAsync();

        // 加载
        Memory loaded = store.load(original.getId(), Memory.MemoryType.KNOWLEDGE);
        assertNotNull(loaded);
        assertTrue(loaded instanceof KnowledgeMemory);

        KnowledgeMemory kmLoaded = (KnowledgeMemory) loaded;
        assertEquals(original.getId(), kmLoaded.getId());
        assertEquals(original.getSubject(), kmLoaded.getSubject());
        assertEquals(original.getContent(), kmLoaded.getContent());
        assertEquals(original.getCategory(), kmLoaded.getCategory());
        assertEquals(original.getKeywords(), kmLoaded.getKeywords());

        System.out.println("✓ 知识库记忆加载成功，数据一致");
    }

    @Test
    public void testLoadAllMemories() {
        System.out.println("=== 测试批量加载记忆 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 存储多个记忆
        ShortTermMemory stm1 = new ShortTermMemory("agent1", "context1", "task1");
        stm1.setId("test-batch-001");
        store.store(stm1);

        ShortTermMemory stm2 = new ShortTermMemory("agent2", "context2", "task2");
        stm2.setId("test-batch-002");
        store.store(stm2);

        waitForAsync();

        // 批量加载
        List<Memory> memories = store.loadAll(Memory.MemoryType.SHORT_TERM);

        assertTrue(memories.size() >= 2, "应加载至少2条记忆");
        System.out.println("✓ 批量加载成功: " + memories.size() + " 条记忆");
    }

    @Test
    public void testDeleteMemory() {
        System.out.println("=== 测试删除记忆 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 存储记忆
        ShortTermMemory memory = new ShortTermMemory("agent", "context", "task");
        memory.setId("test-delete-001");
        store.store(memory);
        waitForAsync();

        // 验证存在
        Memory loaded = store.load(memory.getId(), Memory.MemoryType.SHORT_TERM);
        assertNotNull(loaded);

        // 删除
        store.delete(memory.getId(), Memory.MemoryType.SHORT_TERM);
        System.out.println("✓ 记忆已删除");

        // 验证不存在
        Memory deleted = store.load(memory.getId(), Memory.MemoryType.SHORT_TERM);
        assertNull(deleted);

        System.out.println("✓ 删除后无法加载");
    }

    @Test
    public void testClearAll() {
        System.out.println("=== 测试清空所有记忆 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 存储多个记忆
        ShortTermMemory stm = new ShortTermMemory("agent", "context", "task");
        stm.setId("test-clear-001");
        store.store(stm);

        LongTermMemory ltm = new LongTermMemory("summary", "agent", Arrays.asList("tag"));
        ltm.setId("test-clear-002");
        store.store(ltm);

        waitForAsync();

        // 清空
        store.clear();
        System.out.println("✓ 已清空所有记忆");

        // 验证清空
        List<Memory> stmList = store.loadAll(Memory.MemoryType.SHORT_TERM);
        List<Memory> ltmList = store.loadAll(Memory.MemoryType.LONG_TERM);

        assertTrue(stmList.isEmpty(), "短期记忆应为空");
        assertTrue(ltmList.isEmpty(), "长期记忆应为空");

        System.out.println("✓ 所有记忆已清空");
    }

    @Test
    public void testGetStats() {
        System.out.println("=== 测试获取统计信息 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 存储不同类型的记忆
        ShortTermMemory stm = new ShortTermMemory("agent", "context", "task");
        stm.setId("test-stats-001");
        store.store(stm);

        LongTermMemory ltm = new LongTermMemory("summary", "agent", Arrays.asList("tag"));
        ltm.setId("test-stats-002");
        store.store(ltm);

        waitForAsync();

        // 获取统计
        Map<String, Integer> stats = store.getStats();

        assertNotNull(stats);
        assertTrue(stats.containsKey("short_term") || stats.containsKey("long_term"));
        System.out.println("✓ 统计信息: " + stats);
    }

    @Test
    public void testFileStructure() {
        System.out.println("=== 测试文件结构 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 存储记忆
        ShortTermMemory stm = new ShortTermMemory("agent", "context", "task");
        stm.setId("test-file-001");
        store.store(stm);

        waitForAsync();

        // 验证文件存在
        File typeDir = new File(TEST_WORK_DIR + "/.soloncode/memory/short_term/");
        assertTrue(typeDir.exists(), "类型目录应存在");

        File[] files = typeDir.listFiles((d, name) -> name.equals("test-file-001.json"));
        assertTrue(files != null && files.length > 0, "记忆文件应存在");

        System.out.println("✓ 文件结构正确: " + files[0].getPath());
    }

    @Test
    public void testLoadNonExistentMemory() {
        System.out.println("=== 测试加载不存在的记忆 ===");

        MemoryStore store = new MemoryStore(TEST_WORK_DIR);

        // 加载不存在的记忆
        Memory loaded = store.load("non-existent-id", Memory.MemoryType.SHORT_TERM);

        assertNull(loaded, "不存在的记忆应返回 null");
        System.out.println("✓ 正确处理不存在的记忆");
    }

    /**
     * 等待异步操作完成
     */
    private void waitForAsync() {
        try {
            Thread.sleep(100); // 等待异步存储完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
