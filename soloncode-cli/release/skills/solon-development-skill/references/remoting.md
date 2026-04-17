# Remoting — RPC / Socket.D 通信

> 适用场景：服务间 RPC 调用、Socket.D 双向通信协议。

---

## 通道与序列化组件

RPC 开发由三部分组成：服务接口声明（独立项目）、服务端实现、客户端消费。

**通道组件：**

| 通道 | 客户端组件 | 服务端支持组件 |
|---|---|---|
| Http 通道 | nami-channel-http | solon-server-jdkhttp / solon-server-smarthttp / solon-server-jetty / solon-server-undertow |
| Socket.D 通道 | nami-channel-socketd + socket.d | solon-server-socketd + socket.d |

**序列化方案组件：**

| 序列化方案 | 客户端组件                                                                               | 服务端组件                                                                                                                   |
|---|-------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| Json | nami-coder-snack4 / nami-coder-fastjson / nami-coder-fastjson2 / nami-coder-jackson | solon-serialization-snack4 / solon-serialization-fastjson / solon-serialization-fastjson2 / solon-serialization-jackson |
| Hessian | nami-coder-hessian                                                                  | solon-serialization-hessian                                                                                             |
| Fury | nami-coder-fury                                                                     | solon-serialization-fury                                                                                                |
| Kryo | nami-coder-kryo                                                                     | solon-serialization-kryo                                                                                                |
| Protostuff | nami-coder-protostuff                                                               | solon-serialization-protostuff                                                                                          |

> 选择序列化方案时，客户端与服务端的框架应一一对应。客户端需一个 channel + 一个 coder；服务端需一个 server + 一个 serialization。

---

## RPC — 基于 Nami 的远程调用

### 依赖说明

- 服务端：引入 `solon-web`（已包含 smarthttp + snack4 序列化）
- 客户端：引入 `solon-rpc`（已集成 RPC 客户端所需的 Nami 组件）

### 服务端：接口定义与实现

**接口定义**（独立项目，可被双方引用）：

```java
// 注意：函数名不能相同
public interface UserService {
    void add(User user);
    User getById(long userId);
}
```

**数据实体**（实现 Serializable，以适应任何序列化方案）：

```java
@Data
public class User implements Serializable {
    long userId;
    String name;
    int level;
}
```

**服务端实现**（引入依赖：接口项目 + `solon-web`）：

```java
// 服务端应用
public class ServerApp {
    public static void main(String[] args) {
        Solon.start(ServerApp.class, args);
    }
}

@Mapping("/rpc/v1/user") // 必须有 @Mapping
@Remoting
public class UserServiceImpl implements UserService {
    @Inject
    UserMapper userMapper;

    @Override
    public void add(User user) {
        userMapper.add(user);
    }

    @Override
    public User getById(long userId) {
        return userMapper.getById(userId);
    }
}
```

配置：

```yaml
server.port: 9001

solon.app:
  group: "demo"
  name: "userapi"
```

### 客户端：服务消费

```java
public class ClientApp {
    public static void main(String[] args) {
        Solon.start(ClientApp.class, args);
    }
}

@Mapping("user")
@Controller
public class UserController {
    // 直接指定地址和序列化方案
    @NamiClient(url = "http://localhost:9001/rpc/v1/user", headers = ContentTypes.JSON)
    UserService userService;

    @Post
    @Mapping("register")
    public Result register(User user) {
        userService.add(user);
        return Result.succeed();
    }
}
```

配置：

```yaml
server.port: 8081

solon.app:
  group: "demo"
  name: "userdemo"
```

### 序列化定制

通过配置器全局定制编码/解码：

```java
@Configuration
public class Config {
    @Bean
    public NamiConfiguration initNami() {
        return new NamiConfiguration() {
            @Override
            public void config(NamiClient client, NamiBuilder builder) {
                builder.decoder(SnackDecoder.instance);
                builder.encoder(SnackTypeEncoder.instance);
            }
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

## Nami 注解说明

### @NamiClient 注解

| 字段 | 说明 | 示例 |
|---|---|---|
| url | 完整的 url 地址 | `http://api.water.org/cfg/get/` |
| group | 服务组 | `water` |
| name | 服务名或负载均衡组件名（配合发现服务使用） | `waterapi` |
| path | 路径 | `/cfg/get/` |
| headers | 添加头信息 | `{"head1=a","head2=b"}` |
| configuration | 配置器 | |
| localFirst | 本地优先（如果本地有接口实现，则优先用） | `false` |
| timeout | 超时（秒） | `300` |
| heartbeat | 心跳间隔（秒），仅对 socket、websocket 通道有效 | `30` |

