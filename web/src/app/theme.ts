/** 主题调色板：内置与安卓端 UiPalette 完全一致的兜底色，允许 design/tokens.json 覆盖。 */

export type ThemeId = 'sea' | 'midnight' | 'cherry' | 'wood';

export interface Palette {
  background: string;
  surface: string;
  text: string;
  muted: string;
  accent: string;
  onAccent: string;
  soft: string;
  border: string;
  boardLight: string;
  boardDark: string;
  danger: string;
  dark: boolean;
}

export const THEMES: { id: ThemeId; label: string; description: string }[] = [
  { id: 'sea', label: '海盐青', description: '清爽的浅色主题，默认配色' },
  { id: 'midnight', label: '午夜蓝', description: '低亮度深色主题，适合夜间' },
  { id: 'cherry', label: '樱桃粉', description: '柔和的粉色调，轻快明亮' },
  { id: 'wood', label: '暖木色', description: '木质棋盘质感，安静沉稳' },
];

export const FALLBACK_PALETTES: Record<ThemeId, Palette> = {
  sea: {
    background: '#F4F8F7', surface: '#FFFFFF', text: '#183634', muted: '#57736F',
    accent: '#117D78', onAccent: '#FFFFFF', soft: '#E0F2ED', border: '#D7E6E2',
    boardLight: '#E7D8B8', boardDark: '#77988B', danger: '#B73545', dark: false,
  },
  midnight: {
    background: '#121D2A', surface: '#1C2A3A', text: '#F0F5FA', muted: '#B4C3D1',
    accent: '#80D8C4', onAccent: '#0C2926', soft: '#273D49', border: '#3A4A5B',
    boardLight: '#A5BAC2', boardDark: '#526B79', danger: '#FFADAD', dark: true,
  },
  cherry: {
    background: '#FFF5F6', surface: '#FFFFFF', text: '#422A34', muted: '#7C5969',
    accent: '#A9325D', onAccent: '#FFFFFF', soft: '#FBE5EC', border: '#EACDD7',
    boardLight: '#F8DFE3', boardDark: '#BC8093', danger: '#AD2838', dark: false,
  },
  wood: {
    background: '#F6F0E6', surface: '#FFFBF4', text: '#3B3026', muted: '#75634F',
    accent: '#75603B', onAccent: '#FFFFFF', soft: '#EDE2D0', border: '#DCCDAD',
    boardLight: '#EDD2A1', boardDark: '#AC8053', danger: '#AA382E', dark: false,
  },
};

const COLOR_KEYS = ['background', 'surface', 'text', 'muted', 'accent', 'onAccent', 'soft', 'border', 'boardLight', 'boardDark', 'danger'] as const;
type ColorKey = (typeof COLOR_KEYS)[number];

const HEX = /^#[0-9a-fA-F]{3,8}$/;

function pickColor(source: Record<string, unknown>, key: ColorKey): string | null {
  const variants = [key, key.toLowerCase(), key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`), key.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`)];
  for (const variant of variants) {
    const value = source[variant];
    if (typeof value === 'string' && HEX.test(value.trim())) return value.trim();
  }
  return null;
}

/** 解析 design/tokens.json；结构兼容 { themes: { sea: { colors } } } 与 { themes: [{ id, colors }] }。 */
export function mergeTokens(json: unknown, base: Record<ThemeId, Palette>): { palettes: Record<ThemeId, Palette>; changed: boolean } {
  const palettes: Record<ThemeId, Palette> = { ...base };
  if (typeof json !== 'object' || json === null) return { palettes, changed: false };
  const themesValue = (json as { themes?: unknown }).themes;
  let changed = false;
  for (const theme of THEMES) {
    let entry: unknown = null;
    if (Array.isArray(themesValue)) {
      entry = themesValue.find((item) => typeof item === 'object' && item !== null
        && [ (item as Record<string, unknown>).id, (item as Record<string, unknown>).name, (item as Record<string, unknown>).key ]
          .some((value) => value === theme.id));
    } else if (themesValue && typeof themesValue === 'object') {
      entry = (themesValue as Record<string, unknown>)[theme.id];
    }
    if (!entry || typeof entry !== 'object') continue;
    const record = entry as Record<string, unknown>;
    const colorsValue = record.colors;
    const colors = (colorsValue && typeof colorsValue === 'object' ? colorsValue : record) as Record<string, unknown>;
    const next = { ...palettes[theme.id] };
    for (const key of COLOR_KEYS) {
      const value = pickColor(colors, key);
      if (value) { next[key] = value; changed = true; }
    }
    if (typeof record.dark === 'boolean') { next.dark = record.dark; changed = true; }
    palettes[theme.id] = next;
  }
  return { palettes, changed };
}

