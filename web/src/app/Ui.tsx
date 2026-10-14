import { useEffect, useId, useRef, type ReactNode } from 'react';
import { playTap } from './sound';
import { useSettings } from './settings';

interface ButtonProps {
  children: ReactNode;
  onClick?: () => void;
  variant?: 'primary' | 'ghost' | 'danger';
  disabled?: boolean;
  compact?: boolean;
  block?: boolean;
  title?: string;
  type?: 'button' | 'submit';
}

export function Button({ children, onClick, variant = 'ghost', disabled, compact, block, title, type = 'button' }: ButtonProps) {
  const { settings } = useSettings();
  return (
    <button
      type={type}
      className={`btn btn-${variant}${compact ? ' btn-compact' : ''}${block ? ' btn-block' : ''}`}
      title={title}
      disabled={disabled}
      onClick={() => {
        playTap(settings.sound);
        onClick?.();
      }}
    >
      {children}
    </button>
  );
}

export function Card({ children, className = '', padded = true }: { children: ReactNode; className?: string; padded?: boolean }) {
  return <div className={`card${padded ? ' card-padded' : ''} ${className}`.trim()}>{children}</div>;
}

export function SectionTitle({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <header className="section-title">
      <h2>{title}</h2>
      {subtitle ? <p>{subtitle}</p> : null}
    </header>
  );
}

export function Badge({ children, tone = 'soft' }: { children: ReactNode; tone?: 'soft' | 'accent' | 'danger' | 'muted' }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function TextField({
  label, value, onChange, placeholder, maxLength, password, uppercase, inputMode, autoFocus,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  maxLength?: number;
  password?: boolean;
  uppercase?: boolean;
  inputMode?: 'text' | 'numeric';
  autoFocus?: boolean;
}) {
  const id = useId();
  return (
    <label className="field" htmlFor={id}>
      <span className="field-label">{label}</span>
      <input
        id={id}
        className={`field-input${uppercase ? ' field-upper' : ''}`}
        type={password ? 'password' : 'text'}
        value={value}
        placeholder={placeholder}
        maxLength={maxLength}
        inputMode={inputMode}
        autoFocus={autoFocus}
        autoComplete="off"
        autoCorrect="off"
        spellCheck={false}
        onChange={(event) => onChange(uppercase ? event.target.value.toUpperCase() : event.target.value)}
      />
    </label>
  );
}

export function Segmented<T extends string | number>({
  label, value, options, onChange,
}: {
  label?: string;
  value: T;
  options: { value: T; label: string; hint?: string }[];
  onChange: (value: T) => void;
}) {
  return (
    <div className="segmented">
      {label ? <span className="field-label">{label}</span> : null}
      <div className="segmented-row" role="radiogroup" aria-label={label}>
        {options.map((option) => (
          <button
            key={String(option.value)}
            type="button"
            role="radio"
            aria-checked={option.value === value}
            className={`segment${option.value === value ? ' segment-active' : ''}`}
            title={option.hint}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}

export function Toggle({ label, detail, checked, onChange }: { label: string; detail: string; checked: boolean; onChange: (value: boolean) => void }) {
  const { settings } = useSettings();
  return (
    <div className="toggle-row">
      <div className="toggle-copy">
        <strong>{label}</strong>
        <span>{detail}</span>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        className={`switch${checked ? ' switch-on' : ''}`}
        onClick={() => {
          playTap(settings.sound);
          onChange(!checked);
        }}
      >
        <span className="switch-knob" />
      </button>
    </div>
  );
}

export function Modal({
  title, subtitle, children, onClose, wide,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    ref.current?.focus();
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="overlay" role="presentation" onClick={onClose}>
      <div
        className={`modal${wide ? ' modal-wide' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        ref={ref}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="modal-head">
          <div>
            <h3>{title}</h3>
            {subtitle ? <p>{subtitle}</p> : null}
          </div>
          <button type="button" className="icon-btn" aria-label="关闭" onClick={onClose}>×</button>
        </header>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}

export function StatusNote({ children, tone = 'info' }: { children: ReactNode; tone?: 'info' | 'error' | 'success' }) {
  return <p className={`status-note status-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>{children}</p>;
}

export function seatName(seat: number): string {
  return `${seat + 1} 号`;
}
