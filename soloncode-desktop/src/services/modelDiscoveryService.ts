export interface ModelDiscoveryRequest {
  backendPort: number;
  apiUrl: string;
  apiKey: string;
  provider?: string;
  model?: string;
}

/**
 * 新版桌面后端使用 JSON POST；2026.7.27 及更早版本只接受 GET 查询参数。
 * 仅在服务端明确返回 405 时降级，避免正常情况下把 API Key 放进 URL。
 */
export async function requestDesktopModels(
  request: ModelDiscoveryRequest,
  fetcher: typeof fetch = fetch,
): Promise<Response> {
  const endpoint = `http://localhost:${request.backendPort}/desktop/chat/models/fetch`;
  const payload = {
    apiUrl: request.apiUrl,
    apiKey: request.apiKey,
    provider: request.provider || '',
    model: request.model || '',
  };
  const response = await fetcher(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (response.status !== 405) return response;

  const query = new URLSearchParams(payload);
  return await fetcher(`${endpoint}?${query.toString()}`, {
    method: 'GET',
    cache: 'no-store',
  });
}
