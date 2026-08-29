package org.noear.solon.codecli.config.models;

/**
 * 模型列表拉取失败异常。
 * 适配器不得用空列表吞掉网络、认证或响应解析错误，否则调用方会误判为成功。
 */
public class ModelsFetchException extends RuntimeException {
    public ModelsFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
