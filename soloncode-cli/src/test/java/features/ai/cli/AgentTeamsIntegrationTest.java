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
package features.ai.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.codecli.core.AgentProperties;
import org.noear.solon.ai.codecli.core.memory.*;
import org.noear.solon.ai.codecli.core.event.*;
import org.noear.solon.ai.codecli.core.message.*;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent Teams 集成测试
 *
 * 测试共享记忆、事件总线、消息传递三大功能
 *
 * @author bai
 * @since 3.9.5
 */
public class AgentTeamsIntegrationTest {

    private static final String TEST_WORK_DIR = "work/test";

    @Test
    public void testSharedMemoryManager() {
        System.out.println("=== 测试共享记忆管理器 ===");

        // 创建管理器
        SharedMemoryManager manager = new SharedMemoryManager(TEST_WORK_DIR);

        // 1. 测试存储短期记忆
        ShortTermMemory stm = new ShortTermMemory("explore", "探索任务上下文", "task-1");
        manager.store(stm);
        System.out.println("✓ 存储短期记忆");

        // 2. 测试存储长期记忆
        LongTermMemory ltm = new LongTermMemory("重要架构决策", "plan", Arrays.asList("architecture", "design"));
        ltm.setImportance(0.9);
        manager.store(ltm);
        System.out.println("✓ 存储长期记忆");

        // 3. 测试存储知识库
        KnowledgeMemory km = new KnowledgeMemory("Solon 框架", "轻量级 Java 框架", "framework");
        km.setKeywords(Arrays.asList("java", "lightweight", "framework"));
        manager.store(km);
        System.out.println("✓ 存储知识库记忆");

        // 4. 测试检索
        List<Memory> memories = manager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
        assertTrue(memories.size() >= 1);
        System.out.println("✓ 检索到 " + memories.size() + " 条短期记忆");

        // 5. 测试按标签检索
        List<LongTermMemory> taggedMemories = manager.retrieveByTag("architecture", 10);
        assertTrue(taggedMemories.size() >= 1);
        System.out.println("✓ 按标签检索到 " + taggedMemories.size() + " 条记忆");

        // 6. 测试搜索
        List<Memory> searchResults = manager.search("架构", 10);
        assertTrue(searchResults.size() >= 1);
        System.out.println("✓ 搜索到 " + searchResults.size() + " 条相关记忆");

        // 7. 测试统计信息
        var stats = manager.getStats();
        System.out.println("✓ 记忆统计: " + stats);

        // 清理
        manager.shutdown();
        System.out.println("✓ 共享记忆管理器测试通过\n");
    }

    @Test
    public void testEventBus() throws Exception {
        System.out.println("=== 测试事件总线 ===");

        // 创建事件总线
        EventBus bus = new EventBus(2, 100);

        // 1. 测试订阅和发布（使用枚举）
        CompletableFuture<String> future = new CompletableFuture<>();
        String subscriptionId = bus.subscribe(AgentEventType.TASK_STARTED.getCode(), event -> {
            future.complete((String) event.getPayload());
            return CompletableFuture.completedFuture(EventHandler.Result.success());
        });
        System.out.println("✓ 订阅事件: " + subscriptionId);

        // 2. 发布事件
        EventMetadata metadata = EventMetadata.builder()
                .sourceAgent("test")
                .taskId("task-1")
                .priority(5)
                .build();
        AgentEvent event = new AgentEvent(AgentEventType.TASK_STARTED, "hello world", metadata);
        bus.publish(event);
        System.out.println("✓ 发布事件");

        // 3. 验证收到事件
        String result = future.get(2, TimeUnit.SECONDS);
        assertEquals("hello world", result);
        System.out.println("✓ 接收到事件: " + result);

        // 4. 测试通配符订阅
        CompletableFuture<String> wildcardFuture = new CompletableFuture<>();
        bus.subscribe("task.*", e -> {
            wildcardFuture.complete(e.getEventTypeCode());
            return CompletableFuture.completedFuture(EventHandler.Result.success());
        });

        AgentEvent taskEvent = new AgentEvent(AgentEventType.TASK_COMPLETED, "data", metadata);
        bus.publish(taskEvent);

        String eventType = wildcardFuture.get(2, TimeUnit.SECONDS);
        assertEquals("task.completed", eventType);
        System.out.println("✓ 通配符订阅成功: " + eventType);

        // 5. 测试事件历史
        List<AgentEvent> history = bus.getEventHistory(10);
        assertTrue(history.size() >= 2);
        System.out.println("✓ 事件历史记录: " + history.size() + " 条");

        // 6. 测试订阅者数量
        int count = bus.getSubscriberCount();
        assertTrue(count >= 2);
        System.out.println("✓ 订阅者数量: " + count);

        // 清理
        bus.shutdown();
        System.out.println("✓ 事件总线测试通过\n");
    }

