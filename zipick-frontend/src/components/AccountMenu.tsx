import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { AuthUser } from '../hooks/useAuth';
import styles from './AccountMenu.module.css';

type IconName = 'user' | 'clipboard' | 'bell' | 'shield' | 'logout' | 'chevron' | 'right';

function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  const paths: Record<IconName, ReactNode> = {
    user: <><circle cx="12" cy="8" r="4" /><path d="M4.5 20a7.5 7.5 0 0 1 15 0" /></>,
    clipboard: <><rect x="5" y="5" width="14" height="16" rx="2" /><path d="M9 5.5V3.8c0-.5.4-.8.9-.8h4.2c.5 0 .9.3.9.8v1.7M9 12h6M9 16h4" /></>,
    bell: <path d="M18 9a6 6 0 0 0-12 0c0 7-3 8-3 9h18c0-1-3-2-3-9ZM10 21h4" />,
    shield: <><path d="M12 2 20 5v6c0 5-3.4 8.2-8 11-4.6-2.8-8-6-8-11V5z" /><path d="m9 11 2 2 4-4" /></>,
    logout: <path d="M9 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4M14 16l4-4-4-4M8 12h10" />,
    chevron: <path d="m6 9 6 6 6-6" />,
    right: <path d="m9 6 6 6-6 6" />,
  };

  return <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

// TODO(account-management):
// 닉네임 변경, 비밀번호 변경/재설정, 회원 탈퇴 기능은 현재 구현 범위에서 제외.
// 향후 실제 기능 도입 여부가 확정되면 이 메뉴를 연결한다.
// 해당 기능들을 최종적으로 제공하지 않기로 결정하면
// "내 정보 관리" 준비 중 항목을 프로필 메뉴에서 제거한다.
const upcomingItems = [
  { icon: 'user', title: '내 정보 관리', description: '계정 정보 관리 기능은 추후 제공' },
  { icon: 'bell', title: '알림 설정', description: '계정 메뉴 연동은 추후 제공' },
  { icon: 'shield', title: '약관·개인정보 동의 관리', description: '동의 내역 관리 기능은 추후 제공' },
] as const;

/** 일반 링크와 버튼을 쓰는 disclosure. Tab으로 이동하고 영역을 벗어나면 닫는다. */
export default function AccountMenu({ user, onLogout }: { user: AuthUser; onLogout: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Node && !containerRef.current?.contains(event.target)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <div ref={containerRef} className={styles.container} onBlur={(event) => {
      if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
    }}>
      <button
        ref={triggerRef}
        type="button"
        className={styles.trigger}
        aria-label={`내 계정 메뉴 ${open ? '닫기' : '열기'}`}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((previous) => !previous)}
      >
        <span className={styles.triggerAvatar}><Icon name="user" size={18} /></span>
        <span>내 계정</span>
        <span className={styles.chevron}><Icon name="chevron" size={16} /></span>
      </button>
      <section id={panelId} className={styles.panel} aria-label="내 계정 메뉴" hidden={!open}>
        <div className={styles.summary}>
          <span className={styles.avatar}><Icon name="user" size={22} /></span>
          <div className={styles.identity}>
            {/*
              TODO(kakao-login):
              카카오 로그인 도입 후 실제 인증 provider를 기준으로 가입 유형을 표시한다.
              LOCAL → "일반 가입자", KAKAO → "카카오 가입자".

              이메일 표시 정책:
              1. 이메일이 제공되면 로그인 방식과 무관하게 실제 이메일 표시.
              2. KAKAO 사용자에게 이메일이 없으면 "카카오 계정으로 로그인" 표시.
              3. provider/email 정보를 임의로 추론하거나 가짜 이메일을 생성하지 않는다.
            */}
            <div className={styles.name}>일반 가입자</div>
            {user.email && <div className={styles.email}>{user.email}</div>}
          </div>
        </div>
        <div className={styles.divider} />
        <div className={styles.label}>나의 집 찾기</div>
        <Link to="/onboarding" className={`${styles.row} ${styles.featured}`} onClick={() => setOpen(false)}>
          <span className={styles.symbol}><Icon name="clipboard" /></span>
          <span className={styles.content}>
            <span className={styles.title}>내 조건 확인·수정</span>
            <span className={styles.description}>기본 정보 · 희망 주거 조건 · 우선순위</span>
          </span>
          <span className={styles.tail}><Icon name="right" size={16} /></span>
        </Link>
        <div className={styles.label}>내 계정 관리 <span className={styles.note}>추후 연동 항목</span></div>
        <ul className={styles.upcoming} aria-label="준비 중인 계정 관리 메뉴">
          {upcomingItems.map((item) => (
            <li key={item.icon} className={styles.row} aria-disabled="true">
              <span className={styles.symbol}><Icon name={item.icon} size={19} /></span>
              <span className={styles.content}>
                <span className={styles.title}>{item.title}</span>
                <span className={styles.description}>{item.description}</span>
              </span>
              <span className={styles.status}>준비 중</span>
            </li>
          ))}
        </ul>
        <div className={styles.divider} />
        <button type="button" className={`${styles.row} ${styles.logout}`} onClick={() => {
          setOpen(false);
          triggerRef.current?.focus();
          void onLogout();
        }}>
          <span className={styles.symbol}><Icon name="logout" size={19} /></span>
          <span className={styles.title}>로그아웃</span>
        </button>
      </section>
    </div>
  );
}