### @NamiMapping 注解（注在函数上，默认不需要）

| 字段 | 说明 |
|---|---|
| value | 映射值，支持三种情况 |

映射值的三种情况：

- 没有注解：没有参数时执行 GET，有参数时执行 POST；path 为函数名（默认行为）
- `@NamiMapping("GET")`：执行 GET 请求，path 为函数名
- `@NamiMapping("PUT user/a.0.1")`：执行 PUT 请求，path 为 `user/a.0.1`

### @NamiBody 注解（注在参数上）

| 字段 | 说明 |
|---|---|
| contentType | 内容类型 |

注在参数上，表示以此参数做为内容主体进行提交。

### @NamiParam 注解（注在参数上）

| 字段 | 说明 |
|---|---|
| value | 参数名 |

注在参数上，主要为参数标注参数名字（v3.2.0 后支持）。

---

## Nami 声明式 HttpClient

Nami 除做 RPC 客户端外，还提供声明式 HttpClient 的体验能力。

### 接口声明与使用

```java
@NamiClient(url = "http://localhost:8080/ComplexModelService/")
public interface IComplexModelService {
    // POST http://localhost:8080/ComplexModelService/save
    void save(@NamiBody ComplexModel model);

    // POST http://localhost:8080/ComplexModelService/read
    ComplexModel read(Integer modelId);
}
```

调整请求方式和路径：

```java
@NamiClient(url = "http://localhost:8080/ComplexModelService/", headers = "TOKEN=xxx")
public interface IComplexModelService {
    // PUT http://localhost:8080/ComplexModelService/save
    @NamiMapping("PUT")
    void save(@NamiBody ComplexModel model);

    // GET http://localhost:8080/ComplexModelService/api/1.0.1?modelId=xxx
    @NamiMapping("GET api/1.0.1")
    ComplexModel read(Integer modelId);
}
```

注入使用：

```java
@Controller
public class Demo {
    // 注入时没有配置，则使用接口声明时的注解配置
    @NamiClient
    IComplexModelService complexModelService;

    @Mapping
    public void test(ComplexModel model) {
        complexModelService.save(model);
    }
}
```

---

## Nami 使用 Solon 注解（v3.3.0 后支持）

新的特性可以直接 copy 控制器上的代码（微做调整），即可作为客户端接口。

**注解的对应关系：**

| Nami 注解 | Solon 注解 | 备注 |
|---|---|---|
| `@NamiMapping` | `@Mapping` / `@Get` / `@Put` / `@Post` / `@Delete` / `@Patch` | |
| | `@Consumes` | 声明请求的内容类型 |
| `@NamiBody` | `@Body` | 声明参数为 body（会转为独立主体发送） |
| `@NamiParam` | `@Param` | |
| | `@Header` | 声明参数为 header（会自动转到请求头） |
| | `@Cookie` | 声明参数为 cookie（会自动转到请求头） |
| | `@Path` | 声明参数为 path（会自动转到 url，v3.3.1 后支持） |

**示例：**

```java
@NamiClient
public interface HelloService {
    @Post
    @Mapping("hello")
    String hello(String name, @Header("H1") String h1, @Cookie("C1") String c1);

    @Consumes(MimeType.APPLICATION_JSON_VALUE)
    @Mapping("/test01")
    @Post
    String test01(@Param("ids") List<String> ids);

    @Mapping("/test02")
    @Post
    String test02(@Param("file") UploadedFile file);

    @Mapping("/test04/{name}")
    @Get
    String test04(@Path("name") String name);
}
```

简化模式（"路径段"与"方法"同名的、"参数名"相同的，可以简化）：

```java
@NamiClient
public interface HelloService {
    @Post
    String hello(String name, @Header("H1") String h1, @Cookie("C1") String c1);

    @Consumes(MimeType.APPLICATION_JSON_VALUE)
    @Post
    String test01(List<String> ids);

    @Mapping("/test05/?type={type}")
    @Post
    String test05(int type, @Body Order order);
}
```

