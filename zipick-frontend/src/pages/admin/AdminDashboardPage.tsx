import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/admin';
import type { CollectionJobType, CollectionRun } from '../../types/admin';
import { Badge, Pager, Stat, errText, fmtDuration, fmtTime } from './adminUi';

const JOB_LABEL: Record<CollectionJobType, string> = { BASIC: '기본 수집', AI: 'AI 수집', INFRA: '인프라만', FULL: '전체 (기본 → AI)' };
const RUN_LABEL: Record<string, string> = { RUNNING: '실행 중', SUCCESS: '성공', FAILED: '실패' };
const REQ_LABEL: Record<string, string> = { PENDING: '대기', RUNNING: '실행 중', DONE: '완료', FAILED: '실패', CANCELLED: '취소' };
const TRIGGER_LABEL: Record<string, string> = { CRON: '자동', ADMIN: '운영자', MANUAL: '수동' };

function summaryLine(r: CollectionRun) {
  const s = r.summary ?? {};
  const parts: string[] = [];
  if (r.jobType === 'BASIC') {
    if (s.collectedRows != null) parts.push(`원본 ${s.collectedRows}행`);
    if (s.createdNotices != null) parts.push(`신규 ${s.createdNotices}`);
    if (s.updatedNotices != null) parts.push(`갱신 ${s.updatedNotices}`);
    if (s.housings != null) parts.push(`주택 ${s.housings}`);
  }
  if (r.jobType === 'AI') {
    if (s.processed != null) parts.push(`처리 ${s.processed}/${s.candidates ?? '?'}`);
    if (s.detailed != null) parts.push(`상세 반영 ${s.detailed}`);
    if (s.failed) parts.push(`실패 ${s.failed}`);
    if (s.normalizationCandidates != null) parts.push(`정규화 후보 ${s.normalizationCandidates}`);
  }
  if (s.infra) {
    if (s.infra.skipped) parts.push('인프라 건너뜀');
    else parts.push(`인프라 ${s.infra.saved ?? 0}/${s.infra.targets ?? 0}${s.infra.aborted ? ' (중단)' : ''}`);
  }
  if (s.raw) parts.push(s.raw);
  return parts.join(' · ') || '-';
}

function RunCard({ title, run }: { title: string; run?: CollectionRun }) {
  return (
    <div className="run-card">
      <div className="run-card__title">
        <span>{title}</span>
        {run ? <Badge value={run.status} label={RUN_LABEL[run.status]} /> : <span className="muted small">기록 없음</span>}
      </div>
      {run && (
        <dl>
          <dt>시작</dt><dd>{fmtTime(run.startedAt)} <span className="muted">({TRIGGER_LABEL[run.trigger] ?? run.trigger})</span></dd>
          <dt>소요</dt><dd>{run.status === 'RUNNING' ? '진행 중…' : fmtDuration(run.durationSeconds)}</dd>
          <dt>결과</dt><dd>{summaryLine(run)}</dd>
          {run.errorMessage && <><dt>오류</dt><dd className="error" style={{ margin: 0 }}>{run.errorMessage}</dd></>}
        </dl>
      )}
    </div>
  );
}

/**
 * 수집 현황. 지금까지 SSH 로 로그를 뒤지던 일을 대신한다.
 * 실행 기록은 수집기가 collection_run 에 남기고, 수동 실행은 요청 행만 남긴 뒤 앱 서버 cron(1분)이 집어 돌린다.
 */
