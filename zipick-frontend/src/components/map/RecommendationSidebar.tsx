import { Link } from 'react-router-dom';
import { useId, useState } from 'react';
import FeedbackButton from '../FeedbackButton';
import InterestButton from '../InterestButton';
import PrioritySliders from '../PrioritySliders';
import ReasonList from '../ReasonList';
import RelaxSuggestions from '../RelaxSuggestions';
import { openGate } from '../../hooks/useAuthGate';
import type { useRecommendationBoard } from '../../hooks/useRecommendationBoard';
import type { OnboardingStatus } from '../../hooks/useOnboardingStatus';
import type { HouseFilter, RecommendedHouse } from '../../types';
import { applySchedule, won } from '../../utils/format';
import styles from './RecommendationSidebar.module.css';

interface Props {
  signedIn: boolean;
  onboarding: OnboardingStatus;
  filter: HouseFilter;
  board: ReturnType<typeof useRecommendationBoard>;
  onSelect: (houseId: number) => void;
}

/**
 * 추천 모드의 우측 패널. 기존 추천 화면의 기능을 지도 옆으로 옮겨 놓은 것이다.
 * 우선순위 조정 · 조건 완화 제안 · 추천 목록 · 피드백 · 정보 부족 묶음을 모두 포함한다.
 */
export default function RecommendationSidebar({ signedIn, onboarding, filter, board, onSelect }: Props) {
  if (!signedIn) {
    return (
      <div className="map-side__notice">
        <p>로그인하면 내 조건에 맞는 집만 골라 지도에 표시해 드려요.</p>
        <button type="button" className="btn btn--small" onClick={() => openGate('LOGIN')}>
          로그인하기
        </button>
      </div>
    );
  }
  if (onboarding === 'LOADING') return <p className="map-side__notice">내 조건을 확인하는 중…</p>;
  if (onboarding === 'ERROR') {
    return <p className="map-side__notice">내 조건을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.</p>;
  }
  if (onboarding === 'INCOMPLETE') {
    return (
      <div className="map-side__notice">
        <p>내 조건을 입력하면 맞춤 추천을 받을 수 있어요.</p>
        <button type="button" className="btn btn--small" onClick={() => openGate('ONBOARDING')}>
          내 조건 입력하기
        </button>
      </div>
    );
  }

  const { query, active, ranked, incomplete, priorities, setPriorities, serverPriorities, dirty, save, hide } = board;

  return (
    <div className={styles.sidebar}>
      {query.data && <header className={styles.heading}><h2>내 조건에 맞는 집 <strong>{ranked.length}곳</strong></h2><p>입력한 조건과 우선순위를 기준으로 정렬했어요.</p></header>}
      <nav className={styles.links} aria-label="추천 조건 설정"><Link to="/onboarding">내 조건 수정 ↗</Link><Link to="/tune" title="두 집 비교 AHP 설문으로 선호도 설정">취향 찾기 ↗</Link></nav>
      <details className={styles.accordion}>
        <summary><span aria-hidden="true">☷</span> 우선순위 조정</summary>
        <p className={styles.help}>현재 가중치를 직접 조정해요. 두 집을 비교하는 취향 찾기와는 별도 설정이에요.</p>
        <PrioritySliders
          showTuneLink={false}
          priorities={priorities}
          onChange={setPriorities}
          dirty={dirty}
          saving={save.isPending}
          onSave={() => save.mutate()}
          onReset={() => setPriorities(serverPriorities)}
        />
        {save.isError && <p className="error" role="alert">{save.error.message}</p>}
      </details>

      {query.isLoading && <p className="map-side__notice">추천을 계산하는 중…</p>}

      {query.data && ranked.length === 0 && !query.isLoading && (
        <div className="map-side__notice">
          <p>
            {incomplete.length > 0
              ? '관심 지역 공고는 있지만 보증금·임대료가 공고문에만 있어요. 아래 목록에서 공고문을 확인해 보세요.'
              : '조건에 맞는 집이 없어요. 필터를 풀거나 내 조건을 넓혀 보세요.'}
          </p>
        </div>
      )}

      {query.data && <div className={styles.resultsHead}><h3>추천 순서</h3><span>점수가 높은 순</span></div>}
      {ranked.map((house, index) => (
        <RecommendationItem key={house.houseId} rank={index + 1} house={house} onSelect={onSelect} onHidden={hide} />
      ))}

      {query.isError && <p className="error" role="alert">추천을 불러오지 못했어요. <button type="button" onClick={() => void query.refetch()}>다시 시도</button></p>}

      {incomplete.length > 0 && (
        <details className={styles.accordion}>
          <summary>정보 부족 · 공고문 확인 필요 ({incomplete.length}곳)</summary>
          <p className="muted small">보증금·임대료나 지역 정보가 없어 점수를 매길 수 없는 집이에요.</p>
          {incomplete.map((house) => (
            <RecommendationItem key={house.houseId} house={house} onSelect={onSelect} onHidden={hide} />
          ))}
        </details>
      )}
      {query.data && <footer className={styles.statistics}>
        <RelaxSuggestions filter={filter} currentCount={ranked.length} />
        <details><summary>추천 집계 정보</summary><p>후보 {query.data.candidateTotal}곳 · 관심 지역 밖·지원조건 미충족 {query.data.excluded}곳 제외</p><p>추천 {ranked.length}곳 · 정보 부족 {incomplete.length}곳</p>{active && <p>기준 "{active.name}" v{active.version}</p>}</details>
      </footer>}
    </div>
  );
}

