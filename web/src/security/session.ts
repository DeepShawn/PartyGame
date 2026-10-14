import { hmac } from '@noble/hashes/hmac';
import { sha256 } from '@noble/hashes/sha256';
import { bytesToHex, hexToBytes, utf8ToBytes } from '@noble/hashes/utils';

export interface EncryptedPacket { type: 'encrypted'; sid: string; n: number; data: string }

const MAX_PLAIN_BYTES = 28_000;
const MAX_CIPHER_HEX = 56_032;

export function deriveKey(keyHex: string, roomId: string, sid: string, direction: string): Uint8Array<ArrayBuffer> {
  return new Uint8Array(hmac(sha256, hexToBytes(keyHex.padStart(64, '0')), utf8ToBytes(`partygame/1|${roomId}|${sid}|${direction}\u0001`)));
}

/** 与安卓 SessionCipher 逐字节一致的加密会话：AES-256-GCM、严格顺序号、AAD 绑定房间与方向。 */
export class SessionCipher {
  private readonly sendKey: Uint8Array<ArrayBuffer>;
  private readonly receiveKey: Uint8Array<ArrayBuffer>;
  private readonly sendDirection: string;
  private readonly receiveDirection: string;
  private sent = 0;
  private received = -1;

  private constructor(
    private readonly roomId: string,
    private readonly sid: string,
    keyHex: string,
    host: boolean,
  ) {
    this.sendDirection = host ? 'host' : 'guest';
    this.receiveDirection = host ? 'guest' : 'host';
    this.sendKey = deriveKey(keyHex, roomId, sid, this.sendDirection);
    this.receiveKey = deriveKey(keyHex, roomId, sid, this.receiveDirection);
  }

  static async create(roomId: string, sid: string, keyHex: string, host: boolean): Promise<SessionCipher> {
    if (!/^[0-9a-f]{1,64}$/.test(keyHex) || !/^[0-9a-f]{1,64}$/.test(sid) || sid.length === 0) throw new Error('会话密钥无效');
    return new SessionCipher(roomId, sid, keyHex, host);
  }

  private aad(direction: string, sequence: number): Uint8Array<ArrayBuffer> {
    return new Uint8Array(utf8ToBytes(`partygame/1|${this.roomId}|${this.sid}|${direction}|${sequence}`));
  }

  private nonce(sequence: number): Uint8Array<ArrayBuffer> {
    const nonce = new Uint8Array(12);
    const view = new DataView(nonce.buffer);
    view.setUint32(0, 0, false);
    view.setBigUint64(4, BigInt(sequence), false);
    return nonce;
  }

  async seal(text: string): Promise<EncryptedPacket> {
    const bytes = new Uint8Array(utf8ToBytes(text));
    if (bytes.length > MAX_PLAIN_BYTES) throw new Error('消息过大');
    if (this.sent >= 1_000_000_000) throw new Error('请重新连接房间');
    const sequence = this.sent++;
    const key = await crypto.subtle.importKey('raw', this.sendKey, { name: 'AES-GCM' }, false, ['encrypt']);
    const sealed = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: this.nonce(sequence), additionalData: this.aad(this.sendDirection, sequence) },
      key,
      bytes,
    );
    return { type: 'encrypted', sid: this.sid, n: sequence, data: bytesToHex(new Uint8Array(sealed)) };
  }

  async open(packet: EncryptedPacket): Promise<string> {
    if (packet.type !== 'encrypted' || packet.sid !== this.sid) throw new Error('会话无效');
    if (!Number.isInteger(packet.n) || packet.n !== this.received + 1) throw new Error('消息顺序或会话无效');
    if (typeof packet.data !== 'string' || packet.data.length % 2 !== 0 || packet.data.length < 32 || packet.data.length > MAX_CIPHER_HEX || !/^[0-9a-f]+$/.test(packet.data)) {
      throw new Error('密文格式无效');
    }
    const key = await crypto.subtle.importKey('raw', this.receiveKey, { name: 'AES-GCM' }, false, ['decrypt']);
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: this.nonce(packet.n), additionalData: this.aad(this.receiveDirection, packet.n) },
      key,
      new Uint8Array(hexToBytes(packet.data)),
    );
    this.received = packet.n;
    return new TextDecoder('utf-8', { fatal: true }).decode(plain);
  }
}
