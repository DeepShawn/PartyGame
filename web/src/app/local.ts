/** 本地小工具：房间码/口令生成与昵称持久化。 */

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const NAME_KEY = 'partygame.name.v1';

export function randomRoomCode(length = 6): string {
  const bytes = new Uint8Array(length);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') crypto.getRandomValues(bytes);
  else for (let index = 0; index < length; index += 1) bytes[index] = Math.floor(Math.random() * 256);
  let code = '';
  for (let index = 0; index < length; index += 1) code += CODE_ALPHABET[bytes[index] % CODE_ALPHABET.length];
  return code;
}

export function randomPassword(length = 6): string {
  const digits = new Uint8Array(length);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') crypto.getRandomValues(digits);
  else for (let index = 0; index < length; index += 1) digits[index] = Math.floor(Math.random() * 256);
  return Array.from(digits, (value) => String(value % 10)).join('');
}

export function normalizeCode(value: string): string {
  return value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
}

export function loadName(): string {
  try {
    return localStorage.getItem(NAME_KEY) ?? '';
  } catch {
    return '';
  }
}

export function saveName(value: string): void {
  try {
    localStorage.setItem(NAME_KEY, value);
  } catch {
    /* 隐私模式下忽略 */
  }
}
