import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { notificationApi } from '../api/notification';
import type { NotificationEvent, NotificationStatus } from '../types';

const TYPE_LABEL: Record<string, string> = {
  NOTICE_DEADLINE_REMINDER: '접수 마감', NOTICE_NEW_MATCH: '새 공고', MAIN_TEST: '테스트',
  AUTH_EMAIL_VERIFY: '이메일 인증', AUTH_PASSWORD_CHANGED: '비밀번호 변경', MARKETING_NEWSLETTER: '소식',
};
const STATUS_LABEL: Record<NotificationStatus, string> = { PENDING: '대기', SENT: '보냄', PARTIAL: '일부 실패', FAILED: '실패', CANCELLED: '취소' };
const typeLabel = (t: string) => TYPE_LABEL[t] ?? t;
/** 채널별 결과를 한 줄로: "email 보냄 · slack 실패(이유)" */
const deliveryText = (e: NotificationEvent) =>
  e.deliveries.map((d) => `${d.channel} ${d.status === 'SENT' ? '보냄' : d.status === 'FAILED' ? '실패' : '대기'}${d.lastError ? `(${d.lastError})` : ''}`).join(' · ');

function errText(e: unknown) {
  return e instanceof Error ? e.message : '요청에 실패했어요.';
}

/**
 * 알림 설정. 설정은 메인 서버가 갖고, 보내는 일만 알림센터(별도 서버)가 한다.
 * 슬랙 웹훅(incoming webhook URL) 또는 이메일로 ① 관심 공고의 접수 마감 D-day ② 내 조건에 맞는 새 공고 를 보낸다.
 * 광고성 소식은 따로 동의한 사람에게만 간다.
 */
export default function NotificationPanel() {
  const qc = useQueryClient();
  const setting = useQuery({ queryKey: ['notification-setting'], queryFn: notificationApi.setting });
  const history = useQuery({ queryKey: ['notification-history'], queryFn: notificationApi.history });

  const [enabled, setEnabled] = useState(false);
  const [webhook, setWebhook] = useState('');
  const [email, setEmail] = useState('');
  const [deadlineDays, setDeadlineDays] = useState('3,1,0');
  const [newMatch, setNewMatch] = useState(true);
  const [marketing, setMarketing] = useState(false);
  const [keepWebhook, setKeepWebhook] = useState(false);

  useEffect(() => {
    const s = setting.data;
    if (!s) return;
    setEnabled(s.enabled);
    setEmail(s.email ?? '');
    setDeadlineDays(s.deadlineDays.join(','));
    setNewMatch(s.newMatchEnabled);
    setMarketing(s.marketingConsent);
    setKeepWebhook(s.slackConfigured);
    setWebhook('');
  }, [setting.data]);

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['notification-setting'] });
    qc.invalidateQueries({ queryKey: ['notification-history'] });
  };
  const save = useMutation({
    mutationFn: () =>
      notificationApi.save({
        enabled,
        // 입력란이 비어 있고 "기존 웹훅 유지"면 저장된 값을 그대로 둔다 (서버는 null 이면 기존 값 유지)
        slackWebhookUrl: webhook.trim() ? webhook.trim() : keepWebhook ? undefined : '',
        email: email.trim() || '',
        marketingConsent: marketing,
        deadlineDays,
        newMatchEnabled: newMatch,
      }),
    onSuccess: invalidate,
  });
  const test = useMutation({ mutationFn: notificationApi.test, onSuccess: invalidate });

  const s = setting.data;

  return (
    <section className="panel notif">
      <h2 className="panel__title">알림 설정</h2>
      <p className="muted small">
        관심 공고의 접수 마감이 다가오면, 그리고 내 조건에 맞는 새 공고가 들어오면 슬랙이나 이메일로 알려 드려요. 서버가 5분마다 확인해요.
      </p>

      <label className="toggle">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        알림 받기
      </label>

      <div className="notif__grid">
        <label>
          슬랙 웹훅 URL {s?.slackConfigured && <span className="muted small">(저장됨 {s.slackWebhookMasked})</span>}
          <input
            type="text"
            placeholder={s?.slackConfigured ? '바꾸려면 새 주소를 넣으세요' : 'https://hooks.slack.com/services/…'}
            value={webhook}
            onChange={(e) => setWebhook(e.target.value)}
            autoComplete="off"
          />
          {s?.slackConfigured && (
            <span className="toggle small">
              <input type="checkbox" checked={keepWebhook} onChange={(e) => setKeepWebhook(e.target.checked)} />
              기존 웹훅 유지 (끄면 삭제)
            </span>
          )}
        </label>
        <label>
          이메일 {s && !s.emailAvailable && <span className="muted small">(알림센터에 이메일 발송 설정이 없어 지금은 슬랙만 동작)</span>}
          <input type="email" placeholder="me@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          마감 알림 (며칠 전, 쉼표로)
          <input type="text" value={deadlineDays} onChange={(e) => setDeadlineDays(e.target.value)} placeholder="3,1,0" />
        </label>
        <label className="toggle">
          <input type="checkbox" checked={newMatch} onChange={(e) => setNewMatch(e.target.checked)} />
          내 조건에 맞는 새 공고 알림
        </label>
        <label className="toggle">
          <input type="checkbox" checked={marketing} onChange={(e) => setMarketing(e.target.checked)} />
          <span>
            (광고) 소식·이벤트 메시지 받기
            {s?.marketingConsent && s.marketingConsentedAt && (
              <span className="muted small"> · 동의 {s.marketingConsentedAt.slice(0, 10)}</span>
            )}
          </span>
        </label>
      </div>

      {save.isError && <div className="error">{errText(save.error)}</div>}
      {test.isError && <div className="error">{errText(test.error)}</div>}
      {test.isSuccess && (
        <p className="small">
          테스트 알림: <b>{STATUS_LABEL[test.data.status]}</b>
          {test.data.deliveries.length > 0 && ` — ${deliveryText(test.data)}`}
        </p>
      )}

      <div className="panel__actions">
        <button type="button" className="btn btn--ghost" onClick={() => test.mutate()} disabled={test.isPending || !s?.slackConfigured && !s?.email}>
          테스트 발송
        </button>
        <button type="button" className="btn btn--primary" onClick={() => save.mutate()} disabled={save.isPending}>
          저장
        </button>
      </div>

      {history.data && history.data.length > 0 && (
        <details className="notif__history">
          <summary>알림 내역 ({history.data.length})</summary>
          <ul>
            {history.data.map((e: NotificationEvent) => (
              <li key={e.id}>
                <span className={`badge badge--${e.status.toLowerCase()}`}>{STATUS_LABEL[e.status]}</span>
                <span className="muted small">{typeLabel(e.templateCode)}</span>
                <span className="notif__title" title={deliveryText(e)}>{e.title}</span>
                <span className="muted small">{e.scheduledAt.slice(0, 16).replace('T', ' ')}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
