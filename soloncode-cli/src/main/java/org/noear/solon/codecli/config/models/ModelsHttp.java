package org.noear.solon.codecli.config.models;

import org.noear.snack4.ONode;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;

import java.io.InterruptedIOException;
import java.util.concurrent.TimeoutException;

/**
 * 模型列表请求的公共执行逻辑。
 * <p>
 * HttpUtils#get() 对 4xx/5xx 不抛异常，只把错误响应体当正文返回；若适配器只解析 JSON 数组，
 * 404 这类“没有模型列表接口”的响应会被当成空列表，最终误报为“拉取成功但没有模型”。
 * 因此这里统一检查状态码，并把失败归类成 {@link ModelsFetchReason}。
 */
public final class ModelsHttp {
    private ModelsHttp() {
    }

    /**
     * 发起 GET 请求并返回响应体，非 2xx 一律按对应原因抛出
     *
     * @param http 已配置好地址、请求头与代理的请求对象
     * @param tag  协议标识，仅用于异常信息（不含地址与密钥）
     */
    public static String getBody(HttpUtils http, String tag) {
        int status = 0;
        try (HttpResponse resp = http.exec("GET")) {
            status = resp.code();
            String body = resp.bodyAsString();

            if (status < 200 || status >= 300) {
                throw new ModelsFetchException(
                        tag + " model list request failed, status=" + status,
                        ModelsFetchReason.ofStatus(status), status, null);
            }

            return body;
        } catch (ModelsFetchException e) {
            throw e;
        } catch (Exception e) {
            ModelsFetchReason reason = isTimeout(e) ? ModelsFetchReason.TIMEOUT : ModelsFetchReason.NETWORK_ERROR;
            throw new ModelsFetchException(tag + " model list request failed", reason, status, e);
        }
    }

    /**
     * 解析响应体，非 JSON 内容（如网关 HTML 错误页）按响应格式不可识别处理
     */
    public static ONode parseJson(String body, String tag) {
        if (body == null || body.trim().isEmpty()) {
            throw new ModelsFetchException(tag + " model list response is empty",
                    ModelsFetchReason.INVALID_RESPONSE, 200, null);
        }

        try {
            return ONode.ofJson(body);
        } catch (Exception e) {
            throw new ModelsFetchException(tag + " model list response is not valid json",
                    ModelsFetchReason.INVALID_RESPONSE, 200, e);
        }
    }

    /**
     * 超时判定：底层实现可能把 SocketTimeoutException 包在多层异常里
     */
    private static boolean isTimeout(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof InterruptedIOException || cur instanceof TimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
