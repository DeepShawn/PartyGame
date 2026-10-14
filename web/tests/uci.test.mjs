import assert from 'node:assert/strict';
import test from 'node:test';
import { loadModule } from './helpers.mjs';

const { uciToAction, squareIndex } = await loadModule('core/uci.ts');

const move = (from, to, promotion) => ({
  type: 'move',
  values: promotion === undefined ? [from, to] : [from, to, promotion],
  text: '',
});

test('squareIndex 与核心自顶向下的棋盘索引一致', () => {
  assert.equal(squareIndex('a8'), 0);
  assert.equal(squareIndex('h8'), 7);
  assert.equal(squareIndex('a1'), 56);
  assert.equal(squareIndex('h1'), 63);
  assert.equal(squareIndex('e2'), 52);
  assert.equal(squareIndex('e4'), 36);
});

test('uciToAction 在候选着法中匹配 from/to', () => {
  const legal = [move(52, 36), move(52, 44)];
  assert.deepEqual(uciToAction('e2e4', legal), move(52, 36));
  assert.deepEqual(uciToAction('e2e3', legal), move(52, 44));
});

test('uciToAction 无匹配候选时返回 null', () => {
  assert.equal(uciToAction('e2e4', [move(52, 44)]), null);
  assert.equal(uciToAction('e2e4', []), null);
});

test('uciToAction 识别非法输入', () => {
  assert.equal(uciToAction('', [move(52, 36)]), null);
  assert.equal(uciToAction('e2', [move(52, 36)]), null);
  assert.equal(uciToAction('i9a1', [move(52, 36)]), null);
  assert.equal(uciToAction('e2e4x', [move(52, 36)]), null);
  assert.equal(uciToAction('e2e4tt', [move(52, 36)]), null);
  assert.equal(uciToAction('(none)', [move(52, 36)]), null);
});

test('uciToAction 升变按 promotion 编码选择（2马3象4车5后）', () => {
  const promotions = [move(12, 4, 2), move(12, 4, 3), move(12, 4, 4), move(12, 4, 5)];
  assert.deepEqual(uciToAction('e7e8n', promotions), move(12, 4, 2));
  assert.deepEqual(uciToAction('e7e8b', promotions), move(12, 4, 3));
  assert.deepEqual(uciToAction('e7e8r', promotions), move(12, 4, 4));
  assert.deepEqual(uciToAction('e7e8q', promotions), move(12, 4, 5));
  assert.equal(uciToAction('e7e8q', [move(12, 4, 4)]), null);
});

test('uciToAction 多候选时优先选择无升变的普通着法', () => {
  const legal = [move(12, 4, 2), move(12, 4, 5), move(12, 4)];
  assert.deepEqual(uciToAction('e7e8', legal), move(12, 4));
  assert.deepEqual(uciToAction('e7e8', [move(12, 4, 2)]), move(12, 4, 2));
});
