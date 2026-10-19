import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { recommendationApi } from '../api/recommendation';
import ReasonList from '../components/ReasonList';
import InterestButton from '../components/InterestButton';
import { SCORE_ITEMS } from '../types';
import { ITEM_LABEL, STATUS_LABEL, area, dDay, housingLabel, regionText, rentalLabel, won } from '../utils/format';

/** 집 하나의 추천 점수와 근거. 세부 정보·로드뷰·인프라 패널은 다른 담당자가 붙인다. */
export default function HouseDetailPage() {
  const { houseId } = useParams();
  const q = useQuery({
    queryKey: ['house-score', houseId],
    queryFn: () => recommendationApi.one(Number(houseId)),
    enabled: !!houseId,
  });

  if (q.isLoading) return <p className="muted">불러오는 중…</p>;
  if (q.isError || !q.data) return <div className="error">{(q.error as Error)?.message ?? '불러오지 못했어요'}</div>;
  const h = q.data;
  const d = dDay(h.closeDate);

  return (
    <div className="detail">
      <Link to="/recommendations" className="back">
        ← 추천 목록
      </Link>
      <header className="detail__head">
        <h1>{h.housingName || h.address}</h1>
        <p className="card__addr">{regionText(h)}{h.housingName && ` · ${h.address}`}</p>
        <p className="muted">{h.title}</p>
        <div className="badges">
          <span className={`badge badge--${h.status.toLowerCase()}`}>{STATUS_LABEL[h.status]}</span>
          {d && <span className="badge badge--soon">{d}</span>}
          <span className="badge">{h.agencyName ?? h.agencyCode}</span>
          <span className="badge">{rentalLabel(h)}</span>
          {h.detailUrl && (
            <a className="badge" href={h.detailUrl} target="_blank" rel="noreferrer">
              공고문 보기 ↗
            </a>
          )}
          <InterestButton noticeId={h.announcementId} />
        </div>
      </header>

      <section className="score-box">
        <div className="score-box__score">
          {h.complete ? (
            <>추천도 <strong>{h.score}</strong>점</>
          ) : (
            <span className="badge badge--check">정보 부족 · 점수 없음 (참고값 {h.score}점)</span>
          )}
          {h.eligibility === 'CHECK' && <span className="badge badge--check">지원조건 확인 필요</span>}
          {h.excludedBy && (
            <span className="badge badge--fail">
              추천 제외 · {h.excludedBy === 'ELIGIBILITY' ? '지원조건 미충족' : `'꼭' 조건(${ITEM_LABEL[h.excludedBy as keyof typeof ITEM_LABEL] ?? h.excludedBy}) 불일치`}
            </span>
          )}
        </div>
        <ReasonList reasons={h.reasons} />
        <table className="items">
          <thead>
            <tr>
              <th>항목</th>
              <th>점수 (0~1)</th>
            </tr>
          </thead>
          <tbody>
            {SCORE_ITEMS.map((i) => (
              <tr key={i}>
                <td>{ITEM_LABEL[i]}</td>
                <td>{h.itemScores[i] == null ? '계산 제외' : h.itemScores[i]!.toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="facts">
        <dl className="card__facts">
          <div><dt>보증금</dt><dd>{won(h.deposit)}</dd></div>
          <div><dt>월 임대료</dt><dd>{won(h.monthlyRent)}</dd></div>
          <div><dt>월 환산 비용</dt><dd>{h.monthlyCost != null ? `${h.monthlyCost}만 원` : '—'}</dd></div>
          <div><dt>전용면적</dt><dd>{area(h.exclusiveArea)}</dd></div>
          <div><dt>방 수</dt><dd>{h.roomCount != null ? `${h.roomCount}개` : '정보 없음'}</dd></div>
          <div><dt>주택 유형</dt><dd>{housingLabel(h)}</dd></div>
          <div><dt>준공</dt><dd>{h.buildYear ?? '정보 없음'}</dd></div>
          <div><dt>층수</dt><dd>{h.floor ?? '정보 없음'}</dd></div>
        </dl>
        <p className="muted small">최종 자격은 공고문으로 확인하세요. 점수는 참고용입니다.</p>
      </section>
    </div>
  );
}
