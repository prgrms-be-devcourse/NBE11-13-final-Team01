import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationApi } from '../api/notification';
import { isAdmin, useAuth } from '../hooks/useAuth';
import type { NotificationEvent, NotificationStatus } from '../types';
import '../design-system/tokens.css';
import styles from './NotificationSettingsPage.module.css';

const TYPE_LABEL: Record<string, string> = {
  NOTICE_DEADLINE_REMINDER: '접수 마감', NOTICE_NEW_MATCH: '새 공고', MAIN_TEST: '테스트',
  AUTH_EMAIL_VERIFY: '이메일 인증', AUTH_PASSWORD_CHANGED: '비밀번호 변경', MARKETING_NEWSLETTER: '소식',
};
const STATUS_LABEL: Record<NotificationStatus, string> = { PENDING: '대기', SENT: '보냄', PARTIAL: '일부 실패', FAILED: '실패', CANCELLED: '취소' };
const DEADLINE_OPTIONS = [3, 1, 0] as const;

const deliveryText = (e: NotificationEvent) =>
  e.deliveries.map((d) => `${d.channel} ${d.status === 'SENT' ? '보냄' : d.status === 'FAILED' ? '실패' : '대기'}${d.lastError ? `(${d.lastError})` : ''}`).join(' · ');
const errText = (e: unknown) => (e instanceof Error ? e.message : '요청에 실패했어요.');

function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: React.ReactNode; disabled?: boolean }) {
  return (
    <label className={styles.switch}>
      <input type="checkbox" role="switch" aria-checked={checked} checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className={styles.track} aria-hidden="true"><span className={styles.knob} /></span>
      <span className={styles.switchLabel}>{label}</span>
    </label>
  );
}

/**
 * 알림 설정. 설정은 메인 서버가 갖고, 보내는 일만 알림센터(별도 서버)가 한다.
 * 일반 사용자는 이메일로만 받는다. 슬랙 웹훅은 운영자 계정에만 보이고, 서버도 운영자가 아니면 거부한다.
 */
