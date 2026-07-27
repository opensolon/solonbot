import type { ContentItem } from '../types';

interface UserAttachment {
  name: string;
  type: string;
  content: string;
}

const ALLOWED_IMAGE_MIME_TYPES = new Set([
  'image/png',
  'image/jpeg',
  'image/jpg',
  'image/gif',
  'image/webp',
  'image/bmp',
  'image/svg+xml',
  'image/x-icon',
  'image/vnd.microsoft.icon',
  'image/tiff',
  'image/avif',
]);

/** Only allow base64 image data URLs produced by the local attachment picker. */
export function isSafeImageDataUrl(value: unknown): value is string {
  if (typeof value !== 'string' || !value.startsWith('data:image/')) return false;

  const base64Marker = ';base64,';
  const markerIndex = value.indexOf(base64Marker);
  if (markerIndex <= 5 || markerIndex > 64 || markerIndex + base64Marker.length >= value.length) {
    return false;
  }

  const mimeType = value.slice('data:'.length, markerIndex).toLowerCase();
  return ALLOWED_IMAGE_MIME_TYPES.has(mimeType);
}

export function buildUserMessageContents(
  messageText: string,
  attachments: readonly UserAttachment[] = [],
): ContentItem[] {
  const imageItems: ContentItem[] = attachments
    .filter(attachment => attachment.type === 'image' && isSafeImageDataUrl(attachment.content))
    .map(attachment => ({
      type: 'IMAGE',
      text: attachment.content,
      name: attachment.name,
    }));

  return [...imageItems, { type: 'TEXT', text: messageText }];
}
