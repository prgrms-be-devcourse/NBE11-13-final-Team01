import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '../../api/admin';
import type { ActivityActor, ActivityEvent } from '../../types/admin';
import { Badge, Pager, errText, fmtTime } from './adminUi';

const TYPE_LABEL: Record<string, string> = { PAGE_VIEW: '화면', CLICK: '클릭', LOGIN: '로그인' };
const TYPE_BADGE: Record<string, string> = { PAGE_VIEW: 'upcoming', CLICK: 'open', LOGIN: 'success' };

function actorName(a: { userId: number | null; anonymousId: string | null }) {
  if (a.userId != null) return `회원 #${a.userId}`;
  return a.anonymousId ? `비회원 ${a.anonymousId.slice(0, 8)}` : '-';
}

/** 시간순 이동 경로. 화면 진입을 큰 단위로, 그 안의 클릭을 들여쓰기로 보여 준다 */
export function Trail({ events }: { events: ActivityEvent[] }) {
  if (events.length === 0) return <p className="muted">기록이 없어요.</p>;
  return (
    <ol className="trail">
      {events.map((e, i) => {
        const prev = events[i - 1];
        const gapMin = prev ? Math.round((new Date(e.occurredAt).getTime() - new Date(prev.occurredAt).getTime()) / 60000) : 0;
        return (
          <li key={e.id} className={`trail__item trail__item--${e.eventType.toLowerCase()}`}>
            {gapMin >= 30 && <div className="trail__gap">{gapMin >= 1440 ? `${Math.floor(gapMin / 1440)}일` : gapMin >= 60 ? `${Math.floor(gapMin / 60)}시간` : `${gapMin}분`} 뒤</div>}
            <span className="trail__time">{fmtTime(e.occurredAt)}</span>
            <Badge value={TYPE_BADGE[e.eventType]} label={TYPE_LABEL[e.eventType] ?? e.eventType} />
            {e.eventType === 'CLICK'
              ? <span className="trail__text"><b>{e.label}</b>{e.target && <span className="muted"> → {e.target}</span>}<span className="muted small"> ({e.path})</span></span>
              : <span className="trail__text"><b>{e.path}</b>{e.ip && <span className="muted small"> · {e.ip}</span>}</span>}
          </li>
        );
      })}
    </ol>
  );
}

/**
 * 사용자 행동: 누가(회원/브라우저) 어떤 IP 로 어떤 화면까지 보고 무엇을 눌렀는지.
 * 왼쪽은 최근 활동한 사람 목록, 고르면 오른쪽에 시간순 이동 경로. 아래는 전체 이벤트 검색.
 */