export default function NotificationSettingsPage() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const setting = useQuery({ queryKey: ['notification-setting'], queryFn: notificationApi.setting });
  const history = useQuery({ queryKey: ['notification-history'], queryFn: notificationApi.history, retry: false });

  const [enabled, setEnabled] = useState(false);
  const [email, setEmail] = useState('');
  const [days, setDays] = useState<number[]>([3, 1, 0]);
  const [newMatch, setNewMatch] = useState(true);
  const [marketing, setMarketing] = useState(false);
  const [webhook, setWebhook] = useState('');
  const [keepWebhook, setKeepWebhook] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    const s = setting.data;
    if (!s) return;
    setEnabled(s.enabled);
    setEmail(s.email ?? '');
    setDays(s.deadlineDays);
    setNewMatch(s.newMatchEnabled);
    setMarketing(s.marketingConsent);
    setKeepWebhook(s.slackConfigured);
    setWebhook('');
    setDirty(false);
  }, [setting.data]);

  const s = setting.data;
  const slackAllowed = !!s?.slackAllowed || isAdmin(user);

  const save = useMutation({
    mutationFn: () =>
      notificationApi.save({
        enabled,
        // 운영자: 입력란이 비어 있고 "기존 웹훅 유지"면 그대로(null), 아니면 삭제(""). 일반 사용자는 보내지 않는다(null)
        slackWebhookUrl: slackAllowed ? (webhook.trim() ? webhook.trim() : keepWebhook ? null : '') : null,
        email: email.trim(),
        marketingConsent: marketing,
        // 모두 끄면 "" → 마감 알림 끔
        deadlineDays: [...days].sort((a, b) => b - a).join(','),
        newMatchEnabled: newMatch,
      }),
    onSuccess: (data) => {
      qc.setQueryData(['notification-setting'], data);
      qc.invalidateQueries({ queryKey: ['notification-history'] });
    },
  });
  const test = useMutation({ mutationFn: notificationApi.test, onSuccess: () => qc.invalidateQueries({ queryKey: ['notification-history'] }) });

  const mark = <T,>(set: (v: T) => void) => (v: T) => { set(v); setDirty(true); };
  const toggleDay = (d: number) => mark(setDays)(days.includes(d) ? days.filter((x) => x !== d) : [...days, d]);
  const canTest = !!s && (!!s.email || (slackAllowed && s.slackConfigured));

  return (
    <div className={`zipick-theme ${styles.page}`}>
      <header className={styles.head}>
        <h1>알림 설정</h1>
        <p>관심 공고의 접수 마감이 다가오거나 내 조건에 맞는 새 공고가 들어오면 <b>이메일</b>로 알려 드려요. 서버가 5분마다 확인해요.</p>
      </header>

      {setting.isPending && <p className={styles.muted} role="status">알림 설정을 불러오는 중이에요…</p>}
      {setting.isError && (
        <div className={styles.error} role="alert">
          알림 설정을 불러오지 못했어요. <button type="button" className={styles.ghost} onClick={() => setting.refetch()}>다시 시도</button>
        </div>
      )}

      <fieldset className={styles.fields} disabled={!s}>
        <section className={styles.panel}>
          <div className={styles.rowBetween}>
            <div>
              <h2>알림 받기</h2>
              <p className={styles.sub}>{enabled ? '켜져 있어요. 아래에서 무엇을 어디로 받을지 정해요.' : '꺼져 있어요. 켜면 아래 설정대로 보내 드려요.'}</p>
            </div>
            <Switch checked={enabled} onChange={mark(setEnabled)} label={enabled ? '켜짐' : '꺼짐'} />
          </div>
        </section>

        <section className={styles.panel}>
          <h2>받는 곳</h2>
          <label className={styles.field}>
            <span>이메일 {s && !s.emailAvailable && <em className={styles.warn}>(알림센터에 이메일 발송 설정이 없어 지금은 보낼 수 없어요)</em>}</span>
            <input type="email" placeholder={user?.email ?? 'me@example.com'} value={email} onChange={(e) => mark(setEmail)(e.target.value)} autoComplete="email" />
            {!email.trim() && <small className={styles.hint}>받을 이메일이 없으면 알림이 가지 않아요.</small>}
          </label>

          {slackAllowed && (
            <div className={styles.adminBox}>
              <div className={styles.adminTag}>운영자 전용</div>
              <label className={styles.field}>
                <span>슬랙 웹훅 URL {s?.slackConfigured && <em className={styles.muted}>(저장됨 {s.slackWebhookMasked})</em>}</span>
                <input
                  type="text"
                  placeholder={s?.slackConfigured ? '바꾸려면 새 주소를 넣으세요' : 'https://hooks.slack.com/services/…'}
                  value={webhook}
                  onChange={(e) => mark(setWebhook)(e.target.value)}
                  autoComplete="off"
                />
              </label>
              {s?.slackConfigured && (
                <Switch checked={keepWebhook} onChange={mark(setKeepWebhook)} label="기존 웹훅 유지 (끄고 저장하면 삭제)" />
              )}
              <small className={styles.hint}>일반 회원에게는 이 항목이 보이지 않고, 서버도 운영자가 아니면 슬랙을 거부해요.</small>
            </div>
          )}
        </section>

        <section className={styles.panel}>
          <h2>무엇을 받을까요</h2>
          <div className={styles.group}>
            <div className={styles.groupTitle}>관심 공고 접수 마감</div>
            <div className={styles.chips}>
              {DEADLINE_OPTIONS.map((d) => (
                <button
                  key={d}
                  type="button"
                  className={`${styles.chip} ${days.includes(d) ? styles.chipOn : ''}`}
                  aria-pressed={days.includes(d)}
                  onClick={() => toggleDay(d)}
                >
                  {d === 0 ? '마감 당일 (D-day)' : `${d}일 전 (D-${d})`}
                </button>
              ))}
            </div>
            {days.length === 0 && <small className={styles.hint}>마감 알림을 받지 않아요.</small>}
          </div>
          <Switch checked={newMatch} onChange={mark(setNewMatch)} label="내 조건에 맞는 새 공고가 올라오면 알림" />
          <Switch
            checked={marketing}
            onChange={mark(setMarketing)}
            label={<>
              (광고) 소식·이벤트 메일 받기
              {s?.marketingConsent && s.marketingConsentedAt && <em className={styles.muted}> · 동의 {s.marketingConsentedAt.slice(0, 10)}</em>}
            </>}
          />
        </section>

        {save.isError && <div className={styles.error} role="alert">{errText(save.error)}</div>}
        {test.isError && <div className={styles.error} role="alert">{errText(test.error)}</div>}
        {test.isSuccess && (
          <p className={styles.ok}>
            테스트 알림: <b>{STATUS_LABEL[test.data.status]}</b>{test.data.deliveries.length > 0 && ` — ${deliveryText(test.data)}`}
          </p>
        )}

        <div className={styles.actions}>
          <button type="button" className={styles.ghost} onClick={() => test.mutate()} disabled={test.isPending || !canTest || dirty} title={dirty ? '먼저 저장해 주세요' : undefined}>
            테스트 발송
          </button>
          <button type="button" className={styles.primary} onClick={() => save.mutate()} disabled={save.isPending || !dirty}>
            {save.isPending ? '저장 중…' : save.isSuccess && !dirty ? '저장됨' : '저장'}
          </button>
        </div>
      </fieldset>

      <section className={styles.panel}>
        <h2>알림 내역</h2>
        {history.isPending && <p className={styles.muted} role="status">불러오는 중이에요…</p>}
        {history.isError && <p className={styles.muted}>알림 내역을 불러오지 못했어요.</p>}
        {history.isSuccess && history.data.length === 0 && <p className={styles.muted}>아직 받은 알림이 없어요.</p>}
        {history.data && history.data.length > 0 && (
          <ul className={styles.history}>
            {history.data.map((e: NotificationEvent) => (
              <li key={e.id}>
                <span className={`${styles.badge} ${styles[`badge_${e.status.toLowerCase()}`] ?? ''}`}>{STATUS_LABEL[e.status]}</span>
                <span className={styles.type}>{TYPE_LABEL[e.templateCode] ?? e.templateCode}</span>
                <span className={styles.title} title={deliveryText(e)}>{e.title}</span>
                <span className={styles.time}>{e.scheduledAt.slice(0, 16).replace('T', ' ')}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
