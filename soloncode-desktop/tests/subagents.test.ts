import assert from 'node:assert/strict';
import test from 'node:test';
import { extractSubagentHints } from '../src/utils/subagents.ts';

test('extracts only valid subagent hints returned by the web endpoint', () => {
  assert.deepEqual(extractSubagentHints([
    { name: 'help', description: '命令', type: 'command' },
    { name: 'plan', description: '规划代理', type: 'subagent' },
    { name: 'plan', description: '重复项', type: 'subagent' },
    { name: 'bad/name', description: '非法名称', type: 'subagent' },
    { name: 'reviewer', description: '审查代理', type: 'SUBAGENT' },
    { name: '基金助手', description: '中文名称', type: 'subagent' },
  ]), [
    { name: 'plan', description: '规划代理', enabled: true },
    { name: 'reviewer', description: '审查代理', enabled: true },
    { name: '基金助手', description: '中文名称', enabled: true },
  ]);
});
