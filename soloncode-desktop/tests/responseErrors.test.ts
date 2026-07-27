import assert from 'node:assert/strict';
import test from 'node:test';
import { formatResponseErrorText } from '../src/utils/responseErrors.ts';

test('formats backend failures as visible answer errors', () => {
  assert.equal(formatResponseErrorText('model overloaded'), '回答失败：model overloaded');
  assert.equal(formatResponseErrorText('回答超时：请重试'), '回答超时：请重试');
});

test('uses a useful fallback and compacts multiline errors', () => {
  assert.equal(formatResponseErrorText(undefined), '回答失败，请重试。');
  assert.equal(formatResponseErrorText('line one\nline two'), '回答失败：line one line two');
});