    @Test
    public void testMessageChannel() throws Exception {
        System.out.println("=== 测试消息通道 ===");

        // 创建消息通道
        MessageChannel channel = new MessageChannel(TEST_WORK_DIR, 2);

        // 1. 测试注册处理器
        String handlerId = channel.registerHandler("receiver", message -> {
            System.out.println("  → 收到消息: " + message.getPayload());
            return CompletableFuture.completedFuture("ACK");
        });
        System.out.println("✓ 注册消息处理器: " + handlerId);

        // 2. 测试发送点对点消息
        AgentMessage message = new AgentMessage(
                "sender", "receiver", "test.type", "test payload",
                AgentMessage.MessageOptions.builder().requireAck(true).build()
        );

        CompletableFuture<MessageAck> future = channel.send(message);
        MessageAck ack = future.get(2, TimeUnit.SECONDS);
        assertTrue(ack.isSuccess());
        System.out.println("✓ 消息投递成功: " + ack);

        // 3. 测试广播消息
        // 注册多个接收者
        channel.registerHandler("agent1", msg -> CompletableFuture.completedFuture("ACK1"));
        channel.registerHandler("agent2", msg -> CompletableFuture.completedFuture("ACK2"));

        AgentMessage broadcastMsg = new AgentMessage(
                "main", "*", "notification", "broadcast test",
                AgentMessage.MessageOptions.builder().build()
        );

        CompletableFuture<List<MessageAck>> broadcastFuture = channel.broadcast(broadcastMsg);
        List<MessageAck> acks = broadcastFuture.get(2, TimeUnit.SECONDS);
        assertTrue(acks.size() >= 2);
        System.out.println("✓ 广播消息成功: " + acks.size() + " 个接收者");

        // 4. 测试消息队列（离线消息）
        channel.registerHandler("offline_agent", msg -> CompletableFuture.completedFuture("RECEIVED"));

        AgentMessage queuedMsg = new AgentMessage(
                "main", "offline_agent", "query", "queued message",
                AgentMessage.MessageOptions.builder()
                        .persistent(true)
                        .ttl(60000)
                        .build()
        );

        MessageAck queuedAck = channel.send(queuedMsg).get(2, TimeUnit.SECONDS);
        assertTrue(queuedAck.isSuccess());
        System.out.println("✓ 离线消息队列成功");

        // 清理
        channel.shutdown();
        System.out.println("✓ 消息通道测试通过\n");
    }

