export const EMPTY_RESPONSE_ERROR = '回答失败：模型未返回任何内容，请重试。';
export const RESPONSE_TIMEOUT_ERROR = '回答超时：长时间未收到模型响应，请重试。';
export const RESPONSE_PROTOCOL_ERROR = '响应格式异常，请重试。';

const DISPLAY_PREFIX_PATTERN = /^(回答失败|回答超时|请求失败|连接失败|连接恢复失败|等待响应超时|响应格式异常)[：:，,\s]?/;

export function formatResponseErrorText(reason: unknown, fallback = '回答失败，请重试。'): string {
  if (typeof reason !== 'string' || !reason.trim()) return fallback;

  const compact = reason.replace(/\s+/g, ' ').trim();
  const limited = compact.length > 500 ? `${compact.slice(0, 500)}…` : compact;
  return DISPLAY_PREFIX_PATTERN.test(limited) ? limited : `回答失败：${limited}`;
}