const TOKENS_URLS = ['tokens.json', './tokens.json'];

/** 运行时读取构建时复制的 tokens.json；缺失或损坏时保留内置兜底，不抛错。 */
export async function loadTokens(base: Record<ThemeId, Palette>): Promise<{ palettes: Record<ThemeId, Palette>; changed: boolean }> {
  if (typeof fetch !== 'function') return { palettes: base, changed: false };
  for (const url of TOKENS_URLS) {
    try {
      const response = await fetch(url, { cache: 'no-cache' });
      if (!response.ok) continue;
      const json: unknown = await response.json();
      const merged = mergeTokens(json, base);
      if (merged.changed) return merged;
    } catch {
      /* 尝试下一个地址 */
    }
  }
  return { palettes: base, changed: false };
}

export function applyTheme(palette: Palette, fontScale: number, reducedMotion: boolean): void {
  const root = document.documentElement;
  const style = root.style;
  style.setProperty('--bg', palette.background);
  style.setProperty('--surface', palette.surface);
  style.setProperty('--text', palette.text);
  style.setProperty('--muted', palette.muted);
  style.setProperty('--accent', palette.accent);
  style.setProperty('--on-accent', palette.onAccent);
  style.setProperty('--soft', palette.soft);
  style.setProperty('--border', palette.border);
  style.setProperty('--board-light', palette.boardLight);
  style.setProperty('--board-dark', palette.boardDark);
  style.setProperty('--danger', palette.danger);
  style.setProperty('--font-scale', String(fontScale));
  root.dataset.motion = reducedMotion ? 'reduced' : 'full';
  root.dataset.scheme = palette.dark ? 'dark' : 'light';
  const themeColor = document.querySelector('meta[name="theme-color"]');
  if (themeColor) themeColor.setAttribute('content', palette.background);
}

export function paletteFor(palettes: Record<ThemeId, Palette>, theme: string): Palette {
  return palettes[(theme as ThemeId)] ?? palettes.sea;
}

/** 画布颜色：把 CSS 变量读成十六进制，供 canvas2D 使用。 */
export function readPalette(): Palette {
  const style = getComputedStyle(document.documentElement);
  const read = (name: string, fallback: string) => {
    const value = style.getPropertyValue(name).trim();
    return HEX.test(value) ? value : fallback;
  };
  return {
    background: read('--bg', FALLBACK_PALETTES.sea.background),
    surface: read('--surface', FALLBACK_PALETTES.sea.surface),
    text: read('--text', FALLBACK_PALETTES.sea.text),
    muted: read('--muted', FALLBACK_PALETTES.sea.muted),
    accent: read('--accent', FALLBACK_PALETTES.sea.accent),
    onAccent: read('--on-accent', FALLBACK_PALETTES.sea.onAccent),
    soft: read('--soft', FALLBACK_PALETTES.sea.soft),
    border: read('--border', FALLBACK_PALETTES.sea.border),
    boardLight: read('--board-light', FALLBACK_PALETTES.sea.boardLight),
    boardDark: read('--board-dark', FALLBACK_PALETTES.sea.boardDark),
    danger: read('--danger', FALLBACK_PALETTES.sea.danger),
    dark: document.documentElement.dataset.scheme === 'dark',
  };
}