export default function AdminDashboardPage() {
  const qc = useQueryClient();
  const [jobType, setJobType] = useState<CollectionJobType>('FULL');
  const [note, setNote] = useState('');
  const [runFilter, setRunFilter] = useState<CollectionJobType | ''>('');
  const [runPage, setRunPage] = useState(1);
  const [msg, setMsg] = useState<string | null>(null);

  const overview = useQuery({ queryKey: ['admin', 'overview'], queryFn: adminApi.overview, refetchInterval: 30_000, retry: false });
  const stats = useQuery({ queryKey: ['admin', 'stats'], queryFn: adminApi.stats, retry: false });
  const runs = useQuery({ queryKey: ['admin', 'runs', runFilter, runPage], queryFn: () => adminApi.runs(runFilter || undefined, runPage, 20), retry: false });
  const requests = useQuery({ queryKey: ['admin', 'requests'], queryFn: () => adminApi.requests(1, 10), refetchInterval: 30_000, retry: false });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['admin', 'overview'] });
    qc.invalidateQueries({ queryKey: ['admin', 'requests'] });
    qc.invalidateQueries({ queryKey: ['admin', 'runs'] });
  };
  const request = useMutation({
    mutationFn: () => adminApi.requestRun(jobType, note.trim() || undefined),
    onSuccess: () => { setMsg('요청을 남겼어요. 앱 서버가 1분 안에 집어 실행합니다.'); setNote(''); invalidate(); },
    onError: (e) => setMsg(errText(e)),
  });
  const cancel = useMutation({ mutationFn: adminApi.cancelRequest, onSuccess: invalidate, onError: (e) => setMsg(errText(e)) });
  const abandon = useMutation({ mutationFn: adminApi.abandonRun, onSuccess: invalidate, onError: (e) => setMsg(errText(e)) });

  if (overview.isError) return <div className="error">{errText(overview.error)}</div>;
  const o = overview.data;
  const busy = (o?.running.length ?? 0) > 0 || (o?.openRequests.length ?? 0) > 0;

  return (
    <>
      <div className="admin__head"><h1>수집 현황</h1>{o && <span className="muted small">30초마다 새로 고침</span>}</div>

      {o && (
        <div className="run-cards">
          <RunCard title="기본 수집" run={o.lastRuns.BASIC} />
          <RunCard title="AI 수집" run={o.lastRuns.AI} />
          <RunCard title="인프라만" run={o.lastRuns.INFRA} />
        </div>
      )}

      <section className="panel">
        <h2 className="panel__title">수동 실행</h2>
        <p className="muted small">웹 서버가 직접 돌리지 않고 요청만 남깁니다. 앱 서버 cron 이 1분 안에 collector 컨테이너로 실행하며, 09:30 일일 수집과 겹치면 끝난 뒤 이어서 돕니다.</p>
        <div className="admin__toolbar">
          <select value={jobType} onChange={(e) => setJobType(e.target.value as CollectionJobType)}>
            {(Object.keys(JOB_LABEL) as CollectionJobType[]).map((k) => <option key={k} value={k}>{JOB_LABEL[k]}</option>)}
          </select>
          <input type="text" placeholder="메모 (선택)" value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} />
          <button type="button" className="btn btn--primary" disabled={request.isPending || busy} onClick={() => request.mutate()}>
            {busy ? '실행 중 / 대기 중인 요청 있음' : '실행 요청'}
          </button>
        </div>
        {msg && <p className="small">{msg}</p>}
        {o && (o.running.length > 0 || o.openRequests.length > 0) && (
          <ul className="small" style={{ margin: '8px 0 0', paddingLeft: 18 }}>
            {o.running.map((r) => (
              <li key={`run-${r.id}`}>
                실행 중: {JOB_LABEL[r.jobType]} · {fmtTime(r.startedAt)} 시작 ({TRIGGER_LABEL[r.trigger] ?? r.trigger})
                {' '}<button type="button" className="btn btn--small btn--ghost" onClick={() => { if (confirm('프로세스가 죽어 남은 기록일 때만 닫으세요. 닫을까요?')) abandon.mutate(r.id); }}>기록 닫기</button>
              </li>
            ))}
            {o.openRequests.map((r) => (
              <li key={`req-${r.id}`}>
                요청 #{r.id}: {JOB_LABEL[r.jobType]} · <Badge value={r.status} label={REQ_LABEL[r.status]} /> · {fmtTime(r.createdAt)}
                {r.status === 'PENDING' && <> <button type="button" className="btn btn--small btn--ghost" onClick={() => cancel.mutate(r.id)}>취소</button></>}
              </li>
            ))}
          </ul>
        )}
      </section>

      {o && (
        <section className="panel">
          <h2 className="panel__title">데이터</h2>
          <div className="stat-grid">
            <Stat label="주택 (활성/전체)" value={o.data.activeHousings} sub={`/ ${o.data.housings.toLocaleString()}`} />
            <Stat label="좌표 미해결 주택" value={o.data.housingsWithoutCoordinates} tone={o.data.housingsWithoutCoordinates > 0 ? 'warn' : undefined} />
            <Stat label="모집 중 주택" value={o.data.openHousings} />
            <Stat label="모집 중인데 인프라 없음" value={o.data.openHousingsWithoutInfra} tone={o.data.openHousingsWithoutInfra > 0 ? 'warn' : undefined} />
            <Stat label="인프라 스냅샷" value={o.data.infraSnapshots} />
            <Stat label="AI 미완료 문서" value={o.data.openAiDocumentIssues} tone={o.data.openAiDocumentIssues > 0 ? 'warn' : undefined} />
            {Object.entries(o.data.noticesByStatus).map(([k, v]) => <Stat key={k} label={`공고 ${k}`} value={v} />)}
            {Object.entries(o.data.normalizationJobsByStatus).map(([k, v]) => <Stat key={k} label={`정규화 ${k}`} value={v} tone={k === 'RETRY' && v > 0 ? 'warn' : undefined} />)}
          </div>
        </section>
      )}

      {stats.data && (
        <section className="panel">
          <h2 className="panel__title">운영 지표</h2>
          <div className="stat-grid">
            <Stat label="회원" value={stats.data.users.total} />
            <Stat label="최근 7일 가입" value={stats.data.users.newLast7Days} sub={`/ 30일 ${stats.data.users.newLast30Days}`} />
            <Stat label="온보딩 완료" value={stats.data.users.onboarded} sub={`${stats.data.users.onboardingRate}%`} />
            <Stat label="알림 켬" value={stats.data.users.notificationEnabled} sub={`광고 동의 ${stats.data.users.marketingConsented}`} />
            <Stat label="관심 공고" value={stats.data.users.interestNotices} />
            <Stat label="AI 신청 가이드 (7일)" value={stats.data.ai.guidesLast7Days} sub={`/ 30일 ${stats.data.ai.guidesLast30Days}`} />
            <Stat label="수집 실행 (7일)" value={stats.data.collection.runsLast7Days} sub={`실패 ${stats.data.collection.failedLast7Days}`} tone={stats.data.collection.failedLast7Days > 0 ? 'bad' : undefined} />
            <Stat label="신규 공고 (7일)" value={stats.data.collection.createdNoticesLast7Days} sub={`인프라 ${stats.data.collection.infraSavedLast7Days}`} />
          </div>
          {stats.data.topRegions.length > 0 && (
            <p className="small" style={{ marginTop: 10 }}>
              <b>희망 지역 상위:</b> {stats.data.topRegions.map((r) => `${r.key} ${r.value}`).join(' · ')}
            </p>
          )}
        </section>
      )}

      <section className="panel">
        <div className="admin__head">
          <h2 className="panel__title" style={{ margin: 0 }}>실행 기록</h2>
          <div className="admin__toolbar">
            <select value={runFilter} onChange={(e) => { setRunFilter(e.target.value as CollectionJobType | ''); setRunPage(1); }}>
              <option value="">전체</option>
              <option value="BASIC">기본</option>
              <option value="AI">AI</option>
              <option value="INFRA">인프라</option>
            </select>
          </div>
        </div>
        {runs.isError && <div className="error">{errText(runs.error)}</div>}
        <table className="admin-table">
          <thead><tr><th>#</th><th>종류</th><th>주체</th><th>상태</th><th>시작</th><th>소요</th><th>결과</th></tr></thead>
          <tbody>
            {runs.data?.items.map((r) => (
              <tr key={r.id}>
                <td className="num">{r.id}</td>
                <td>{JOB_LABEL[r.jobType]}</td>
                <td>{TRIGGER_LABEL[r.trigger] ?? r.trigger}{r.requestId ? <span className="muted"> #{r.requestId}</span> : null}</td>
                <td><Badge value={r.status} label={RUN_LABEL[r.status]} /></td>
                <td className="num">{fmtTime(r.startedAt)}</td>
                <td className="num">{r.status === 'RUNNING' ? '…' : fmtDuration(r.durationSeconds)}</td>
                <td>{summaryLine(r)}{r.errorMessage && <div className="small" style={{ color: 'var(--bad)' }}>{r.errorMessage}</div>}</td>
              </tr>
            ))}
            {runs.data && runs.data.items.length === 0 && <tr><td colSpan={7} className="muted">아직 기록이 없어요. 이 기능을 배포한 뒤 첫 수집부터 쌓입니다.</td></tr>}
          </tbody>
        </table>
        {runs.data && <Pager page={runs.data.page} totalPages={runs.data.totalPages} total={runs.data.totalElements} onChange={setRunPage} />}
      </section>

      {requests.data && requests.data.items.length > 0 && (
        <section className="panel">
          <h2 className="panel__title">최근 수동 실행 요청</h2>
          <table className="admin-table">
            <thead><tr><th>#</th><th>종류</th><th>상태</th><th>요청</th><th>시작</th><th>끝</th><th>메모 / 결과</th></tr></thead>
            <tbody>
              {requests.data.items.map((r) => (
                <tr key={r.id}>
                  <td className="num">{r.id}</td>
                  <td>{JOB_LABEL[r.jobType]}</td>
                  <td><Badge value={r.status} label={REQ_LABEL[r.status]} /></td>
                  <td className="num">{fmtTime(r.createdAt)}</td>
                  <td className="num">{fmtTime(r.startedAt)}</td>
                  <td className="num">{fmtTime(r.finishedAt)}</td>
                  <td>{r.note}{r.note && r.resultMessage ? ' · ' : ''}<span className="muted">{r.resultMessage}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </>
  );
}
