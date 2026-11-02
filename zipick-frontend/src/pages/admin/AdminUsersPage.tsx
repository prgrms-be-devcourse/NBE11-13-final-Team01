import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/admin';
import type { Role } from '../../types/admin';
import { useAuth } from '../../hooks/useAuth';
import { Badge, Pager, errText, fmtTime } from './adminUi';
import { Trail } from './AdminActivityPage';

const ROLE_LABEL: Record<Role, string> = { ROLE_USER: '일반', ROLE_ADMIN: '운영자' };

/**
 * 회원 관리. 목록(온보딩·알림·관심 공고), 상세(기본 정보·희망 조건·알림 설정·알림 내역), 권한 변경,
 * 가입 이메일 인증 지원("메일이 안 와요" → 다시 보내기 / 기록 초기화).
 * 탈퇴 처리는 연관 데이터(관심·추천·알림)가 많아 아직 화면에 두지 않았다.
 */
export default function AdminUsersPage() {
  const qc = useQueryClient();
  const { user: me } = useAuth();
  const [q, setQ] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  const users = useQuery({ queryKey: ['admin', 'users', keyword, page], queryFn: () => adminApi.users(keyword, page, 30), retry: false });
  const detail = useQuery({ queryKey: ['admin', 'user', selectedId], queryFn: () => adminApi.user(selectedId!), enabled: selectedId != null, retry: false });
  const verifications = useQuery({ queryKey: ['admin', 'verifications'], queryFn: adminApi.verifications, retry: false });
  const trail = useQuery({ queryKey: ['admin', 'trail', selectedId], queryFn: () => adminApi.trail({ userId: selectedId!, limit: 100 }), enabled: selectedId != null, retry: false });

  const changeRole = useMutation({
    mutationFn: (p: { id: number; role: Role }) => adminApi.changeRole(p.id, p.role),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin', 'users'] }); qc.invalidateQueries({ queryKey: ['admin', 'user'] }); },
    onError: (e) => setMsg(errText(e)),
  });
  const resend = useMutation({ mutationFn: adminApi.resendVerification, onSuccess: () => { setMsg('인증 코드를 다시 보냈어요.'); qc.invalidateQueries({ queryKey: ['admin', 'verifications'] }); }, onError: (e) => setMsg(errText(e)) });
  const reset = useMutation({ mutationFn: adminApi.resetVerification, onSuccess: () => { setMsg('인증 기록을 지웠어요. 사용자가 처음부터 다시 받을 수 있어요.'); qc.invalidateQueries({ queryKey: ['admin', 'verifications'] }); }, onError: (e) => setMsg(errText(e)) });

  if (users.isError) return <div className="error">{errText(users.error)}</div>;
  const d = detail.data;

  return (
    <>
      <div className="admin__head"><h1>회원</h1></div>
      {msg && <p className="small" style={{ margin: 0 }}>{msg}</p>}

      <section className="panel">
        <div className="admin__head">
          <h2 className="panel__title" style={{ margin: 0 }}>회원 목록</h2>
          <form className="admin__toolbar" onSubmit={(e) => { e.preventDefault(); setKeyword(q.trim()); setPage(1); }}>
            <input type="search" placeholder="이메일" value={q} onChange={(e) => setQ(e.target.value)} />
            <button type="submit" className="btn btn--small">검색</button>
          </form>
        </div>
        <div className="admin-split">
          <div>
            <table className="admin-table">
              <thead><tr><th>#</th><th>이메일</th><th>권한</th><th>가입</th><th>온보딩</th><th>알림</th><th>관심</th></tr></thead>
              <tbody>
                {users.data?.items.map((u) => (
                  <tr key={u.userId} className={selectedId === u.userId ? 'is-selected' : ''} onClick={() => setSelectedId(u.userId)} style={{ cursor: 'pointer' }}>
                    <td className="num">{u.userId}</td>
                    <td>{u.email}</td>
                    <td><Badge value={u.role} label={ROLE_LABEL[u.role]} /></td>
                    <td className="num small">{fmtTime(u.createdAt)}</td>
                    <td>{u.onboarded ? <span className="ok-text small">완료</span> : <span className="muted small">{u.hasProfile ? '조건 미입력' : u.hasPreference ? '기본 정보 미입력' : '미시작'}</span>}</td>
                    <td className="small">{u.notificationEnabled ? '켬' : '끔'}</td>
                    <td className="num">{u.interestCount}</td>
                  </tr>
                ))}
                {users.data && users.data.items.length === 0 && <tr><td colSpan={7} className="muted">회원이 없어요.</td></tr>}
              </tbody>
            </table>
            {users.data && <Pager page={users.data.page} totalPages={users.data.totalPages} total={users.data.totalElements} onChange={setPage} />}
          </div>
          {selectedId != null && (
            <div className="admin-detail">
              {detail.isError && <div className="error">{errText(detail.error)}</div>}
              {d && (
                <>
                  <h3>{d.user.email} <Badge value={d.user.role} label={ROLE_LABEL[d.user.role]} /></h3>
                  <div className="admin__toolbar">
                    <span className="small">권한:</span>
                    {(['ROLE_USER', 'ROLE_ADMIN'] as Role[]).filter((r) => r !== d.user.role).map((r) => (
                      <button key={r} type="button" className="btn btn--small btn--ghost" disabled={changeRole.isPending || (me?.userId === d.user.userId)}
                        onClick={() => { if (confirm(`${d.user.email} 을(를) ${ROLE_LABEL[r]} 로 바꿀까요?`)) changeRole.mutate({ id: d.user.userId, role: r }); }}>
                        {ROLE_LABEL[r]}로
                      </button>
                    ))}
                    {me?.userId === d.user.userId && <span className="muted small">자기 자신은 못 바꿔요</span>}
                  </div>
                  <h3>기본 정보</h3>
                  {d.profile ? (
                    <dl>
                      <dt>생년월일</dt><dd>{d.profile.birthDate}</dd>
                      <dt>혼인</dt><dd>{d.profile.married ? '기혼' : '미혼'}</dd>
                      <dt>가구원</dt><dd>{d.profile.householdSize}명</dd>
                      <dt>무주택</dt><dd>{d.profile.homeless ? '예' : '아니오'}</dd>
                      <dt>월소득</dt><dd>{d.profile.monthlyIncome ?? '-'}</dd>
                      <dt>자산</dt><dd>{d.profile.totalAssets ?? '-'}</dd>
                    </dl>
                  ) : <p className="muted">미입력</p>}
                  <h3>희망 조건</h3>
                  {d.preference ? (
                    <dl>
                      <dt>지역</dt><dd>{d.preference.regions?.map((r) => r.name ?? r.code).join(', ') || '-'}</dd>
                      <dt>보증금</dt><dd>{d.preference.depositMax ?? '-'}</dd>
                      <dt>월세</dt><dd>{d.preference.rentMax ?? '-'}</dd>
                      <dt>월 예산</dt><dd>{d.preference.monthlyBudget ?? '-'}</dd>
                    </dl>
                  ) : <p className="muted">미입력</p>}
                  <h3>알림 설정</h3>
                  {d.notificationSetting ? (
                    <dl>
                      <dt>알림</dt><dd>{d.notificationSetting.enabled ? '켬' : '끔'}</dd>
                      <dt>이메일</dt><dd>{d.notificationSetting.email ?? '-'}</dd>
                      <dt>슬랙</dt><dd>{d.notificationSetting.slackConfigured ? '설정됨' : '-'}</dd>
                      <dt>광고 동의</dt><dd>{d.notificationSetting.marketingConsent ? `예 (${fmtTime(d.notificationSetting.marketingConsentedAt)})` : '아니오'}</dd>
                      <dt>마감 알림</dt><dd>D-{d.notificationSetting.deadlineDays}</dd>
                    </dl>
                  ) : <p className="muted">설정 없음</p>}
                  <h3>최근 행동 (IP·화면·클릭)</h3>
                  {trail.data && trail.data.length > 0 && (
                    <p className="small" style={{ margin: 0 }}>
                      마지막 IP <b>{[...trail.data].reverse().find((e) => e.ip)?.ip ?? '-'}</b> · 마지막 활동 {fmtTime(trail.data[trail.data.length - 1].occurredAt)}
                    </p>
                  )}
                  {trail.data && <Trail events={trail.data.slice(-40)} />}
                  <h3>알림 내역 {d.notifications.length > 0 && `(${d.notifications.length})`}</h3>
                  {d.notificationError && <p className="muted small">{d.notificationError}</p>}
                  {d.notifications.length > 0 && (
                    <ul className="notif__history">
                      {d.notifications.slice(0, 20).map((n) => (
                        <li key={n.id}>
                          <Badge value={n.status} />
                          <span className="notif__title" title={n.title}>{n.title}</span>
                          <span className="muted small">{fmtTime(n.createdAt)}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
              <div className="panel__actions"><button type="button" className="btn btn--ghost" onClick={() => setSelectedId(null)}>닫기</button></div>
            </div>
          )}
        </div>
      </section>

      <section className="panel">
        <h2 className="panel__title">가입 이메일 인증 지원 <span className="muted small">(가입을 끝내지 않은 인증 시도)</span></h2>
        <p className="muted small">"메일이 안 와요" 문의: 스팸함 안내 → <b>다시 보내기</b>(60초 간격·하루 10회 규칙 적용) → 그래도 막히면 <b>기록 초기화</b>로 횟수를 풀어 주세요.</p>
        {verifications.isError && <div className="error">{errText(verifications.error)}</div>}
        <table className="admin-table">
          <thead><tr><th>이메일</th><th>보낸 시각</th><th>만료</th><th>틀림</th><th>오늘 전송</th><th>인증</th><th></th></tr></thead>
          <tbody>
            {verifications.data?.map((v) => (
              <tr key={v.id}>
                <td>{v.email}</td>
                <td className="num small">{fmtTime(v.sentAt)}</td>
                <td className="num small">{v.expired ? <span className="muted">만료</span> : fmtTime(v.expiresAt)}</td>
                <td className="num">{v.attempts}</td>
                <td className="num">{v.sendCount}</td>
                <td className="small">{v.verifiedAt ? <span className="ok-text">완료 (가입 전)</span> : <span className="muted">대기</span>}</td>
                <td className="actions">
                  <button type="button" className="btn btn--small btn--ghost" disabled={resend.isPending} onClick={() => resend.mutate(v.email)}>다시 보내기</button>
                  <button type="button" className="btn btn--small btn--ghost" disabled={reset.isPending} onClick={() => { if (confirm(`${v.email} 의 인증 기록을 지울까요?`)) reset.mutate(v.email); }}>기록 초기화</button>
                </td>
              </tr>
            ))}
            {verifications.data && verifications.data.length === 0 && <tr><td colSpan={7} className="muted">진행 중인 인증이 없어요.</td></tr>}
          </tbody>
        </table>
      </section>
    </>
  );
}
