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
package org.noear.solon.ai.codecli.core.message;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新消息 API 测试示例
 *
 * 演示如何使用新的 Message<T> API
 *
 * @author bai
 * @since 3.9.5
 */
class MessageApiTest {

    /**
     * 示例 1: 创建简单文本消息
     */
    @Test
    void testSimpleMessage() {
        // 使用 Builder 创建消息
        Message<String> message = Message.of("Hello World")
                .from("agent")
                .to("user")
                .type(Message.Types.NOTIFICATION);

        // 类型安全的访问
        assertEquals("Hello World", message.getContent());
        assertEquals("agent", message.getFrom());
        assertEquals("user", message.getTo());
        assertEquals("notification", message.getType());
    }

    /**
     * 示例 2: 创建列表消息
     */
    @Test
    void testListMessage() {
        List<String> tags = Arrays.asList("controller", "rest", "api");

        // 创建泛型消息
        Message<List<String>> message = Message.<List<String>>of()
                .content(tags)
                .from("explore")
                .to("plan")
                .type("query.result");

        // 类型安全的访问 - 无需强制转换
        List<String> content = message.getContent();
        assertEquals(3, content.size());
        assertEquals("controller", content.get(0));
    }

    /**
     * 示例 3: 带元数据的消息
     */
    @Test
    void testMessageWithMetadata() {
        Message<String> message = Message.of("操作完成")
                .from("bash")
                .to("*")
                .type(Message.Types.TASK_COMPLETED)
                .metadata("taskId", "task-001")
                .metadata("status", "success")
                .metadata("duration", "1500")
                .metadata("persistent", "true");

        // 获取元数据
        assertEquals("task-001", message.getMetadata("taskId"));
        assertEquals("success", message.getMetadata("status", "unknown"));

        // 类型安全的元数据获取
        assertEquals(1500, message.getIntMetadata("duration", 0));
        assertTrue(message.getBooleanMetadata("persistent", false));
    }

    /**
     * 示例 4: 使用枚举类型
     */
    @Test
    void testMessageWithEnum() {
        enum AgentType {
            EXPLORE, PLAN, BASH
        }

        // 使用枚举作为 from/to
        Message<String> message = Message.of("查询结果")
                .from(AgentType.EXPLORE)
                .to(AgentType.PLAN)
                .type(Message.Types.QUERY);

        assertEquals("explore", message.getFrom());
        assertEquals("plan", message.getTo());
    }

    /**
     * 示例 5: 转换为 Builder
     */
    @Test
    void testToBuilder() {
        Message<String> original = Message.of("原始消息")
                .from("agent1")
                .to("agent2")
                .type("request");

        // 使用 toBuilder 创建修改后的副本
        Message<String> modified = original.toBuilder()
                .to("agent3")
                .metadata("modified", "true")
                .build();

        assertEquals("agent1", modified.getFrom());
        assertEquals("agent3", modified.getTo());
        assertEquals("true", modified.getMetadata("modified"));
    }

    /**
     * 示例 6: 消息处理器使用新 API
     */
    @Test
    void testMessageHandler() throws ExecutionException, InterruptedException {
        // 创建消息处理器
        MessageHandler handler = message -> {
            // 类型安全的处理
            String content = message.getContent();
            String taskId = message.getMetadata("taskId");

            return CompletableFuture.completedFuture(
                    "处理完成: " + content + ", taskId=" + taskId
            );
        };

        // 测试处理器
        Message<String> message = Message.of("测试消息")
                .from("agent")
                .to("user")
                .metadata("taskId", "task-123");

        CompletableFuture<Object> result = handler.handle(message);

        assertEquals("处理完成: 测试消息, taskId=task-123", result.get());
    }

    /**
     * 示例 7: 空消息
     */
    @Test
    void testEmptyMessage() {
        Message<?> message = Message.empty()
                .from("system")
                .to("*")
                .type(Message.Types.SYSTEM)
                .build();

        assertEquals("system", message.getFrom());
        assertEquals("*", message.getTo());
        assertNull(message.getContent());
    }

    /**
     * 示例 8: 复杂对象消息
     */
    @Test
    void testComplexObjectMessage() {
        class QueryResult {
            private final String query;
            private final List<String> results;

            QueryResult(String query, List<String> results) {
                this.query = query;
                this.results = results;
            }

            public String getQuery() {
                return query;
            }

            public List<String> getResults() {
                return results;
            }
        }

        QueryResult result = new QueryResult(
                "Controller",
                Arrays.asList("UserController.java", "ProductController.java")
        );

        Message<QueryResult> message = Message.of(result)
                .from("explore")
                .to("plan")
                .type("query.result");

        // 类型安全的访问 - 无需转换
        QueryResult content = message.getContent();
        assertEquals("Controller", content.getQuery());
        assertEquals(2, content.getResults().size());
    }

    /**
     * 示例 9: 默认值
     */
    @Test
    void testDefaultValues() {
        // 使用默认值创建消息
        Message<String> message = Message.of("test")
                .build();

        assertEquals("system", message.getFrom());  // 默认 from
        assertEquals("*", message.getTo());          // 默认 to
        assertEquals("notification", message.getType());  // 默认 type
        assertNotNull(message.getId());  // 自动生成 ID
        assertTrue(message.getTimestamp() > 0);  // 自动生成时间戳
    }

    /**
     * 示例 10: 元数据操作
     */
    @Test
    void testMetadataOperations() {
        Message<String> message = Message.of("test")
                .metadata("key1", "value1")
                .metadata("key2", "value2")
                .build();

        // 获取所有元数据
        assertEquals(2, message.getMetadata().size());
        assertEquals("value1", message.getMetadata("key1"));
        assertEquals("value2", message.getMetadata("key2"));

        // 获取不存在的键（带默认值）
        assertEquals("default", message.getMetadata("key3", "default"));

        // 类型安全的元数据
        assertEquals(100, message.getIntMetadata("key3", 100));
        assertTrue(message.getBooleanMetadata("key3", true));
    }
}
