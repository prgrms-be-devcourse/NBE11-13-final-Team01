import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError } from '../api/client';
import { campaignApi } from '../api/campaign';
import type { Campaign, CampaignStatus } from '../types';

const STATUS_LABEL: Record<CampaignStatus, string> = { DRAFT: '초안', SCHEDULED: '예약됨', CANCELLED: '취소' };

function errText(e: unknown) {
  if (e instanceof ApiError && e.status === 403) return '운영자(ROLE_ADMIN) 계정만 쓸 수 있어요.';
  return e instanceof Error ? e.message : '요청에 실패했어요.';
}

/**
 * 운영자용 소식 메일 캠페인. 받는 사람(광고 수신 동의자)과 시각(21~08시 회피)은 메인 서버가 정하고,
 * 알림센터에는 수신자 목록이 든 묶음 요청만 간다. 예약 뒤에는 알림센터의 집계(보냄/대기/실패)를 보여 준다.
 */
export default function AdminCampaignPage() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['campaigns'], queryFn: campaignApi.list, retry: false });
  const [selected, setSelected] = useState<number | null>(null);
  const detail = useQuery({ queryKey: ['campaign', selected], queryFn: () => campaignApi.get(selected!), enabled: selected != null, refetchInterval: 30_000 });

  const [title, setTitle] = useState('');
  const [headline, setHeadline] = useState('');
  const [message, setMessage] = useState('');
  const [scheduledAt, setScheduledAt] = useState('');

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['campaigns'] });
    qc.invalidateQueries({ queryKey: ['campaign'] });
  };
  const create = useMutation({
    mutationFn: () => campaignApi.create({ title, headline, message, scheduledAt: scheduledAt ? `${scheduledAt}:00` : null }),
    onSuccess: (c) => { refresh(); setSelected(c.id); setTitle(''); setHeadline(''); setMessage(''); setScheduledAt(''); },
  });
  const schedule = useMutation({ mutationFn: campaignApi.schedule, onSuccess: refresh });
  const cancel = useMutation({ mutationFn: campaignApi.cancel, onSuccess: refresh });

  if (list.isError) return <div className="page"><div className="error">{errText(list.error)}</div></div>;

  const d = detail.data;
  return (
    <div className="page admin-campaign">
      <h1>소식 메일 캠페인</h1>
      <p className="muted small">
        알림이 켜져 있고 (광고) 수신에 동의한 사용자에게만 갑니다. 제목에는 "(광고)" 가, 본문 끝에는 수신 거부 안내가 자동으로 붙어요. 21~08시는 피해서 예약됩니다.
      </p>

      <section className="panel">
        <h2 className="panel__title">새 캠페인</h2>
        <div className="notif__grid">
          <label>이름 (운영자용)<input type="text" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={100} /></label>
          <label>제목 줄<input type="text" value={headline} onChange={(e) => setHeadline(e.target.value)} maxLength={200} placeholder="10월 새 공고 모음" /></label>
          <label>보낼 시각 (비우면 지금)<input type="datetime-local" value={scheduledAt} onChange={(e) => setScheduledAt(e.target.value)} /></label>
        </div>
        <label className="admin-campaign__message">
          본문
          <textarea rows={6} value={message} onChange={(e) => setMessage(e.target.value)} maxLength={5000} />
        </label>
        {create.isError && <div className="error">{errText(create.error)}</div>}
        <div className="panel__actions">
          <button type="button" className="btn btn--primary" disabled={create.isPending || !title || !headline || !message} onClick={() => create.mutate()}>
            초안 저장
          </button>
        </div>
      </section>

      <section className="panel">
        <h2 className="panel__title">캠페인 목록</h2>
        {list.data && list.data.length === 0 && <p className="muted small">아직 없어요.</p>}
        <ul className="admin-campaign__list">
          {list.data?.map((c: Campaign) => (
            <li key={c.id} className={c.id === selected ? 'is-selected' : ''}>
              <button type="button" className="linklike" onClick={() => setSelected(c.id)}>
                <span className={`badge badge--${c.status.toLowerCase()}`}>{STATUS_LABEL[c.status]}</span> {c.title}
                <span className="muted small"> · {c.scheduledAt.slice(0, 16).replace('T', ' ')} · 수신자 {c.recipientCount}</span>
              </button>
            </li>
          ))}
        </ul>
      </section>

      {d && (
        <section className="panel">
          <h2 className="panel__title">{d.title} <span className={`badge badge--${d.status.toLowerCase()}`}>{STATUS_LABEL[d.status]}</span></h2>
          <p><b>(광고) {d.headline}</b></p>
          <p className="admin-campaign__preview">{d.message}</p>
          <p className="muted small">보낼 시각 {d.scheduledAt.slice(0, 16).replace('T', ' ')} · 수신자 {d.recipientCount}명</p>
          {d.progress && (
            <p className="small">
              알림센터 집계: 보냄 {d.progress.sent} · 대기 {d.progress.pending} · 일부 실패 {d.progress.partial} · 실패 {d.progress.failed} · 취소 {d.progress.cancelled} (총 {d.progress.total})
            </p>
          )}
          {d.status === 'SCHEDULED' && !d.progress && <p className="muted small">알림센터 집계를 받지 못했어요.</p>}
          {(schedule.isError || cancel.isError) && <div className="error">{errText(schedule.error ?? cancel.error)}</div>}
          <div className="panel__actions">
            {d.status === 'DRAFT' && (
              <button type="button" className="btn btn--primary" disabled={schedule.isPending} onClick={() => schedule.mutate(d.id)}>
                동의자에게 예약
              </button>
            )}
            {d.status !== 'CANCELLED' && (
              <button type="button" className="btn btn--ghost" disabled={cancel.isPending} onClick={() => { if (window.confirm('아직 안 나간 메시지를 취소할까요?')) cancel.mutate(d.id); }}>
                취소
              </button>
            )}
          </div>
        </section>
      )}
    </div>
  );
}
