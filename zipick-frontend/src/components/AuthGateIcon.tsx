/** 인증 안내에서만 사용하는 장식 아이콘. 브랜드 로고와는 별개다. */
export default function AuthGateIcon({ kind }: { kind: 'LOGIN' | 'LOGIN_INTEREST' | 'ONBOARDING' | 'ERROR' }) {
  return (
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {kind === 'LOGIN' && <><rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3M12 15v2" /></>}
      {kind === 'LOGIN_INTEREST' && <path d="M6 4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17l-6-4-6 4V4Z" />}
      {kind === 'ONBOARDING' && <><path d="M4 6h4m4 0h8M4 12h10m4 0h2M4 18h2m4 0h10" /><circle cx="10" cy="6" r="2" /><circle cx="16" cy="12" r="2" /><circle cx="8" cy="18" r="2" /></>}
      {kind === 'ERROR' && <><circle cx="12" cy="12" r="9" /><path d="M12 7v6m0 4h.01" /></>}
    </svg>
  );
}
