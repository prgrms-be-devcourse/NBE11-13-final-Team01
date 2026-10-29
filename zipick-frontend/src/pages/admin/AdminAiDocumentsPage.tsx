import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/admin';
import type { AiDocumentIssue, NormalizationStatus } from '../../types/admin';
import { Badge, Pager, errText, fmtTime } from './adminUi';

const REASON_LABEL: Record<string, string> = { incomplete: '일부 누락', empty: '0세대', exception: '오류' };
const REASON_HELP: Record<string, string> = {
  incomplete: 'PDF 의 세대 수(expected)보다 적게 추출됨. 표가 여러 쪽에 걸쳐 있거나 양식이 다른 경우가 많아요.',
  empty: '추출 결과가 0세대. 전세·매입임대처럼 세대 표가 없는 공고가 대부분이에요.',
  exception: 'Gemini 호출·파싱 중 오류. detail 의 예외 메시지를 보세요.',
};

/**
 * AI 미완료 문서 검토. 수집기가 Gemini PDF 추출에 실패한 첨부를 ai_document_issue 에 남기고,
 * 운영자는 여기서 사유를 보고 "확인함" 으로 접는다. 같은 문서가 성공하면 행이 자동으로 사라진다.
 * 세대 정보의 수동 입력은 공급정보 구조가 깊어 아직 화면에 두지 않았다 (DB 에서 직접).
 */
