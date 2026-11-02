import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '../../api/admin';
import type { NotificationEvent } from '../../types';
import { Badge, errText, fmtTime } from './adminUi';

/**
 * 알림 발송 이력. 데이터는 Go 알림센터 DB 에 있어 메인 서버가 HTTP 로 읽어 넘긴다.
 * 알림센터가 source 또는 tag 중 하나를 요구하므로 둘 중 하나는 꼭 고른다.
 */
export default function AdminNotificationsPage() {
  const [mode, setMode] = useState<'source' | 'user' | 'tag'>('source');
  const [source, setSource] = useState('main');
  const [userId, setUserId] = useState('');
  const [tag, setTag] = useState('kind:collector-report');
  const [applied, setApplied] = useState<{ source?: string; userId?: number; tag?: string }>({ source: 'main' });
  const [selected, setSelected] = useState<NotificationEvent | null>(null);

  const list = useQuery({ queryKey: ['admin', 'notifications', applied], queryFn: () => adminApi.notifications(applied), retry: false });
  const channels = useQuery({ queryKey: ['admin', 'channels'], queryFn: adminApi.channels, retry: false });

  const apply = () => {
    if (mode === 'source') setApplied({ source });
    else if (mode === 'user') setApplied(userId ? { userId: Number(userId) } : {});
    else setApplied({ tag });
    setSelected(null);
  };

  return (
    <>
      <div className="admin__head">
        <h1>알림 이력</h1>
        {channels.data && (
          <span className="muted small">
            채널: {Object.entries(channels.data).map(([k, v]) => `${k} ${v ? '켬' : '끔'}`).join(' · ')}
          </span>
        )}
      </div>

      <section className="panel">
        <form className="admin__toolbar" onSubmit={(e) => { e.preventDefault(); apply(); }}>
          <select value={mode} onChange={(e) => setMode(e.target.value as typeof mode)}>
            <option value="source">출처로</option>
            <option value="user">회원 id 로</option>
            <option value="tag">태그로</option>
          </select>
          {mode === 'source' && (
            <select value={source} onChange={(e) => setSource(e.target.value)}>
              <option value="main">main (가입 인증·마감·새 공고·캠페인)</option>
              <option value="collector">collector (수집 보고)</option>
            </select>
          )}
          {mode === 'user' && <input type="number" placeholder="회원 id" value={userId} onChange={(e) => setUserId(e.target.value)} />}
          {mode === 'tag' && <input type="text" placeholder="key:value (예: kind:collector-report)" value={tag} onChange={(e) => setTag(e.target.value)} />}
          <button type="submit" className="btn btn--small">조회</button>
        </form>
        {list.isError && <div className="error">{errText(list.error)}</div>}
        <div className="admin-split">
          <table className="admin-table">
            <thead><tr><th>#</th><th>출처</th><th>템플릿</th><th>제목</th><th>채널</th><th>상태</th><th>생성</th></tr></thead>
            <tbody>
              {list.data?.map((n) => (
                <tr key={n.id} className={selected?.id === n.id ? 'is-selected' : ''} onClick={() => setSelected(n)} style={{ cursor: 'pointer' }}>
                  <td className="num">{n.id}</td>
                  <td>{n.source}</td>
                  <td className="small">{n.templateCode}</td>
                  <td><span className="clip" title={n.title}>{n.title}</span></td>
                  <td className="small">{n.channels.join(', ')}</td>
                  <td><Badge value={n.status} /></td>
                  <td className="num small">{fmtTime(n.createdAt)}</td>
                </tr>
              ))}
              {list.data && list.data.length === 0 && <tr><td colSpan={7} className="muted">해당 알림이 없어요.</td></tr>}
            </tbody>
          </table>
          {selected && (
            <div className="admin-detail">
              <h3>#{selected.id} {selected.title}</h3>
              <dl>
                <dt>출처</dt><dd>{selected.source} · {selected.templateCode}{selected.batchId ? ` · 묶음 ${selected.batchId}` : ''}</dd>
                <dt>상태</dt><dd><Badge value={selected.status} />{selected.duplicate && <span className="muted small"> 중복 건너뜀</span>}</dd>
                <dt>예약</dt><dd>{fmtTime(selected.scheduledAt)}</dd>
                <dt>생성</dt><dd>{fmtTime(selected.createdAt)}</dd>
                {Object.keys(selected.tags ?? {}).length > 0 && <><dt>태그</dt><dd>{Object.entries(selected.tags).map(([k, v]) => `${k}:${v}`).join(' · ')}</dd></>}
                {selected.missingVariables?.length > 0 && <><dt>빠진 변수</dt><dd className="small" style={{ color: 'var(--bad)' }}>{selected.missingVariables.join(', ')}</dd></>}
              </dl>
              <h3>채널별 전달</h3>
              <table className="admin-table">
                <thead><tr><th>채널</th><th>상태</th><th>시도</th><th>보낸 시각</th><th>오류</th></tr></thead>
                <tbody>
                  {selected.deliveries.map((d) => (
                    <tr key={d.channel}>
                      <td>{d.channel}</td>
                      <td><Badge value={d.status} /></td>
                      <td className="num">{d.attempts}</td>
                      <td className="num small">{fmtTime(d.sentAt)}</td>
                      <td className="small" style={{ wordBreak: 'break-all' }}>{d.lastError ?? '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="panel__actions"><button type="button" className="btn btn--ghost" onClick={() => setSelected(null)}>닫기</button></div>
            </div>
          )}
        </div>
      </section>
    </>
  );
}
