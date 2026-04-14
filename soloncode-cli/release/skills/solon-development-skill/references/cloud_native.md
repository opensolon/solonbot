# Cloud Native — 微服务与分布式

> 适用场景：服务注册与发现、配置中心、消息队列、文件存储、分布式任务调度、熔断限流、网关。

## 核心用法（统一 API）

Solon Cloud 提供统一的 API 接口，不同中间件只需更换插件依赖即可切换。

### Cloud Client 统一入口

```java
// 配置服务
CloudClient.config().load(group);
CloudClient.config().pull(group, key);

// 注册与发现
CloudClient.discovery().register(instance);
CloudClient.discovery().find(group, service);

// 事件总线
CloudClient.event().publish(new CloudEvent topic, content));
CloudClient.event().subscribe(topic, handler);

// 文件服务
CloudClient.file().upload(fileName, content);
CloudClient.file().download(fileName);

// 定时任务
@CloudJob("job1")
public void job1() { /* ... */ }

// 熔断
CloudClient.breaker().entry(name).fail();
```

---

## Cloud Config — 分布式配置

### 适配插件

| 插件 | 刷新方式 | 协议 | namespace | group |
|---|---|---|---|---|
| `local-solon-cloud-plugin` | 不支持 | / | 不支持 | 支持 |
| `nacos-solon-cloud-plugin` | tcp 实时 | tcp | 支持 | 支持 |
| `nacos2-solon-cloud-plugin` | tcp 实时 | tcp | 支持 | 支持 |
| `nacos3-solon-cloud-plugin` | tcp 实时 | tcp | 支持 | 支持 |
| `consul-solon-cloud-plugin` | 定时拉取 | http | 不支持 | 支持 |
| `zookeeper-solon-cloud-plugin` | 实时 | tcp | 不支持 | 支持 |
| `polaris-solon-cloud-plugin` | 实时 | grpc | 支持 | 支持 |
| `etcd-solon-cloud-plugin` | 事件通知 | http | 不支持 | 支持 |
| `water-solon-cloud-plugin` | 事件通知 | http | 不支持 | 支持 |

### 配置示例（Nacos）

```yaml
solon.cloud.nacos:
  server: "127.0.0.1:8848"
  config:
    server: "127.0.0.1:8848"
    namespace: "dev"
    group: "DEFAULT_GROUP"
```

---

## Cloud Discovery — 服务注册与发现

### 适配插件

| 插件 | 发现刷新 | 协议 | namespace | group |
|---|---|---|---|---|
| `local-solon-cloud-plugin` | 不支持 | / | 不支持 | 不支持 |
| `jmdns-solon-cloud-plugin` | 支持 | dns | 不支持 | 支持 |
| `nacos-solon-cloud-plugin` | 实时 | tcp | 支持 | 支持 |
| `nacos2-solon-cloud-plugin` | 实时 | tcp | 支持 | 支持 |
| `consul-solon-cloud-plugin` | 定时拉取 | http | 不支持 | 不支持 |
| `zookeeper-solon-cloud-plugin` | 实时 | tcp | 不支持 | 不支持 |
| `polaris-solon-cloud-plugin` | 实时 | grpc | 支持 | 支持 |
| `etcd-solon-cloud-plugin` | 实时 | http | 不支持 | 支持 |

### 配置示例（Nacos）

```yaml
solon.cloud.nacos:
  server: "127.0.0.1:8848"
  discovery:
    namespace: "dev"
    group: "DEFAULT_GROUP"
    serviceName: "demo-service"
```

---

## Cloud Event — 分布式事件总线

### 适配插件

| 插件 | 确认重试 | 自动延时 | 定时事件 | 消息事务 |
|---|---|---|---|---|
| `local-solon-cloud-plugin` | 支持 | 支持 | 支持(内存) | / |
| `folkmq-solon-cloud-plugin` | 支持 | 支持 | 支持(内存) | 支持 |
| `kafka-solon-cloud-plugin` | 支持 | / | / | 支持 |
| `rabbitmq-solon-cloud-plugin` | 支持 | 支持 | 支持(内存) | 支持 |
| `rocketmq-solon-cloud-plugin` | 支持 | 支持 | 半支持 | / |
| `rocketmq5-solon-cloud-plugin` | 支持 | 支持 | 支持 | 半支持 |
| `activemq-solon-cloud-plugin` | 支持 | 支持 | 支持(内存) | 支持 |
| `mqtt-solon-cloud-plugin` | 支持 | / | / | / |
| `mqtt5-solon-cloud-plugin` | 支持 | / | / | / |
| `jedis-solon-cloud-plugin` | / | / | / | / |

