import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { FALLBACK_PALETTES, applyTheme, loadTokens, paletteFor, type Palette, type ThemeId } from './theme';

export interface Settings {
  theme: ThemeId;
  fontScale: number;
  reducedMotion: boolean;
  sound: boolean;
  coordinates: boolean;
  confirmMoves: boolean;
  aiDifficulty: number;
}

export const DEFAULT_SETTINGS: Settings = {
  theme: 'sea',
  fontScale: 1,
  reducedMotion: false,
  sound: true,
  coordinates: true,
  confirmMoves: true,
  aiDifficulty: 1,
};

export const FONT_SCALES = [
  { value: 1, label: '标准' },
  { value: 1.15, label: '舒适' },
  { value: 1.3, label: '大字' },
];

const STORAGE_KEY = 'partygame.settings.v1';

function clampFontScale(value: unknown): number {
  const number = typeof value === 'number' && Number.isFinite(value) ? value : 1;
  return Math.min(1.4, Math.max(0.85, number));
}

export function loadSettings(): Settings {
  if (typeof localStorage === 'undefined') return DEFAULT_SETTINGS;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULT_SETTINGS;
    const value = JSON.parse(raw) as Partial<Settings>;
    return {
      theme: value.theme === 'midnight' || value.theme === 'cherry' || value.theme === 'wood' ? value.theme : 'sea',
      fontScale: clampFontScale(value.fontScale),
      reducedMotion: value.reducedMotion === true,
      sound: value.sound !== false,
      coordinates: value.coordinates !== false,
      confirmMoves: value.confirmMoves !== false,
      aiDifficulty: value.aiDifficulty === 0 || value.aiDifficulty === 2 ? value.aiDifficulty : 1,
    };
  } catch {
    return DEFAULT_SETTINGS;
  }
}

function saveSettings(settings: Settings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch {
    /* 隐私模式下忽略 */
  }
}

interface SettingsContextValue {
  settings: Settings;
  palette: Palette;
  palettes: Record<ThemeId, Palette>;
  update: (patch: Partial<Settings>) => void;
}

const SettingsContext = createContext<SettingsContextValue | null>(null);

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<Settings>(loadSettings);
  const [palettes, setPalettes] = useState<Record<ThemeId, Palette>>(FALLBACK_PALETTES);

  const palette = useMemo(() => paletteFor(palettes, settings.theme), [palettes, settings.theme]);

  useEffect(() => {
    let alive = true;
    void loadTokens(FALLBACK_PALETTES).then((result) => {
      if (alive && result.changed) setPalettes(result.palettes);
    });
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    applyTheme(palette, settings.fontScale, settings.reducedMotion);
  }, [palette, settings.fontScale, settings.reducedMotion]);

  const update = useCallback((patch: Partial<Settings>) => {
    setSettings((current) => {
      const next: Settings = { ...current, ...patch, fontScale: clampFontScale(patch.fontScale ?? current.fontScale) };
      saveSettings(next);
      return next;
    });
  }, []);

  const value = useMemo(() => ({ settings, palette, palettes, update }), [settings, palette, palettes, update]);
  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
}

export function useSettings(): SettingsContextValue {
  const value = useContext(SettingsContext);
  if (!value) throw new Error('useSettings 必须在 SettingsProvider 内使用');
  return value;
}
