import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { notificationApi } from '../api/notification';
import type { NotificationEvent, NotificationStatus, NotificationType } from '../types';

const TYPE_LABEL: Record<NotificationType, string> = { DEADLINE: '접수 마감', NEW_MATCH: '새 공고', TEST: '테스트' };
const STATUS_LABEL: Record<NotificationStatus, string> = { PENDING: '대기', SENT: '보냄', FAILED: '실패', SKIPPED: '건너뜀' };

function errText(e: unknown) {
  return e instanceof Error ? e.message : '요청에 실패했어요.';
}

/**
 * 알림 설정. 슬랙 웹훅(incoming webhook URL) 또는 이메일로
 * ① 관심 공고의 접수 마감 D-day ② 내 조건에 맞는 새 공고 를 보낸다. 발송은 서버가 5분마다 확인한다.
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
  const [keepWebhook, setKeepWebhook] = useState(false);

  useEffect(() => {
    const s = setting.data;
    if (!s) return;
    setEnabled(s.enabled);
    setEmail(s.email ?? '');
    setDeadlineDays(s.deadlineDays.join(','));
    setNewMatch(s.newMatchEnabled);
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
          이메일 {s && !s.emailAvailable && <span className="muted small">(서버에 SMTP 설정이 없어 지금은 슬랙만 동작)</span>}
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
      </div>

      {save.isError && <div className="error">{errText(save.error)}</div>}
      {test.isError && <div className="error">{errText(test.error)}</div>}
      {test.isSuccess && (
        <p className="small">
          테스트 알림: <b>{STATUS_LABEL[test.data.status]}</b>
          {test.data.channels && ` (${test.data.channels})`}
          {test.data.lastError && ` · ${test.data.lastError}`}
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
                <span className="muted small">{TYPE_LABEL[e.type]}</span>
                <span className="notif__title">
                  {e.url ? <a href={e.url} target="_blank" rel="noreferrer">{e.title}</a> : e.title}
                </span>
                <span className="muted small">{(e.sentAt ?? e.scheduledFor).slice(0, 16).replace('T', ' ')}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
