/** 外观与设置页：全部改动即时生效并写入 localStorage。 */
import { FONT_SCALES, useSettings } from './settings';
import { THEMES, type ThemeId } from './theme';
import { Card, SectionTitle, Segmented, Toggle } from './Ui';
import { AuthView } from './AuthView';

export function AppearanceView() {
  const { settings, update, palettes } = useSettings();
  return (
    <div className="page">
      <SectionTitle title="外观" subtitle="主题、字号与动效都会立即生效，并保存在本机。" />
      <Card>
        <h3 className="card-title">主题</h3>
        <div className="theme-grid">
          {THEMES.map((theme) => {
            const palette = palettes[theme.id as ThemeId];
            const active = settings.theme === theme.id;
            return (
              <button
                key={theme.id}
                type="button"
                className={`theme-card${active ? ' theme-active' : ''}`}
                aria-pressed={active}
                onClick={() => update({ theme: theme.id })}
              >
                <span className="theme-swatches" aria-hidden="true">
                  <i style={{ background: palette.background }} />
                  <i style={{ background: palette.accent }} />
                  <i style={{ background: palette.soft }} />
                  <i style={{ background: palette.boardDark }} />
                </span>
                <strong>{theme.label}</strong>
                <span className="theme-detail">{theme.description}</span>
              </button>
            );
          })}
        </div>
      </Card>
      <Card>
        <h3 className="card-title">字号</h3>
        <Segmented
          value={settings.fontScale}
          options={FONT_SCALES.map((scale) => ({ value: scale.value, label: `${scale.label} ${Math.round(scale.value * 100)}%` }))}
          onChange={(value) => update({ fontScale: value })}
        />
        <p className="card-hint">棋盘字号会同步缩放，棋盘画布按屏幕重新排版。</p>
      </Card>
      <Card>
        <Toggle
          label="减少动效"
          detail="关闭脉冲、过渡与位移动画，适合低性能设备"
          checked={settings.reducedMotion}
          onChange={(value) => update({ reducedMotion: value })}
        />
      </Card>
    </div>
  );
}

export function SettingsView() {
  const { settings, update } = useSettings();
  return (
    <div className="page">
      <SectionTitle title="设置" subtitle="对局相关的偏好设置，安卓端与网页端保持同样的默认值。" />
      <Card>
        <Toggle
          label="按键音效"
          detail="轻量合成提示音，不播放背景音乐"
          checked={settings.sound}
          onChange={(value) => update({ sound: value })}
        />
        <Toggle
          label="棋盘坐标"
          detail="在棋盘边缘显示行列标号，便于口述走法"
          checked={settings.coordinates}
          onChange={(value) => update({ coordinates: value })}
        />
        <Toggle
          label="落子前确认"
          detail="先选好目标点高亮，再按确认落子"
          checked={settings.confirmMoves}
          onChange={(value) => update({ confirmMoves: value })}
        />
      </Card>
      <AuthView />
      <Card>
        <h3 className="card-title">离线 AI 难度</h3>
        <Segmented
          value={settings.aiDifficulty}
          options={[
            { value: 0, label: '轻松', hint: '思考更快，失误更多' },
            { value: 1, label: '标准' },
            { value: 2, label: '挑战', hint: '思考更久，更少失误' },
          ]}
          onChange={(value) => update({ aiDifficulty: value })}
        />
        <p className="card-hint">联机房间的难度由房主在房间内选择；本机同屏与离线对局使用这里的设置。</p>
      </Card>
      <Card>
        <h3 className="card-title">关于</h3>
        <p className="card-hint">
          同乐盒 PartyGame 网页版：与安卓端共享同一套规则内核，房间口令通过 SRP 校验后建立加密信道；
          公网房间经 WebRTC 直连，局域网房间由安卓房主提供页面并走同源 WebSocket。
        </p>
      </Card>
    </div>
  );
}
