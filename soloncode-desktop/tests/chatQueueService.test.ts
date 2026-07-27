import test from 'node:test';
import assert from 'node:assert/strict';
import { MAX_CHAT_QUEUE_SIZE, serializeQueue, type QueuedChatMessage } from '../src/services/chatQueueService.ts';

function item(index: number): QueuedChatMessage {
  return {
    id: `q-${index}`,
    text: `task ${index}`,
    displayText: `task ${index}`,
    createdAt: index,
    options: { model: 'provider', modelName: 'model', agent: '', contexts: [], attachments: [], reasoningEffort: 'medium' },
  };
}

test('serializes only the supported queue metadata and enforces the server limit', () => {
  const result = serializeQueue(Array.from({ length: 12 }, (_, index) => item(index)));
  assert.equal(result.length, MAX_CHAT_QUEUE_SIZE);
  assert.equal(result[0].text, 'task 0');
  assert.equal(result[0].hasFiles, false);
  assert.equal('attachments' in result[0], false);
});

test('marks queued file context without persisting attachment contents', () => {
  const queued = item(1);
  queued.options.attachments.push({ id: 'secret', name: 'note.txt', type: 'text', content: 'private body' });
  const [result] = serializeQueue([queued]);
  assert.equal(result.hasFiles, true);
  assert.equal(JSON.stringify(result).includes('private body'), false);
});

test('persists Goal token and iteration limits in the running queue', () => {
  const queued = item(2);
  queued.options.mode = 'goal';
  queued.options.goalMaxTokens = 12000;
  queued.options.goalMaxIterations = 6;

  const [result] = serializeQueue([queued]);

  assert.equal(result.goalMaxTokens, 12000);
  assert.equal(result.goalMaxIterations, 6);
});
