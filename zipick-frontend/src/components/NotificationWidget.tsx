import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationApi } from '../api/notification';
import { useAuth } from '../hooks/useAuth';
import { openGate } from '../hooks/useAuthGate';
import type { NotificationEvent } from '../types';
import '../design-system/tokens.css';
import styles from './NotificationWidget.module.css';

const TYPE_LABEL: Record<string, string> = {
  NOTICE_DEADLINE_REMINDER: '접수 마감', NOTICE_NEW_MATCH: '새 공고', MAIN_TEST: '테스트',
  AUTH_EMAIL_VERIFY: '이메일 인증', AUTH_PASSWORD_CHANGED: '비밀번호 변경', MARKETING_NEWSLETTER: '소식',
};

function BellIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </svg>
  );
}

/**
 * 오른쪽 아래에 늘 떠 있는 알림 버튼. 어느 화면에서든 알림을 켜고 끌 수 있다.
 * - 비회원: 누르면 로그인 안내
 * - 회원: 켜기/끄기 스위치, 받는 곳(이메일) 요약, 최근 알림 3건, 자세한 설정(/notifications) 링크
 * 운영자 화면(/admin)에는 띄우지 않는다. 설정값은 메인 서버가, 보내는 일은 알림센터가 맡는다.
 */
export default function NotificationWidget() {
  const { user } = useAuth();
  const location = useLocation();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  const setting = useQuery({ queryKey: ['notification-setting'], queryFn: notificationApi.setting, enabled: !!user, staleTime: 30_000 });
  const history = useQuery({ queryKey: ['notification-history'], queryFn: notificationApi.history, enabled: !!user && open, retry: false });

  const toggle = useMutation({
    mutationFn: (enabled: boolean) =>
      notificationApi.save({
        enabled,
        // 주소·광고 동의·마감 일수는 그대로 둔다(null = 유지)
        slackWebhookUrl: null,
        email: null,
        marketingConsent: null,
        deadlineDays: null,
        newMatchEnabled: setting.data?.newMatchEnabled ?? true,
      }),
    onSuccess: (data) => qc.setQueryData(['notification-setting'], data),
  });

  // 화면을 옮기면 닫는다. 바깥을 누르거나 Esc 를 눌러도 닫는다
  useEffect(() => { setOpen(false); }, [location.pathname]);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => { if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  if (location.pathname.startsWith('/admin')) return null;

  const s = setting.data;
  const on = !!s?.enabled;
  const hasChannel = !!s && (!!s.email || s.slackConfigured);

  return (
    <div ref={rootRef} className={`zipick-theme ${styles.root}`}>
      {open && user && (
        <section className={styles.card} aria-label="알림">
          <header className={styles.head}>
            <strong>알림</strong>
            <label className={styles.switch}>
              <input
                type="checkbox"
                role="switch"
                aria-checked={on}
                checked={on}
                disabled={!s || toggle.isPending}
                onChange={(e) => toggle.mutate(e.target.checked)}
              />
              <span className={styles.track} aria-hidden="true"><span className={styles.knob} /></span>
              <span className={styles.switchText}>{on ? '켜짐' : '꺼짐'}</span>
            </label>
          </header>

          {setting.isPending && <p className={styles.muted}>불러오는 중…</p>}
          {setting.isError && <p className={styles.error}>알림 설정을 불러오지 못했어요.</p>}
          {toggle.isError && <p className={styles.error}>{toggle.error instanceof Error ? toggle.error.message : '저장하지 못했어요.'}</p>}

          {s && (
            <p className={styles.summary}>
              {on
                ? hasChannel
                  ? <>관심 공고 마감{s.deadlineDays.length > 0 ? ` ${s.deadlineDays.map((d) => (d === 0 ? 'D-day' : `D-${d}`)).join(' · ')}` : ' 알림 꺼짐'}{s.newMatchEnabled ? ' · 새 공고' : ''}를 <b>{s.email ?? '슬랙'}</b>으로 받아요.</>
                  : <>켜져 있지만 <b>받을 이메일이 없어요.</b> 설정에서 넣어 주세요.</>
                : <>꺼져 있어요. 켜면 관심 공고 마감과 새 공고를 이메일로 알려 드려요.</>}
            </p>
          )}

          {history.data && history.data.length > 0 && (
            <ul className={styles.list}>
              {history.data.slice(0, 3).map((e: NotificationEvent) => (
                <li key={e.id}>
                  <span className={styles.type}>{TYPE_LABEL[e.templateCode] ?? e.templateCode}</span>
                  <span className={styles.title} title={e.title}>{e.title}</span>
                  <span className={styles.time}>{e.scheduledAt.slice(5, 16).replace('T', ' ')}</span>
                </li>
              ))}
            </ul>
          )}
          {history.isSuccess && history.data.length === 0 && <p className={styles.muted}>아직 받은 알림이 없어요.</p>}

          <Link to="/notifications" className={styles.more} onClick={() => setOpen(false)}>알림 설정 자세히 →</Link>
        </section>
      )}

      <button
        type="button"
        className={`${styles.fab} ${on ? styles.fabOn : ''}`}
        aria-label={user ? (open ? '알림 닫기' : `알림 ${on ? '켜짐' : '꺼짐'} — 열기`) : '알림 — 로그인 필요'}
        aria-expanded={user ? open : undefined}
        onClick={() => { if (!user) return openGate('LOGIN'); setOpen((v) => !v); }}
      >
        <BellIcon />
        {user && s && <span className={`${styles.dot} ${on ? styles.dotOn : ''}`} aria-hidden="true" />}
      </button>
    </div>
  );
}
