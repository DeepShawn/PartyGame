import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import test from 'node:test';
import { loadModule } from './helpers.mjs';

// Node 18 的 `node --test` 子进程不默认暴露全局 crypto，这里补上 Web Crypto。
if (!globalThis.crypto) globalThis.crypto = webcrypto;

// srp.ts 依赖 Web Crypto；握手全程在本进程内完成，
// 等于对「口令校验 + AES-GCM 加密信道」做一次端到端自查。
const { SrpClient, createSrpHost } = await loadModule('security/srp.ts');
const { SessionCipher } = await loadModule('security/session.ts');

test('SRP 握手成功后可双向收发加密消息', async () => {
  const roomId = 'AB12CD';
  const password = '246810';
  const host = await createSrpHost(roomId, password);
  assert.equal(host.challenge.type, 'challenge');
  assert.equal(host.challenge.roomId, roomId);
  assert.match(host.challenge.salt, /^[0-9a-f]{64}$/);
  assert.match(host.challenge.B, /^[0-9a-f]{1,512}$/);

  const client = await SrpClient.create(roomId, password);
  const proof = client.answer(host.challenge);
  assert.equal(proof.type, 'proof');
  assert.match(proof.A, /^[0-9a-f]{1,512}$/);
  assert.match(proof.M1, /^[0-9a-f]{1,64}$/);

  const hostResult = await host.verify(proof, SessionCipher.create);
  assert.equal(hostResult.proof.type, 'verified');
  assert.match(hostResult.proof.M2, /^[0-9a-f]{1,64}$/);

  const guestCipher = await client.finish(hostResult.proof, SessionCipher.create);

  const sealed = await guestCipher.seal('你好，房间！');
  assert.equal(sealed.type, 'encrypted');
  assert.equal(await hostResult.cipher.open(sealed), '你好，房间！');

  const reply = await hostResult.cipher.seal('收到，开始吧');
  assert.equal(await guestCipher.open(reply), '收到，开始吧');
});

test('口令错误时宿主拒绝证明', async () => {
  const host = await createSrpHost('ROOM01', 'correct-password');
  const attacker = await SrpClient.create('ROOM01', 'wrong-password');
  const proof = attacker.answer(host.challenge);
  await assert.rejects(
    () => host.verify(proof, SessionCipher.create),
    (error) => /credentials/i.test(String(error)),
  );
});

test('同一认证会话不能重复使用', async () => {
  const host = await createSrpHost('ROOM04', 'pass');
  const client = await SrpClient.create('ROOM04', 'pass');
  const proof = client.answer(host.challenge);
  await host.verify(proof, SessionCipher.create);
  await assert.rejects(() => host.verify(proof, SessionCipher.create), /认证已过期/);
});

test('房间不匹配时客户端拒绝挑战', async () => {
  const host = await createSrpHost('ROOM02', 'pass');
  const client = await SrpClient.create('ROOM03', 'pass');
  assert.throws(() => client.answer(host.challenge), /房间不匹配/);
});
