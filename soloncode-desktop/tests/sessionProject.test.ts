import assert from 'node:assert/strict';
import test from 'node:test';
import { isUnlinkedEmptySession } from '../src/utils/sessionProject.ts';

test('allows an empty unlinked conversation to attach to a project', () => {
  assert.equal(isUnlinkedEmptySession({ messageCount: 0 }), true);
  assert.equal(isUnlinkedEmptySession({ messageCount: 0, workspacePath: '__unlinked__' }), true);
});

test('does not reattach a conversation with messages or an existing project', () => {
  assert.equal(isUnlinkedEmptySession({ messageCount: 1, workspacePath: '__unlinked__' }), false);
  assert.equal(isUnlinkedEmptySession({ messageCount: 0, workspacePath: 'C:/workspace/demo' }), false);
});