### 事件发布与订阅

```java
// 发布
CloudClient.event().publish(new CloudEvent("topic.order", "order-1"));

// 订阅
@CloudEvent("topic.order")
public void onOrder(CloudEvent event) {
    System.out.println(event.content());
}
```

虚拟组配置（类似 namespace 隔离）：

```yaml
solon.cloud.water:
  event:
    group: demo  # 所有发送、订阅自动加上此组
```

---

## Cloud Job — 分布式定时任务

### 适配插件

| 插件 | cron | 自动注册 | 支持脚本 | 分布式调度 | 控制台 |
|---|---|---|---|---|---|
| `local-solon-cloud-plugin` | 支持 | 支持 | 不支持 | 不支持 | 无 |
| `quartz-solon-cloud-plugin` | 支持 | 支持 | 不支持 | 支持 | 无 |
| `water-solon-cloud-plugin` | 支持 | 支持 | 支持 | 支持 | 有 |
| `xxl-job-solon-cloud-plugin` | 支持 | 不支持 | 不支持 | 支持 | 有 |
| `powerjob-solon-cloud-plugin` | 支持 | 不支持 | 不支持 | 支持 | 有 |

### 任务声明

```java
@CloudJob("demoJob")
public class DemoJob implements JobHandler {
    @Override
    public void handle(Context ctx) throws Throwable {
        // 任务逻辑
    }
}
```

```yaml
solon.cloud.local:
  job:
    demoJob:
      cron: "0 0/5 * * * ?"  # 每 5 分钟
```

---

## Cloud File — 分布式文件服务

### 适配插件

| 插件 | 本地文件 | 云端文件 | 支持服务商 |
|---|---|---|---|
| `local-solon-cloud-plugin` | 支持 | / | / |
| `aliyun-oss-solon-cloud-plugin` | / | 支持 | 阿里云 |
| `aws-s3-solon-cloud-plugin` | / | 支持 | S3 协议 |
| `file-s3-solon-cloud-plugin` | 支持 | 支持 | S3 + 本地 |
| `qiniu-kodo-solon-cloud-plugin` | / | 支持 | 七牛云 |
| `minio-solon-cloud-plugin` | / | 支持 | MinIO |
| `minio7-solon-cloud-plugin` | / | 支持 | MinIO |
| `fastdfs-solon-cloud-plugin` | / | 支持 | FastDFS |

### 文件操作

```java
// 上传
CloudClient.file().upload("test.txt", inputStream);

// 下载
InputStream content = CloudClient.file().download("test.txt");
```

---

## Cloud Breaker — 熔断/限流

### 适配插件

| 插件 | Backend |
|---|---|
| `semaphore-solon-cloud-plugin` | 信号量 |
| `guava-solon-cloud-plugin` | Guava RateLimiter |
| `sentinel-solon-cloud-plugin` | Alibaba Sentinel |
| `resilience4j-solon-cloud-plugin` | Resilience4j |

---

## Cloud Gateway — 分布式网关

Solon Cloud Gateway 提供服务路由与拦截能力。

### 建议

- **推荐**使用专业网关产品（nginx、apisix、kong 等）
- Solon Cloud Gateway 可用于 Java 技术栈内的网关场景

### 核心能力

- 服务路由（基于 LoadBalance）
- 全局过滤器（`CloudGatewayFilter`）
- 路由过滤器定制
- 签权/跨域处理
- 基于 Cloud Config 动态更新路由
- 响应式支持

---

## Cloud Trace — 链路追踪

Solon Cloud 内置链路追踪支持，可通过 `solon-cloud-trace` 相关插件集成。

## Cloud Id — 分布式 ID

提供分布式唯一 ID 生成能力。

## Cloud Lock — 分布式锁

提供分布式锁服务。
