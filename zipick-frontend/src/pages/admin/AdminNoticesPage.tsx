import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/admin';
import type { AdminHousing, AdminNotice, NoticeStatus } from '../../types/admin';
import { Badge, Pager, errText, fmtTime } from './adminUi';

const STATUS_LABEL: Record<NoticeStatus, string> = { UPCOMING: '공고 예정', OPEN: '접수 중', CLOSED: '마감', CANCELLED: '취소' };
const ALL_STATUS: NoticeStatus[] = ['UPCOMING', 'OPEN', 'CLOSED', 'CANCELLED'];

function CoordForm({ h, onSave, saving }: { h: AdminHousing; onSave: (lat: number, lng: number) => void; saving: boolean }) {
  const [lat, setLat] = useState(h.latitude?.toString() ?? '');
  const [lng, setLng] = useState(h.longitude?.toString() ?? '');
  const valid = Number.isFinite(Number(lat)) && Number.isFinite(Number(lng)) && lat !== '' && lng !== '';
  return (
    <span className="inline-form">
      <input type="text" inputMode="decimal" placeholder="위도" value={lat} onChange={(e) => setLat(e.target.value)} />
      <input type="text" inputMode="decimal" placeholder="경도" value={lng} onChange={(e) => setLng(e.target.value)} />
      <button type="button" className="btn btn--small" disabled={!valid || saving} onClick={() => onSave(Number(lat), Number(lng))}>저장</button>
    </span>
  );
}

