# Remoting — RPC / Socket.D 通信

> 适用场景：服务间 RPC 调用、Socket.D 双向通信协议。

## RPC — 基于 Nami 的远程调用

### 基础使用

Dependency: `nami`（通常通过 `solon-cloud` 或单独引入）

**服务端：**
```java
@Remoting
public class UserServiceImpl implements UserService {
    @Override
    public User getUser(long userId) {
        return new User(userId, "noear");
    }

    @Override
    public void addUser(User user) {
        // ...
    }
}
```

**客户端注入：**
```java
@Controller
@Mapping("user")
public class UserController {
    @NamiClient(name = "userapi", path = "/rpc/v1/user")
    UserService userService;

    @Post
    @Mapping("register")
    public Result register(User user) {
        userService.add(user);
        return Result.succeed();
    }
}
```

### 序列化定制

通过配置器全局定制编码/解码：

```java
@Configuration
public class Config {
    @Bean
    public NamiConfiguration initNami() {
        return (client, builder) -> {
            builder.decoder(SnackDecoder.instance);
            builder.encoder(SnackTypeEncoder.instance);
        };
    }
}
```

或在接口声明时指定内容类型：

```java
@NamiClient(name = "userapi", path = "/rpc/v1/user", headers = ContentTypes.JSON)
UserService userService;
```

### 构建器模式（手动创建）

```java
UserService userService = Nami.builder()
        .name("userapi")
        .path("/rpc/v1/user")
        .decoder(SnackDecoder.instance)
        .encoder(SnackTypeEncoder.instance)
        .create(UserService.class);
```

### 超时与心跳

```java
// 注解方式
@NamiClient(name = "userapi", path = "/rpc/v1/user", timeout = 300, heartbeat = 30)
UserService userService;

// 构建器方式
UserService userService = Nami.builder()
        .name("userapi").path("/rpc/v1/user")
        .timeout(300).heartbeat(30)
        .create(UserService.class);
```

- `timeout`（秒）：对 http、socket、websocket 通道都有效
- `heartbeat`（秒）：仅对 socket、websocket 通道有效

---

## LoadBalance — 负载均衡

内核接口，nami 和 httputils 都使用它进行服务调用：

```java
LoadBalance loadBalance = LoadBalance.get("serviceName");
String server = loadBalance.getServer();
```

默认实现：`CloudLoadBalanceFactory`（基于 Solon Cloud Discovery）。

策略定制：

```java
@Configuration
public class Config {
    @Bean
    public CloudLoadStrategy loadStrategy() {
        return new CloudLoadStrategyDefault(); // 默认轮询
        // return new CloudLoadStrategyIpHash(); // IP 哈希
    }
}
```

---

## Socket.D — 双向通信协议

Solon 特色通信协议，支持 tcp、ws、udp 传输。

Dependency: `socketd-transport-netty`

### 集成配置

```java
@Configuration
public class SdConfig {
    @Bean
    public ClientSession clientSession() throws IOException {
        return SocketD.createClient("sd:tcp://127.0.0.1:18602").open();
    }
}
```

### Mono 模式（请求-应答）

```java
@Controller
public class DemoController {
    @Inject ClientSession clientSession;

    @Mapping("/hello")
    public Mono<String> hello(String name) {
        return Mono.create(sink -> {
            Entity entity = new StringEntity("hello").metaPut("name", name);
            clientSession.sendAndRequest("hello", entity)
                    .thenReply(reply -> sink.success(reply.dataAsString()))
                    .thenError(sink::error);
        });
    }
}
```

### Flux 模式（订阅-流式）

```java
@Mapping("/hello2")
public Flux<String> hello2(String name) {
    return Flux.create(sink -> {
        Entity entity = new StringEntity("hello")
                .metaPut("name", name).range(5, 5);
        clientSession.sendAndSubscribe("hello", entity)
                .thenReply(reply -> {
                    sink.next(reply.dataAsString());
                    if (reply.isEnd()) sink.complete();
                })
                .thenError(sink::error);
    });
}
```

### Socket.D 协议转 MVC 接口

Socket.D 支持将协议转为标准 MVC 风格接口，可以像写 HTTP 接口一样写 Socket.D 服务。

### Socket.D 主要场景

| 场景 | 说明 |
|---|---|
| 消息上报 | 单向消息发送 |
| 消息应答 | 请求-响应模式 |
| 消息订阅 | 流式数据推送 |
| RPC 调用 | 远程方法调用 |
| 双向 RPC | 单连接双向调用 |
| 消息鉴权 | 带认证的消息通信 |
| RPC 鉴权 | 带认证的远程调用 |

### 借用 HTTP Server 端口

Socket.D 可以与 HTTP Server 共享同一端口，简化部署。
