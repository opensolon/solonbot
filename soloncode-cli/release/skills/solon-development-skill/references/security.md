# Security — 安全（认证/鉴权/CORS/加密）

> 适用场景：跨域处理、用户认证、路径鉴权、角色权限控制。

## CORS — 跨域处理

Dependency: `solon-web-cors`（已包含在 `solon-web` 中）

### 方式一：注解在控制器或方法上

```java
@CrossOrigin(origins = "*")
@Controller
public class DemoController {
    @Mapping("/hello")
    public String hello() { return "hello"; }
}
```

### 方式二：注解在基类

```java
@CrossOrigin(origins = "*")
public class BaseController {}

@Controller
public class DemoController extends BaseController {
    @Mapping("/hello")
    public String hello() { return "hello"; }
}
```

### 方式三：全局配置

```java
Solon.start(App.class, args, app -> {
    // 全局处理（过滤器模式，-1 优先级更高）
    app.filter(-1, new CrossFilter().allowedOrigins("*"));

    // 某段路径
    app.filter(new CrossFilter().pathPatterns("/user/**").allowedOrigins("*"));

    // 路由拦截器模式
    app.routerInterceptor(-1, new CrossInterceptor().allowedOrigins("*"));
});
```

---

## Auth — 用户认证与鉴权

Dependency: `solon-security-auth`

核心概念：通过 `AuthAdapter` 统一配置认证规则，通过 `AuthProcessor` 接口适配具体业务逻辑。

### 第 1 步：构建认证适配器

```java
@Configuration
public class Config {
    @Bean(index = 0)
    public AuthAdapter init() {
        return new AuthAdapter()
                .loginUrl("/login")
                .addRule(r -> r.include("**").verifyIp()
                        .failure((c, t) -> c.output("你的IP不在白名单")))
                .addRule(b -> b.exclude("/login**").exclude("/run/**").verifyPath())
                .processor(new AuthProcessorImpl())
                .failure((ctx, rst) -> ctx.render(rst));
    }
}
```

规则配置说明：
- `include(path)` — 规则包含的路径范围
- `exclude(path)` — 规则排除的路径范围
- `failure(..)` — 规则失败后的处理
- `verifyIp()` / `verifyPath()` / `verifyLogined()` — 验证方案

### 第 2 步：认证异常处理

```java
@Component
public class DemoFilter implements Filter {
    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        try {
            chain.doFilter(ctx);
        } catch (AuthException e) {
            AuthStatus status = e.getStatus();
            ctx.render(Result.failure(status.code, status.message));
        }
    }
}
```

### 第 3 步：实现认证处理器

```java
public class AuthProcessorImpl implements AuthProcessor {
    @Override
    public boolean verifyIp(String ip) {
        // 验证 IP 是否有权访问
        return true;
    }

    @Override
    public boolean verifyLogined() {
        // 验证用户是否已登录
        return getSubjectId() > 0;
    }

    @Override
    public boolean verifyPath(String path, String method) {
        // 验证路径，用户是否可访问
        return true;
    }

    @Override
    public boolean verifyPermissions(String[] permissions, Logical logical) {
        // 验证特定权限（verifyLogined 为 true 时触发）
        return true;
    }

    @Override
    public boolean verifyRoles(String[] roles, Logical logical) {
        // 验证特定角色（verifyLogined 为 true 时触发）
        return true;
    }
}
```

### 注解控制（特定权限/角色）

```java
@Controller
@Mapping("/demo/agroup")
public class AgroupController {
    @Mapping("")
    public void home() { /* 首页 */ }

    @AuthPermissions("agroup:edit")
    @Mapping("edit/{id}")
    public void edit(int id) { /* 需要编辑权限 */ }

    @AuthRoles("admin")
    @Mapping("edit/{id}/ajax/save")
    public void save(int id) { /* 需要管理员角色 */ }
}
```

### 模板中使用

```html
<@authPermissions name="user:del">我有 user:del 权限</@authPermissions>
<@authRoles name="admin">我有 admin 角色</@authRoles>
```

### 组合使用建议

- **规则控制**：在 AuthAdapter 中配置所有需要登录的地址（宏观控制）
- **注解控制**：在特定方法上控制权限和角色（细节把握）

---

## Vault — 配置加密

Dependency: `solon-security-vault`

用于敏感配置项的加密存储。

---

## Web 安全 — 请求头安全

Dependency: `solon-security-web`

提供 HTTP 请求头安全防护能力。
