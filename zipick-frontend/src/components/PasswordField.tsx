import { useId, useState } from 'react';

interface Props {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  /** 'current-password' | 'new-password' */
  autoComplete: string;
  error?: string | null;
  describedBy?: string;
}

/** 눈 아이콘. 보임 상태에서는 사선을 덧그린다. */
function EyeIcon({ off }: { off: boolean }) {
  return (
    <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
      <path d="M1 8s2.6-4.6 7-4.6S15 8 15 8s-2.6 4.6-7 4.6S1 8 1 8Z" />
      <circle cx="8" cy="8" r="2.1" />
      {off && <path d="M3 13 13 3" />}
    </svg>
  );
}

/** 비밀번호 입력 + 표시/숨기기. 가입 규칙이 영문·숫자 혼합이라 오타가 잦아 토글을 둔다. */
export default function PasswordField({ label, value, onChange, placeholder, autoComplete, error, describedBy }: Props) {
  const [visible, setVisible] = useState(false);
  const id = useId();
  const errorId = `${id}-error`;
  return (
    <div className="auth-password-field">
      <label htmlFor={id}>{label}</label>
      <span className="auth-password">
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          autoComplete={autoComplete}
          aria-invalid={!!error}
          aria-describedby={[describedBy, error ? errorId : undefined].filter(Boolean).join(' ') || undefined}
          required
        />
        <button
          type="button"
          className="auth-password__toggle"
          onClick={() => setVisible((v) => !v)}
          aria-pressed={visible}
          aria-label={visible ? '비밀번호 숨기기' : '비밀번호 보기'}
          title={visible ? '비밀번호 숨기기' : '비밀번호 보기'}
        >
          <EyeIcon off={visible} />
        </button>
      </span>
      {error && <small id={errorId} className="auth-error">{error}</small>}
    </div>
  );
}
