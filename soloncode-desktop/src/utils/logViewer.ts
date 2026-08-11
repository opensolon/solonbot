/** Redact common credentials before diagnostic logs are rendered in the UI. */
export function redactLogContent(content: string): string {
  return content
    .replace(
      /(api[_-]?key|access[_-]?token|auth[_-]?token|password|client[_-]?secret|app[_-]?secret)(\s*[=:]\s*)("[^"\r\n]*"|'[^'\r\n]*'|[^\s,;]+)/gi,
      '$1$2<redacted>',
    )
    .replace(/(authorization\s*[=:]\s*)bearer\s+[^\s,;]+/gi, '$1Bearer <redacted>')
    .replace(/\bbearer\s+[a-z0-9._~-]{8,}/gi, 'Bearer <redacted>')
    .replace(/\bsk-[a-z0-9_-]{12,}/gi, '<redacted>');
}
