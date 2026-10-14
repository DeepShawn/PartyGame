/** 把底层（SRP/WebRTC/WebSocket）错误统一映射为中文提示。 */

const PASSWORD_HINTS = /bad client credentials|invalid.*proof|proof.*invalid|verify|认证失败|认证数据/i;

export function friendlyError(reason: string | null | undefined): string {
  const text = (reason ?? '').trim();
  if (!text) return '连接已关闭';
  if (PASSWORD_HINTS.test(text)) return '口令错误，请核对后重试';
  if (text.includes('认证超时')) return '对方没有响应，请稍后重试或重新加入';
  if (text.includes('尚未完成房间认证')) return '房间认证未完成，请重新加入';
  if (text === '连接已关闭') return '连接已关闭：可能是口令错误，或房主已离开房间';
  if (text.includes('room is full') || text.includes('Connection limit')) return '房间已满，请稍后再试';
  return text;
}

/** 连接被对端拒绝时的中文提示（房间状态提示已由会话层给出时的兜底）。 */
export function rejectionMessage(reason: string): string {
  if (reason.includes('房间已满') || reason.includes('对局已开始')) return reason;
  return friendlyError(reason);
}
