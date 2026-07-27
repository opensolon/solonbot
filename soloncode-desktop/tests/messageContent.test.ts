import assert from 'node:assert/strict';
import test from 'node:test';
import { buildUserMessageContents, isSafeImageDataUrl } from '../src/utils/messageContent.ts';

const PNG_DATA_URL = 'data:image/png;base64,iVBORw0KGgo=';

test('keeps image attachments alongside user text', () => {
  const contents = buildUserMessageContents('请分析这张图', [
    { name: 'chart.png', type: 'image', content: PNG_DATA_URL },
  ]);

  assert.deepEqual(contents, [
    { type: 'IMAGE', text: PNG_DATA_URL, name: 'chart.png' },
    { type: 'TEXT', text: '请分析这张图' },
  ]);
});

test('does not turn arbitrary data URLs into rendered image content', () => {
  assert.equal(isSafeImageDataUrl('data:text/html;base64,PHNjcmlwdD4='), false);
  assert.equal(isSafeImageDataUrl('https://example.com/image.png'), false);

  const contents = buildUserMessageContents('hello', [
    { name: 'unsafe.html', type: 'image', content: 'data:text/html;base64,PHNjcmlwdD4=' },
  ]);
  assert.deepEqual(contents, [{ type: 'TEXT', text: 'hello' }]);
});