interface ItemProps {
  rank?: number;
  house: RecommendedHouse;
  onSelect: (houseId: number) => void;
  onHidden: (houseId: number) => void;
}

function RecommendationItem({ rank, house, onSelect, onHidden }: ItemProps) {
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const evidenceId = useId();
  // 목록 응답에는 마감일만 있다. 접수 시작일은 상세에서 채워진다.
  const schedule = applySchedule(house.status, null, house.closeDate);
  const mapped = house.lat != null && house.lng != null;
  return (
    <article className={styles.card}>
      <button type="button" className={styles.select} onClick={() => onSelect(house.houseId)}>
        {rank !== undefined && <span className={styles.rank}>{rank}</span>}
        <span className={styles.body}>
          <strong>{house.housingName || house.address}</strong>
          {(house.agencyName || house.agencyCode) && <span className={styles.provider}>{house.agencyName || house.agencyCode}</span>}
        </span>
        <span className={styles.score}>{house.complete ? <>{house.score}<small>점</small></> : '—'}</span>
      </button>
      <div className={styles.content}>
          {rank === undefined && house.title && <p className={styles.provider}>{house.title}</p>}
          {(house.deposit != null || house.monthlyRent != null) && <p className={styles.prices}>{house.deposit != null && <span>보증금 <strong>{won(house.deposit)}</strong></span>}{house.monthlyRent != null && <span>월 <strong>{won(house.monthlyRent)}</strong></span>}</p>}
          <span className="rec-item__badges">
            <span className={`badge ${schedule.urgent ? 'badge--soon' : ''}`}>{schedule.label}</span>
            {house.eligibility === 'CHECK' && <span className="badge badge--check">지원조건 확인</span>}
            {!mapped && <span className="badge badge--check">지도 표시 불가</span>}
          </span>
          <ReasonList reasons={house.reasons.slice(0, 3)} />
      </div>
      <div className={styles.cardFoot}>
        <button type="button" className={styles.reasonToggle} aria-expanded={evidenceOpen} aria-controls={evidenceId} onClick={() => setEvidenceOpen(!evidenceOpen)}>{evidenceOpen ? '근거 접기' : '추천 근거 보기'}<span aria-hidden="true">{evidenceOpen ? '⌃' : '⌄'}</span></button>
      <div className={styles.actions}>
        <InterestButton noticeId={house.announcementId} shortLabel />
        <FeedbackButton houseId={house.houseId} onHidden={onHidden} />
        {rank === undefined && house.detailUrl && <a href={house.detailUrl} target="_blank" rel="noreferrer">공고 원문 ↗</a>}
      </div>
      </div>
      <div id={evidenceId} className={styles.evidence} hidden={!evidenceOpen}>
        <p className={styles.evidenceTitle}>조건별 추천 근거</p>
        <ReasonList reasons={house.reasons} />
        {!house.reasons.length && <p className={styles.help}>제공된 상세 추천 근거가 없어요.</p>}
      </div>
    </article>
  );
}
