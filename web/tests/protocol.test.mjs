import assert from 'node:assert/strict';
import test from 'node:test';
import { loadModule } from './helpers.mjs';

const { emptyMessage, parseRoomMessage, action, PROTOCOL_VERSION } = await loadModule('net/types.ts');

test('emptyMessage 提供协议规范的默认值', () => {
  const message = emptyMessage('join');
  assert.deepEqual(message, {
    type: 'join',
    protocolVersion: PROTOCOL_VERSION,
    roomId: '',
    matchId: 0,
    sequence: 0,
    name: '',
    gameId: '',
    ready: false,
    action: null,
    room: null,
    message: '',
  });
  assert.equal(PROTOCOL_VERSION, 1);
});

test('action 构造默认值并保留传入内容', () => {
  assert.deepEqual(action('pass'), { type: 'pass', values: [], text: '' });
  assert.deepEqual(action('move', [12, 28], 'x'), { type: 'move', values: [12, 28], text: 'x' });
});

test('parseRoomMessage 只接受白名单字段并丢弃未知字段', () => {
  const text = '{"type":"room","protocolVersion":1,"roomId":"AB12CD","matchId":4,"sequence":9,"name":"小猫","gameId":"gomoku","ready":true,"message":"你好","evil":{"inject":true},"__proto__":{"polluted":true},"extraTop":42}';
  const parsed = JSON.parse(text);
  assert.equal(Object.prototype.hasOwnProperty.call(parsed, '__proto__'), true, 'JSON.parse 保留 __proto__ 为自有属性');
  const message = parseRoomMessage(text);
  assert.deepEqual(Object.keys(message).sort(), [
    'action', 'gameId', 'matchId', 'message', 'name', 'protocolVersion',
    'ready', 'room', 'roomId', 'sequence', 'type',
  ]);
  assert.equal(message.type, 'room');
  assert.equal(message.roomId, 'AB12CD');
  assert.equal(message.matchId, 4);
  assert.equal(message.sequence, 9);
  assert.equal(message.name, '小猫');
  assert.equal(message.gameId, 'gomoku');
  assert.equal(message.ready, true);
  assert.equal(message.message, '你好');
  assert.equal(message.action, null);
  assert.equal(message.room, null);
  assert.equal(Object.prototype.hasOwnProperty.call(message, 'evil'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(message, 'extraTop'), false);
});

test('parseRoomMessage 对缺字段补齐默认值，类型不符则丢弃', () => {
  const message = parseRoomMessage(JSON.stringify({ type: 'leave', roomId: 7, ready: 'yes', matchId: null }));
  assert.equal(message.type, 'leave');
  assert.equal(message.roomId, '');
  assert.equal(message.ready, false);
  assert.equal(message.matchId, 0);
  assert.equal(message.protocolVersion, PROTOCOL_VERSION);
});

test('parseRoomMessage 清洗 action：只保留数字 values 与字符串 text', () => {
  const message = parseRoomMessage(JSON.stringify({
    type: 'action',
    action: { type: 'move', values: [0, 1, '2', null, 3.5, Number.NaN], text: 'go', other: 1 },
  }));
  assert.deepEqual(message.action, { type: 'move', values: [0, 1, 3.5], text: 'go' });
  assert.equal(Object.prototype.hasOwnProperty.call(message.action, 'other'), false);

  const noType = parseRoomMessage(JSON.stringify({ type: 'action', action: { values: [1] } }));
  assert.equal(noType.action, null);
});

test('parseRoomMessage 处理 room 字段的边界情况', () => {
  const room = { roomId: 'R1', gameId: 'chess', capacity: 2, members: [], phase: 'lobby', matchId: 0, yourSeat: -1, bots: [], game: null, message: '' };
  assert.deepEqual(parseRoomMessage(JSON.stringify({ type: 'room', room })).room, room);
  assert.equal(parseRoomMessage(JSON.stringify({ type: 'room', room: 'not-object' })).room, null);
  assert.equal(parseRoomMessage(JSON.stringify({ type: 'room', room: null })).room, null);
});

test('parseRoomMessage 拒绝非法输入', () => {
  assert.throws(() => parseRoomMessage('not json'), SyntaxError);
  assert.throws(() => parseRoomMessage('null'), /消息格式无效/);
  assert.throws(() => parseRoomMessage('"text"'), /消息格式无效/);
  assert.throws(() => parseRoomMessage('[]'), /消息格式无效/);
  assert.throws(() => parseRoomMessage(JSON.stringify({ roomId: 'x' })), /消息格式无效/);
  assert.throws(() => parseRoomMessage(JSON.stringify({ type: 42 })), /消息格式无效/);
});