进一步简化（有参数的默认是 POST 方式，没参数的默认是 GET 方式）：

```java
@NamiClient
public interface HelloService {
    String hello(String name, @Header("H1") String h1, @Cookie("C1") String c1);

    @Consumes(MimeType.APPLICATION_JSON_VALUE)
    String test01(List<String> ids);

    String test02(UploadedFile file);

    @Post // 如果是 GET 请求，这个注解可以去掉
    String test03();

    @Mapping("/test04/{name}")
    String test04(String name);

    @Mapping("/test05/?type={type}")
    String test05(int type, @Body Order order);
}
```

---

## Nami 过滤器

Nami 过滤器有两种作用域：自身过滤器（仅对当前接口有效）和全局过滤器（对所有接口有效）。

### 自身过滤器

为当前接口添加专属过滤器。在声明式 HttpClient 体验中，方便为不同站点指定编码等过滤策略。

```java
@NamiClient(url = "http://localhost:8080/ComplexModelService/")
public interface IComplexModelService extends Filter {
    @NamiMapping("PUT")
    void save(@NamiBody ComplexModel model);

    @NamiMapping("GET api/1.0.1")
    ComplexModel read(Integer modelId);

    // 自带过滤器，要用 default 直接实现
    default Result doFilter(Invocation inv) throws Throwable {
        inv.headers.put("Token", "Xxx");
        inv.headers.put("TraceId", Utils.guid());
        inv.config.setDecoder(SnackDecoder.instance);
        inv.config.setEncoder(SnackEncoder.instance);
        return inv.invoke();
    }
}
```

### 全局过滤器

使用组件注解：

```java
@Component
public class NamiFilterImpl implements org.noear.nami.Filter {
    @Override
    public Result doFilter(Invocation inv) throws Throwable {
        inv.headers.put("Token", "Xxx");
        inv.headers.put("TraceId", Utils.guid());
        inv.config.setDecoder(SnackDecoder.instance);
        inv.config.setEncoder(SnackEncoder.instance);
        return inv.invoke();
    }
}
```

或使用手动注册（要注意时机，在 Nami 使用前完成注册）：

```java
NamiManager.reg(inv -> {
    inv.headers.put("Token", "Xxx");
    inv.headers.put("TraceId", Utils.guid());
    inv.config.setDecoder(SnackDecoder.instance);
    inv.config.setEncoder(SnackEncoder.instance);
    return inv.invoke();
});
```

---

## 注册与发现服务

### 本地发现服务

引入 `solon-cloud` 插件依赖（自带了本地发现能力）。

服务端不需要改造，也不需要注册。

客户端配置：

```yaml
solon.cloud.local:
  discovery:
    service:
      userapi: # 添加本地服务发现（userapi 为服务名）
        - "http://localhost:8081"
```

客户端代码：

```java
@Mapping("user")
public class UserController {
    // 指定服务名、路径和序列化方案（不用关注服务地址）
    @NamiClient(name = "userapi", path = "/rpc/v1/user", headers = ContentTypes.JSON)
    UserService userService;
}
```

### 分布式注册与发现服务

使用 Solon Cloud Discovery 相关组件（如 nacos、zookeeper、water 等）。

服务端配置（以 water 为例）：

```yaml
server.port: 9001

solon.app:
  group: "demo"
  name: "userapi"

solon.cloud.water:
  server: "waterapi:9371"
```

客户端配置：

```yaml
server.port: 8081

solon.app:
  group: "demo"
  name: "userdemo"

solon.cloud.water:
  server: "waterapi:9371"
```

客户端代码与本地发现方式一致，都使用 `name` 而非 `url` 引用服务。

---

## LoadBalance — 负载均衡

内核接口，nami 和 httputils 都使用它进行服务调用：

```java
// 根据服务名获取"负载均衡"
LoadBalance loadBalance = LoadBalance.get("serviceName");

// 根据分组和服务名获取"负载均衡"
LoadBalance loadBalance = LoadBalance.get("groupName", "serviceName");

// 获取服务实例地址（例："http://12.0.1.2.3:8871"）
String server = loadBalance.getServer();
```

默认实现：`CloudLoadBalanceFactory`（基于 Solon Cloud Discovery）。引入 Solon Cloud Discovery 相关的组件即可使用。