function HousingTable({ rows, showNotice, onDone }: { rows: AdminHousing[]; showNotice: boolean; onDone: () => void }) {
  const [msg, setMsg] = useState<string | null>(null);
  const coords = useMutation({ mutationFn: (p: { id: number; lat: number; lng: number }) => adminApi.setCoordinates(p.id, p.lat, p.lng), onSuccess: onDone, onError: (e) => setMsg(errText(e)) });
  const active = useMutation({ mutationFn: (p: { id: number; active: boolean }) => adminApi.setHousingActive(p.id, p.active), onSuccess: onDone, onError: (e) => setMsg(errText(e)) });
  const refresh = useMutation({ mutationFn: adminApi.refreshInfra, onSuccess: onDone, onError: (e) => setMsg(errText(e)) });
  return (
    <>
      {msg && <div className="error">{msg}</div>}
      <table className="admin-table">
        <thead><tr>{showNotice && <th>공고</th>}<th>주택</th><th>주소</th><th>좌표</th><th>인프라</th><th>상태</th><th></th></tr></thead>
        <tbody>
          {rows.map((h) => (
            <tr key={h.id}>
              {showNotice && <td><span className="clip" title={h.noticeTitle ?? ''}>{h.noticeTitle ?? h.noticeId}</span></td>}
              <td><a href={`/houses/${h.id}`} target="_blank" rel="noreferrer">{h.name ?? `#${h.id}`}</a></td>
              <td><span className="clip" title={h.address}>{h.address}</span></td>
              <td>
                {h.latitude != null && h.longitude != null
                  ? <span className="small">{h.latitude}, {h.longitude}</span>
                  : <CoordForm h={h} saving={coords.isPending} onSave={(lat, lng) => coords.mutate({ id: h.id, lat, lng })} />}
              </td>
              <td>{h.hasInfra ? <span className="ok-text small">있음 · {fmtTime(h.infraCollectedAt)}</span> : <span className="muted small">없음</span>}</td>
              <td><Badge value={h.active ? 'open' : 'closed'} label={h.active ? '표시' : '숨김'} /></td>
              <td className="actions">
                <button type="button" className="btn btn--small btn--ghost" onClick={() => active.mutate({ id: h.id, active: !h.active })}>{h.active ? '숨기기' : '표시'}</button>
                {h.latitude != null && <button type="button" className="btn btn--small btn--ghost" disabled={refresh.isPending} onClick={() => refresh.mutate(h.id)}>인프라 재수집</button>}
              </td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={showNotice ? 7 : 6} className="muted">주택이 없어요.</td></tr>}
        </tbody>
      </table>
    </>
  );
}

/**
 * 공고·주택 관리. 공고 상태 변경(숨김은 CLOSED/CANCELLED), 좌표 미해결 주택 직접 입력, 주택 숨김, 인프라 재수집 예약.
 * 인프라 재수집은 스냅샷만 지우고, 실제 수집은 다음 수집기 실행(또는 수집 현황의 "인프라만")이 한다.
 */
export default function AdminNoticesPage() {
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<NoticeStatus[]>(['UPCOMING', 'OPEN']);
  const [source, setSource] = useState('');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<AdminNotice | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  const notices = useQuery({ queryKey: ['admin', 'notices', keyword, status, source, page], queryFn: () => adminApi.notices({ q: keyword, status, source, page, size: 30 }), retry: false });
  const housings = useQuery({ queryKey: ['admin', 'housings', selected?.id], queryFn: () => adminApi.housingsOf(selected!.id), enabled: !!selected, retry: false });
  const unresolved = useQuery({ queryKey: ['admin', 'unresolved'], queryFn: adminApi.unresolvedHousings, retry: false });

  const refreshAll = () => { qc.invalidateQueries({ queryKey: ['admin'] }); };
  const changeStatus = useMutation({
    mutationFn: (p: { id: number; status: NoticeStatus }) => adminApi.changeNoticeStatus(p.id, p.status),
    onSuccess: (n) => { setSelected(n); refreshAll(); },
    onError: (e) => setMsg(errText(e)),
  });

  if (notices.isError) return <div className="error">{errText(notices.error)}</div>;

  return (
    <>
      <div className="admin__head"><h1>공고·주택</h1></div>
      {msg && <div className="error">{msg}</div>}

      <section className="panel">
        <h2 className="panel__title">좌표 미해결 주택 <span className="muted small">(활성인데 위경도 없음 — 카카오가 주소를 못 찾음)</span></h2>
        <p className="muted small">카카오맵에서 주소를 찾아 위도·경도를 넣어 주세요. 저장하면 다음 수집에서 주변 인프라가 채워지고 지도에 핀이 생겨요.</p>
        {unresolved.isError && <div className="error">{errText(unresolved.error)}</div>}
        {unresolved.data && <HousingTable rows={unresolved.data} showNotice onDone={refreshAll} />}
      </section>

      <section className="panel">
        <div className="admin__head">
          <h2 className="panel__title" style={{ margin: 0 }}>공고</h2>
          <form className="admin__toolbar" onSubmit={(e) => { e.preventDefault(); setKeyword(q.trim()); setPage(1); }}>
            <input type="search" placeholder="제목 · 공고번호" value={q} onChange={(e) => setQ(e.target.value)} />
            <select value={source} onChange={(e) => { setSource(e.target.value); setPage(1); }}>
              <option value="">출처 전체</option>
              <option value="SH">SH</option>
              <option value="GH">GH</option>
              <option value="HUG">HUG</option>
              <option value="LH">LH</option>
              <option value="MYHOME">마이홈</option>
            </select>
            {ALL_STATUS.map((s) => (
              <label key={s} className="small">
                <input type="checkbox" checked={status.includes(s)} onChange={(e) => { setStatus(e.target.checked ? [...status, s] : status.filter((x) => x !== s)); setPage(1); }} /> {STATUS_LABEL[s]}
              </label>
            ))}
            <button type="submit" className="btn btn--small">검색</button>
          </form>
        </div>
        <div className="admin-split">
          <div>
            <table className="admin-table">
              <thead><tr><th>#</th><th>출처</th><th>제목</th><th>상태</th><th>접수</th><th>마지막 확인</th></tr></thead>
              <tbody>
                {notices.data?.items.map((n) => (
                  <tr key={n.id} className={selected?.id === n.id ? 'is-selected' : ''} onClick={() => setSelected(n)} style={{ cursor: 'pointer' }}>
                    <td className="num">{n.id}</td>
                    <td>{n.source}</td>
                    <td><span className="clip" title={n.title}>{n.title}</span><span className="muted small">{n.agency} · {n.region ?? '-'}</span></td>
                    <td><Badge value={n.status} label={STATUS_LABEL[n.status]} />{n.statusReason === 'ADMIN' && <span className="muted small"> 운영자</span>}</td>
                    <td className="num small">{fmtTime(n.applyStartAt)}<br />~ {fmtTime(n.applyEndAt)}</td>
                    <td className="num small">{fmtTime(n.lastSeenAt)}</td>
                  </tr>
                ))}
                {notices.data && notices.data.items.length === 0 && <tr><td colSpan={6} className="muted">조건에 맞는 공고가 없어요.</td></tr>}
              </tbody>
            </table>
            {notices.data && <Pager page={notices.data.page} totalPages={notices.data.totalPages} total={notices.data.totalElements} onChange={setPage} />}
          </div>
          {selected && (
            <div className="admin-detail">
              <h3>{selected.title}</h3>
              <dl>
                <dt>출처</dt><dd>{selected.source} · {selected.externalNoticeId}</dd>
                <dt>기관</dt><dd>{selected.agency ?? '-'}</dd>
                <dt>유형</dt><dd>{selected.noticeType ?? '-'} / {selected.rentalType ?? '-'}</dd>
                <dt>상태</dt><dd><Badge value={selected.status} label={STATUS_LABEL[selected.status]} /> <span className="muted">({selected.statusReason ?? '-'})</span></dd>
                <dt>접수</dt><dd>{fmtTime(selected.applyStartAt)} ~ {fmtTime(selected.applyEndAt)}</dd>
                {selected.detailUrl && <><dt>원본</dt><dd><a href={selected.detailUrl} target="_blank" rel="noreferrer">열기</a></dd></>}
              </dl>
              <div className="admin__toolbar">
                <span className="small">상태 변경:</span>
                {ALL_STATUS.filter((s) => s !== selected.status).map((s) => (
                  <button key={s} type="button" className="btn btn--small btn--ghost" disabled={changeStatus.isPending} onClick={() => changeStatus.mutate({ id: selected.id, status: s })}>{STATUS_LABEL[s]}</button>
                ))}
              </div>
              <p className="muted small">마감·취소로 바꾸면 지도·목록에서 사라져요. 원본 공고 상태가 바뀌면 다음 수집에서 다시 덮어쓸 수 있어요.</p>
              <h3>주택 {housings.data ? `(${housings.data.length})` : ''}</h3>
              {housings.isError && <div className="error">{errText(housings.error)}</div>}
              {housings.data && <HousingTable rows={housings.data} showNotice={false} onDone={refreshAll} />}
            </div>
          )}
        </div>
      </section>
    </>
  );
}
