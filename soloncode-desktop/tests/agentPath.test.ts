import assert from 'node:assert/strict';
import test from 'node:test';
import { agentConfigFilePath } from '../src/utils/agentPath.ts';

test('builds the Agent config path with the native-looking separator', () => {
  assert.equal(
    agentConfigFilePath('C:\\Users\\bai\\.soloncode\\agents\\demo'),
    'C:\\Users\\bai\\.soloncode\\agents\\demo\\AGENT.md',
  );
  assert.equal(agentConfigFilePath('/home/bai/.soloncode/agents/demo'), '/home/bai/.soloncode/agents/demo/AGENT.md');
});

test('removes the Windows extended-length prefix before appending AGENT.md', () => {
  assert.equal(
    agentConfigFilePath('\\\\?\\C:\\Users\\bai\\.soloncode\\agents\\demo'),
    'C:\\Users\\bai\\.soloncode\\agents\\demo\\AGENT.md',
  );
  assert.equal(
    agentConfigFilePath('\\\\?\\UNC\\server\\share\\agents\\demo'),
    '\\\\server\\share\\agents\\demo\\AGENT.md',
  );
});