export default function AdminActivityPage() {
  const [hours, setHours] = useState(24);
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<ActivityActor | null>(null);
  const [filter, setFilter] = useState({ ip: '', path: '', type: '' });
  const [applied, setApplied] = useState(filter);
  const [evPage, setEvPage] = useState(1);

  const actors = useQuery({ queryKey: ['admin', 'actors', hours, page], queryFn: () => adminApi.actors(hours, page, 30), refetchInterval: 30_000, retry: false });
  const trail = useQuery({
    queryKey: ['admin', 'trail', selected?.userId, selected?.anonymousId],
    queryFn: () => adminApi.trail(selected!.userId != null ? { userId: selected!.userId, limit: 300 } : { anonymousId: selected!.anonymousId!, limit: 300 }),
    enabled: !!selected, retry: false,
  });
  const events = useQuery({
    queryKey: ['admin', 'events', applied, evPage],
    queryFn: () => adminApi.activityEvents({ ip: applied.ip || undefined, path: applied.path || undefined, type: applied.type || undefined, page: evPage, size: 50 }),
    retry: false,
  });

  if (actors.isError) return <div className="error">{errText(actors.error)}</div>;

  return (
    <>
      <div className="admin__head">
        <h1>사용자 행동</h1>
        <span className="muted small">화면 진입·클릭·로그인을 기록합니다. IP 는 개인정보라 이 화면에서만 보이고 90일 뒤 지워져요.</span>
      </div>

      <section className="panel">
        <div className="admin__head">
          <h2 className="panel__title" style={{ margin: 0 }}>최근 활동</h2>
          <div className="admin__toolbar">
            <select value={hours} onChange={(e) => { setHours(Number(e.target.value)); setPage(1); }}>
              <option value={1}>최근 1시간</option>
              <option value={24}>최근 24시간</option>
              <option value={168}>최근 7일</option>
              <option value={720}>최근 30일</option>
            </select>
          </div>
        </div>
        <div className="admin-split">
          <div>
            <table className="admin-table">
              <thead><tr><th>누구</th><th>마지막 IP</th><th>마지막 화면</th><th>화면</th><th>클릭</th><th>처음</th><th>마지막</th></tr></thead>
              <tbody>
                {actors.data?.items.map((a) => {
                  const key = a.userId != null ? `u${a.userId}` : `a${a.anonymousId}`;
                  const sel = selected && (selected.userId != null ? selected.userId === a.userId : selected.anonymousId === a.anonymousId);
                  return (
                    <tr key={key} className={sel ? 'is-selected' : ''} onClick={() => setSelected(a)} style={{ cursor: 'pointer' }}>
                      <td>{actorName(a)}</td>
                      <td className="small">{a.lastIp ?? '-'}</td>
                      <td><span className="clip" title={a.lastPath ?? ''}>{a.lastPath ?? '-'}</span></td>
                      <td className="num">{a.pageViews}</td>
                      <td className="num">{a.clicks}</td>
                      <td className="num small">{fmtTime(a.firstSeen)}</td>
                      <td className="num small">{fmtTime(a.lastSeen)}</td>
                    </tr>
                  );
                })}
                {actors.data && actors.data.items.length === 0 && <tr><td colSpan={7} className="muted">이 기간에 활동이 없어요. 배포 뒤 사용자가 화면을 열면 쌓입니다.</td></tr>}
              </tbody>
            </table>
            {actors.data && <Pager page={actors.data.page} totalPages={actors.data.totalPages} total={actors.data.totalElements} onChange={setPage} />}
          </div>
          {selected && (
            <div className="admin-detail">
              <h3>{actorName(selected)} 의 이동 경로</h3>
              <dl>
                <dt>마지막 IP</dt><dd>{selected.lastIp ?? '-'}</dd>
                <dt>활동</dt><dd>{fmtTime(selected.firstSeen)} ~ {fmtTime(selected.lastSeen)}</dd>
                <dt>이벤트</dt><dd>화면 {selected.pageViews} · 클릭 {selected.clicks} (총 {selected.events})</dd>
                {selected.anonymousId && <><dt>브라우저</dt><dd className="small" style={{ wordBreak: 'break-all' }}>{selected.anonymousId}</dd></>}
              </dl>
              {trail.isError && <div className="error">{errText(trail.error)}</div>}
              {trail.data && <Trail events={trail.data} />}
              <div className="panel__actions"><button type="button" className="btn btn--ghost" onClick={() => setSelected(null)}>닫기</button></div>
            </div>
          )}
        </div>
      </section>

      <section className="panel">
        <div className="admin__head">
          <h2 className="panel__title" style={{ margin: 0 }}>이벤트 검색</h2>
          <form className="admin__toolbar" onSubmit={(e) => { e.preventDefault(); setApplied(filter); setEvPage(1); }}>
            <input type="text" placeholder="IP" value={filter.ip} onChange={(e) => setFilter({ ...filter, ip: e.target.value })} style={{ minWidth: 160 }} />
            <input type="text" placeholder="경로 포함 (예: /houses)" value={filter.path} onChange={(e) => setFilter({ ...filter, path: e.target.value })} />
            <select value={filter.type} onChange={(e) => setFilter({ ...filter, type: e.target.value })}>
              <option value="">종류 전체</option>
              <option value="PAGE_VIEW">화면</option>
              <option value="CLICK">클릭</option>
              <option value="LOGIN">로그인</option>
            </select>
            <button type="submit" className="btn btn--small">검색</button>
          </form>
        </div>
        {events.isError && <div className="error">{errText(events.error)}</div>}
        <table className="admin-table">
          <thead><tr><th>시각</th><th>누구</th><th>IP</th><th>종류</th><th>화면</th><th>클릭한 것</th><th>브라우저</th></tr></thead>
          <tbody>
            {events.data?.items.map((e) => (
              <tr key={e.id}>
                <td className="num small">{fmtTime(e.occurredAt)}</td>
                <td className="small">{actorName(e)}</td>
                <td className="small">{e.ip ?? '-'}</td>
                <td><Badge value={TYPE_BADGE[e.eventType]} label={TYPE_LABEL[e.eventType] ?? e.eventType} /></td>
                <td><span className="clip" title={e.path}>{e.path}</span></td>
                <td>{e.label ? <><b>{e.label}</b>{e.target && <span className="muted small"> → {e.target}</span>}</> : <span className="muted">-</span>}</td>
                <td><span className="clip muted small" title={e.userAgent ?? ''} style={{ maxWidth: 180 }}>{e.userAgent ?? '-'}</span></td>
              </tr>
            ))}
            {events.data && events.data.items.length === 0 && <tr><td colSpan={7} className="muted">조건에 맞는 이벤트가 없어요.</td></tr>}
          </tbody>
        </table>
        {events.data && <Pager page={events.data.page} totalPages={events.data.totalPages} total={events.data.totalElements} onChange={setEvPage} />}
      </section>
    </>
  );
}