    @Test
    public void testAgentCollaboration() throws Exception {
        System.out.println("=== 测试代理协作 ===");

        // 创建管理器
        SharedMemoryManager memoryManager = new SharedMemoryManager(TEST_WORK_DIR);
        EventBus eventBus = new EventBus(2, 100);
        MessageChannel messageChannel = new MessageChannel(TEST_WORK_DIR, 2);

        // 场景：Plan 代理制定计划 → 存储到共享记忆 → 发布事件 → Explore 代理接收事件
        String taskId = "task-collab-1";

        // 1. Plan 代理存储计划到共享记忆
        LongTermMemory plan = new LongTermMemory(
                "实现缓存系统的计划",
                "plan",
                Arrays.asList("cache", "architecture")
        );
        plan.setImportance(0.95);
        memoryManager.store(plan);
        System.out.println("✓ Plan 代理存储计划到共享记忆");

        // 2. Plan 代理发布完成事件
        eventBus.publish(new AgentEvent(
                AgentEventType.TASK_COMPLETED,
                "计划已完成",
                EventMetadata.builder()
                        .sourceAgent("plan")
                        .taskId(taskId)
                        .priority(5)
                        .build()
        ));
        System.out.println("✓ Plan 代理发布完成事件");

        // 3. Explore 代理订阅事件并处理
        CompletableFuture<String> exploreFuture = new CompletableFuture<>();
        eventBus.subscribe(AgentEventType.TASK_COMPLETED.getCode(), event -> {
            if ("plan".equals(event.getMetadata().getSourceAgent())) {
                // 检索相关记忆
                List<Memory> memories = memoryManager.search("缓存", 5);
                exploreFuture.complete("找到 " + memories.size() + " 条相关记忆");
                return CompletableFuture.completedFuture(EventHandler.Result.success());
            }
            return CompletableFuture.completedFuture(EventHandler.Result.success());
        });
        System.out.println("✓ Explore 代理订阅事件");

        // 4. Explore 代理发送消息给 Plan 代理
        messageChannel.registerHandler("explore", msg ->
            CompletableFuture.completedFuture("探索完成")
        );
        messageChannel.registerHandler("plan", msg -> {
            if (msg.getType() == AgentMessageType.QUERY) {
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(100); // 模拟处理
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return CompletableFuture.completedFuture("计划细节");
            }
            return CompletableFuture.completedFuture("ACK");
        });

        CompletableFuture<Object> queryResult = messageChannel.request(
                "explore", "plan", "query", "需要探索的模块"
        );
        Object result = queryResult.get(2, TimeUnit.SECONDS);
        assertNotNull(result);
        System.out.println("✓ 代理间消息传递成功");

        // 5. 验证结果
        String exploreResult = exploreFuture.get(2, TimeUnit.SECONDS);
        assertTrue(exploreResult.contains("相关记忆"));
        System.out.println("✓ " + exploreResult);

        // 清理
        memoryManager.shutdown();
        eventBus.shutdown();
        messageChannel.shutdown();
        System.out.println("✓ 代理协作测试通过\n");
    }

    @Test
    public void testConfigurationProperties() {
        System.out.println("=== 测试配置属性 ===");

        AgentProperties props = new AgentProperties();

        // 验证默认值
        assertFalse(props.sharedMemoryEnabled);
        assertFalse(props.eventBusEnabled);
        assertFalse(props.messageChannelEnabled);
        System.out.println("✓ 默认功能未启用");

        // 验证共享记忆配置
        assertNotNull(props.sharedMemory);
        assertEquals(3600_000L, props.sharedMemory.shortTermTtl);
        assertEquals(7 * 24 * 3600_000L, props.sharedMemory.longTermTtl);
        assertTrue(props.sharedMemory.persistOnWrite);
        assertEquals(1000, props.sharedMemory.maxShortTermCount);
        System.out.println("✓ 共享记忆配置正确");

        // 验证事件总线配置
        assertNotNull(props.eventBus);
        assertEquals(1000, props.eventBus.maxHistorySize);
        assertEquals(5, props.eventBus.defaultPriority);
        assertEquals(30, props.eventBus.timeoutSeconds);
        System.out.println("✓ 事件总线配置正确");

        // 验证消息通道配置
        assertNotNull(props.messageChannel);
        assertEquals(60_000L, props.messageChannel.defaultTtl);
        assertEquals(1000, props.messageChannel.maxQueueSize);
        assertTrue(props.messageChannel.persistMessages);
        System.out.println("✓ 消息通道配置正确");

        System.out.println("✓ 配置属性测试通过\n");
    }
}