### 策略定制

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

自定义策略示例（如基于 k8s 服务地址）：

```java
@Component
public class CloudLoadStrategyImpl implements CloudLoadStrategy {
    @Override
    public String getServer(Discovery discovery) {
        // 通过服务名，获取 k8s 的服务地址
        return K8sUtil.getServer(discovery.service());
    }
}
```

### 自定义负载均衡实现

基于内核接口 `LoadBalance.Factory` 实现：

```java
@Component
public class LoadBalanceFactoryImpl implements LoadBalance.Factory {
    @Override
    public LoadBalance create(String group, String service) {
        if ("local".equals(service)) {
            return new LoadBalanceImpl();
        }
        return null;
    }
}
```

---

## Socket.D — 双向通信协议

Solon 特色通信协议，支持 tcp、ws、udp 传输。

Dependency：`solon-server-socketd` + 传输协议包（如 `socketd-transport-netty`）

### 服务端集成

引入依赖：

```xml
<!-- socket.d 的 solon 服务启动插件 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-server-socketd</artifactId>
</dependency>

<!-- 传输协议包（按需选择），会使用独立的端口 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>socketd-transport-netty</artifactId>
    <version>${socketd.version}</version>
</dependency>
```

启用服务：

```java
public class DemoApp {
    public static void main(String[] args) {
        Solon.start(DemoApp.class, args, app -> {
            // 启用 Socket.D 服务
            app.enableSocketD(true);
        });
    }
}
```

使用 `@ServerEndpoint` 监听：

```java
@ServerEndpoint("/demo/{id}")
public class SocketDDemo extends SimpleListener {
    @Override
    public void onMessage(Session session, Message message) throws IOException {
        session.send("test", new StringEntity("我收到了：" + message));
        // session.param("id"); // 获取路径变量、queryString 变量、握手变量
    }
}
```

### 集成配置参考

```yaml
# 服务 socket 信号名称（默认为 ${solon.app.name}）
server.socket.name: "waterapi.tcp"
# 服务 socket 信号端口（默认为 20000+${server.port}）
server.socket.port: 28080
# 服务 socket 信号主机（ip）
server.socket.host: "0.0.0.0"
# 服务 socket 信号包装端口（一般用 docker + 服务注册时才可能用到）
server.socket.wrapPort: 28080
# 服务 socket 信号包装主机
server.socket.wrapHost: "0.0.0.0"
# 服务 socket 最小线程数（默认 0 表示自动，支持固定值 2 或倍数 x2）
server.socket.coreThreads: 0
# 服务 socket 最大线程数（默认 0 表示自动，支持固定值 32 或倍数 x32）
server.socket.maxThreads: 0
# 服务 socket 闲置线程或连接超时（0 表示自动，单位毫秒）
server.socket.idleTimeout: 0
# 服务 socket 是否为 IO 密集型
server.socket.ioBound: true
```

不同协议架构的独立端口自动处理：

| 协议架构 | 端口 | 示例 |
|---|---|---|
| sd:tcp | ${server.socket.port} | 28080 |
| sd:udp | ${server.socket.port} + 1 | 28081 |
| sd:ws | ${server.socket.port} + 2 | 28082 |

### 客户端连接

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

Socket.D 支持将协议转为标准 MVC 风格接口（v2.6.0 后支持），可以像写 HTTP 接口一样写 Socket.D 服务。

服务端代码：

```java
// 协议转换处理
@ServerEndpoint("/mvc/")
public class SocketdAsMvc extends ToHandlerListener {
    @Override
    public void onOpen(Session s) {
        // 可选：加鉴权
        if (!"a".equals(s.param("u"))) {
            s.close();
            return;
        }
        super.onOpen();
    }
}

// 控制器
@Controller
public class HelloController {
    @Socket // 不加限定注解的话，可同时支持 http 请求
    @Mapping("/mvc/hello")
    public Result hello(long id, String name) {
        return Result.succeed();
    }
}
```

客户端以 RPC 代理模式调用（引入 `nami-channel-socketd`）：

```java
// 客户端调用服务端的 MVC
HelloService rpc = SocketdProxy.create("sd:ws://localhost:28082/mvc/?u=a", HelloService.class);
System.out.println("MVC result:: " + rpc.hello("noear"));
```

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