export default function AdminAiDocumentsPage() {
  const qc = useQueryClient();
  const [acked, setAcked] = useState(false);
  const [reason, setReason] = useState('');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<AiDocumentIssue | null>(null);
  const [note, setNote] = useState('');
  const [jobStatus, setJobStatus] = useState<NormalizationStatus | ''>('RETRY');
  const [jobPage, setJobPage] = useState(1);
  const [msg, setMsg] = useState<string | null>(null);

  const issues = useQuery({ queryKey: ['admin', 'issues', acked, reason, page], queryFn: () => adminApi.issues(acked, reason || undefined, page, 50), retry: false });
  const jobs = useQuery({ queryKey: ['admin', 'njobs', jobStatus, jobPage], queryFn: () => adminApi.normalizationJobs(jobStatus || undefined, jobPage, 50), retry: false });

  const refresh = () => { qc.invalidateQueries({ queryKey: ['admin', 'issues'] }); qc.invalidateQueries({ queryKey: ['admin', 'overview'] }); };
  const ack = useMutation({ mutationFn: (i: AiDocumentIssue) => adminApi.acknowledge(i.id, note.trim() || undefined), onSuccess: () => { setSelected(null); setNote(''); refresh(); }, onError: (e) => setMsg(errText(e)) });
  const reopen = useMutation({ mutationFn: (id: number) => adminApi.reopen(id), onSuccess: refresh, onError: (e) => setMsg(errText(e)) });
  const requeue = useMutation({ mutationFn: adminApi.requeue, onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'njobs'] }), onError: (e) => setMsg(errText(e)) });

  if (issues.isError) return <div className="error">{errText(issues.error)}</div>;

  return (
    <>
      <div className="admin__head"><h1>AI 문서 검토</h1></div>
      {msg && <div className="error">{msg}</div>}

      <section className="panel">
        <div className="admin__head">
          <h2 className="panel__title" style={{ margin: 0 }}>Gemini 추출 미완료 문서</h2>
          <div className="admin__toolbar">
            <select value={reason} onChange={(e) => { setReason(e.target.value); setPage(1); }}>
              <option value="">사유 전체</option>
              <option value="incomplete">일부 누락</option>
              <option value="empty">0세대</option>
              <option value="exception">오류</option>
            </select>
            <label className="small"><input type="checkbox" checked={acked} onChange={(e) => { setAcked(e.target.checked); setPage(1); }} /> 확인한 것 보기</label>
          </div>
        </div>
        {reason && <p className="muted small">{REASON_HELP[reason]}</p>}
        <div className="admin-split">
          <table className="admin-table">
            <thead><tr><th>기관</th><th>공고 / 파일</th><th>사유</th><th>세대</th><th>횟수</th><th>마지막</th><th></th></tr></thead>
            <tbody>
              {issues.data?.items.map((i) => (
                <tr key={i.id} className={selected?.id === i.id ? 'is-selected' : ''}>
                  <td>{i.agency ?? '-'}</td>
                  <td>
                    <span className="clip" title={i.noticeTitle ?? ''}>{i.noticeTitle ?? '-'}</span>
                    <span className="clip muted small" title={i.fileName ?? ''}>{i.fileName ?? i.documentKey}</span>
                  </td>
                  <td><Badge value={i.reason === 'exception' ? 'failed' : i.reason === 'empty' ? 'pending' : 'partial'} label={REASON_LABEL[i.reason] ?? i.reason} /></td>
                  <td className="num">{i.parsedUnits ?? '-'} / {i.expectedUnits ?? '-'}</td>
                  <td className="num">{i.occurrences}</td>
                  <td className="num">{fmtTime(i.lastSeenAt)}</td>
                  <td className="actions">
                    <button type="button" className="btn btn--small btn--ghost" onClick={() => { setSelected(i); setNote(i.adminNote ?? ''); }}>보기</button>
                    {i.acknowledged && <button type="button" className="btn btn--small btn--ghost" onClick={() => reopen.mutate(i.id)}>다시 열기</button>}
                  </td>
                </tr>
              ))}
              {issues.data && issues.data.items.length === 0 && <tr><td colSpan={7} className="muted">{acked ? '확인한 문서가 없어요.' : '미완료 문서가 없어요. 이 기능을 배포한 뒤 첫 AI 수집부터 쌓입니다.'}</td></tr>}
            </tbody>
          </table>
          {selected && (
            <div className="admin-detail">
              <h3>{selected.noticeTitle ?? selected.documentKey}</h3>
              <dl>
                <dt>기관</dt><dd>{selected.agency ?? '-'}</dd>
                <dt>파일</dt><dd>{selected.fileName ?? '-'}</dd>
                <dt>문서 키</dt><dd style={{ wordBreak: 'break-all' }}>{selected.documentKey}</dd>
                <dt>사유</dt><dd>{REASON_LABEL[selected.reason] ?? selected.reason} — {REASON_HELP[selected.reason]}</dd>
                <dt>세대</dt><dd>추출 {selected.parsedUnits ?? '-'} / 기대 {selected.expectedUnits ?? '-'}</dd>
                <dt>처음</dt><dd>{fmtTime(selected.firstSeenAt)}</dd>
                <dt>마지막</dt><dd>{fmtTime(selected.lastSeenAt)} ({selected.occurrences}회)</dd>
              </dl>
              {selected.detail && <pre>{selected.detail}</pre>}
              <label className="small">메모
                <input type="text" value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} style={{ width: '100%', font: 'inherit', padding: 6, marginTop: 4 }} placeholder="예: 전세 공고라 세대 표 없음" />
              </label>
              <div className="panel__actions">
                {selected.acknowledged
                  ? <button type="button" className="btn btn--ghost" onClick={() => reopen.mutate(selected.id)}>다시 열기</button>
                  : <button type="button" className="btn btn--primary" disabled={ack.isPending} onClick={() => ack.mutate(selected)}>확인함 (목록에서 접기)</button>}
                <button type="button" className="btn btn--ghost" onClick={() => setSelected(null)}>닫기</button>
              </div>
              <p className="muted small">다음 AI 수집에서 같은 문서가 성공하면 이 항목은 자동으로 사라져요.</p>
            </div>
          )}
        </div>
        {issues.data && <Pager page={issues.data.page} totalPages={issues.data.totalPages} total={issues.data.totalElements} onChange={setPage} />}
      </section>

      <section className="panel">
        <div className="admin__head">
          <h2 className="panel__title" style={{ margin: 0 }}>AI 정규화 작업</h2>
          <div className="admin__toolbar">
            <select value={jobStatus} onChange={(e) => { setJobStatus(e.target.value as NormalizationStatus | ''); setJobPage(1); }}>
              <option value="">전체</option>
              <option value="PENDING">대기</option>
              <option value="RETRY">재시도</option>
              <option value="SUCCESS">성공</option>
            </select>
          </div>
        </div>
        <p className="muted small">추출된 세대 정보를 Gemini 가 검수·정규화하는 작업. 503 등으로 미뤄진 것은 RETRY 로 남고 다음 AI 수집에서 다시 시도해요.</p>
        {jobs.isError && <div className="error">{errText(jobs.error)}</div>}
        <table className="admin-table">
          <thead><tr><th>#</th><th>출처</th><th>공고</th><th>상태</th><th>시도</th><th>다음 시도</th><th>마지막 오류</th><th></th></tr></thead>
          <tbody>
            {jobs.data?.items.map((j) => (
              <tr key={j.id}>
                <td className="num">{j.id}</td>
                <td>{j.source}</td>
                <td><span className="clip" title={j.noticeTitle ?? j.documentId}>{j.noticeTitle ?? j.documentId}</span></td>
                <td><Badge value={j.status} /></td>
                <td className="num">{j.attemptCount}</td>
                <td className="num">{fmtTime(j.nextRetryAt)}</td>
                <td><span className="clip muted" title={j.lastError ?? ''}>{j.lastError ?? '-'}</span></td>
                <td className="actions">
                  {j.status !== 'PENDING' && <button type="button" className="btn btn--small btn--ghost" onClick={() => requeue.mutate(j.id)}>다시 시도</button>}
                </td>
              </tr>
            ))}
            {jobs.data && jobs.data.items.length === 0 && <tr><td colSpan={8} className="muted">해당 작업이 없어요.</td></tr>}
          </tbody>
        </table>
        {jobs.data && <Pager page={jobs.data.page} totalPages={jobs.data.totalPages} total={jobs.data.totalElements} onChange={setJobPage} />}
      </section>
    </>
  );
}
