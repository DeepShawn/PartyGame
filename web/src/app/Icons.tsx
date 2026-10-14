import { useState, type ReactNode } from 'react';

/** 内联线性图标：与主题同色，无 emoji、无外部字体或图片依赖。 */
function Stroke({ children, size = 22 }: { children: ReactNode; size?: number }) {
  return (
    <svg
      className="nav-icon"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

export function GamesIcon() {
  return (
    <Stroke>
      <path d="M7 8h10a4 4 0 0 1 4 4v.5a3.5 3.5 0 0 1-6.3 2.2l-.7-.9h-2l-.7.9A3.5 3.5 0 0 1 3 12.5V12a4 4 0 0 1 4-4Z" />
      <path d="M8.5 11v2M7.5 12h2" />
      <circle cx="15.4" cy="11.4" r="1" />
      <circle cx="17.4" cy="13.4" r="1" />
    </Stroke>
  );
}

export function NetIcon() {
  return (
    <Stroke>
      <path d="M5 12.6a9.4 9.4 0 0 1 14 0" />
      <path d="M8 15.6a5.6 5.6 0 0 1 8 0" />
      <circle cx="12" cy="18.6" r="1.15" fill="currentColor" stroke="none" />
    </Stroke>
  );
}

export function PaletteIcon() {
  return (
    <Stroke>
      <path d="M12 3.6a8.4 8.4 0 0 0 0 16.8c1.3 0 1.9-.9 1.9-1.7 0-.6-.3-1-.7-1.4-.3-.4-.6-.8-.6-1.3 0-.9.8-1.6 1.7-1.6h1.4a4.7 4.7 0 0 0 4.7-4.7c0-3.4-3.5-6.1-8.4-6.1Z" />
      <circle cx="7.9" cy="11.2" r="1" fill="currentColor" stroke="none" />
      <circle cx="10.8" cy="8" r="1" fill="currentColor" stroke="none" />
      <circle cx="14.9" cy="8.7" r="1" fill="currentColor" stroke="none" />
    </Stroke>
  );
}

export function SettingsIcon() {
  return (
    <Stroke>
      <path d="M4 7.2h9M18.6 7.2H20M4 16.8h1.4M10.6 16.8H20" />
      <circle cx="15.8" cy="7.2" r="2.1" />
      <circle cx="7.8" cy="16.8" r="2.1" />
    </Stroke>
  );
}

export function CopyIcon() {
  return (
    <Stroke size={18}>
      <rect x="9" y="9" width="10" height="10" rx="2.4" />
      <path d="M5.5 15V6.4A1.4 1.4 0 0 1 6.9 5H15" />
    </Stroke>
  );
}

export function BackIcon() {
  return (
    <Stroke size={18}>
      <path d="M14.5 5.5 8 12l6.5 6.5" />
    </Stroke>
  );
}

export function InfoIcon() {
  return (
    <Stroke size={18}>
      <circle cx="12" cy="12" r="8.4" />
      <path d="M12 11v5.2" />
      <circle cx="12" cy="8.2" r="1" fill="currentColor" stroke="none" />
    </Stroke>
  );
}

export function Logo({ size = 30 }: { size?: number }) {
  return (
    <svg className="logo-mark" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="8.2" r="4.1" fill="var(--accent)" opacity="0.92" />
      <circle cx="7.4" cy="15.2" r="4.1" fill="var(--board-dark)" opacity="0.88" />
      <circle cx="16.6" cy="15.2" r="4.1" fill="var(--danger)" opacity="0.82" />
    </svg>
  );
}

/** 游戏图标：优先 icons/<id>.svg，加载失败时用首字兜底（CSS 绘制）。 */
export function GameIcon({ id, title }: { id: string; title: string }) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <span className="game-icon game-icon-fallback" aria-hidden="true">
        {title.slice(0, 1)}
      </span>
    );
  }
  return (
    <img
      className="game-icon"
      src={`icons/${id}.svg`}
      alt=""
      width={44}
      height={44}
      loading="lazy"
      draggable={false}
      onError={() => setFailed(true)}
    />
  );
}
