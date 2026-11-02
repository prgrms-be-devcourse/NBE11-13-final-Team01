import { ApiError } from '../../api/http';

/** 운영자 화면 공통 조각. 화면마다 같은 표·배지·페이지 이동을 쓰므로 한곳에 둔다 */

export function errText(e: unknown) {
  if (e instanceof ApiError && e.status === 403) return '운영자(ROLE_ADMIN) 계정만 쓸 수 있어요.';
  return e instanceof Error ? e.message : '요청에 실패했어요.';
}

/** ISO → "10-29 09:30" (올해면 연도 생략). 없으면 '-' */
export function fmtTime(iso: string | null | undefined) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  const sameYear = d.getFullYear() === new Date().getFullYear();
  const ymd = `${sameYear ? '' : `${d.getFullYear()}-`}${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return `${ymd} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fmtDuration(sec: number | null | undefined) {
  if (sec == null) return '-';
  if (sec < 60) return `${sec}초`;
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m < 60) return `${m}분 ${s}초`;
  return `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

export function Badge({ value, label }: { value: string | null | undefined; label?: string }) {
  if (!value) return <span className="muted">-</span>;
  return <span className={`badge badge--${value.toLowerCase()}`}>{label ?? value}</span>;
}

export function Pager({ page, totalPages, total, onChange }: { page: number; totalPages: number; total: number; onChange: (p: number) => void }) {
  if (totalPages <= 1) return <div className="admin__pager">총 {total}건</div>;
  return (
    <div className="admin__pager">
      <span>총 {total}건 · {page}/{totalPages}</span>
      <button type="button" className="btn btn--small btn--ghost" disabled={page <= 1} onClick={() => onChange(page - 1)}>이전</button>
      <button type="button" className="btn btn--small btn--ghost" disabled={page >= totalPages} onClick={() => onChange(page + 1)}>다음</button>
    </div>
  );
}

export function Stat({ label, value, sub, tone }: { label: string; value: number | string; sub?: string; tone?: 'bad' | 'warn' }) {
  return (
    <div className={`stat${tone ? ` stat--${tone}` : ''}`}>
      <div className="stat__label">{label}</div>
      <div className="stat__value">{typeof value === 'number' ? value.toLocaleString() : value}{sub && <small>{sub}</small>}</div>
    </div>
  );
}
