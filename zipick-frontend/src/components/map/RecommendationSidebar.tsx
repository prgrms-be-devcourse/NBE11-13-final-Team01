import { Link } from 'react-router-dom';
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
    <div className="map-side">
      <details className="map-side__tools">
        <summary>우선순위 조정</summary>
        <PrioritySliders
          priorities={priorities}
          onChange={setPriorities}
          dirty={dirty}
          saving={save.isPending}
          onSave={() => save.mutate()}
          onReset={() => setPriorities(serverPriorities)}
        />
      </details>

      {query.data && (
        <p className="muted small map-side__summary">
          후보 {query.data.candidateTotal}곳 중 관심 지역 밖·지원조건 미충족 {query.data.excluded}곳 제외 · 추천{' '}
          <strong>{ranked.length}곳</strong>
          {incomplete.length > 0 && ` · 정보 부족 ${incomplete.length}곳`}
          {active && ` · 기준 "${active.name}" v${active.version}`}
        </p>
      )}

      {query.data && <RelaxSuggestions filter={filter} currentCount={ranked.length} />}

      {query.isLoading && <p className="map-side__notice">추천을 계산하는 중…</p>}

      {query.data && ranked.length === 0 && !query.isLoading && (
        <div className="map-side__notice">
          <p>
            {incomplete.length > 0
              ? '관심 지역 공고는 있지만 보증금·임대료가 공고문에만 있어요. 아래 목록에서 공고문을 확인해 보세요.'
              : '조건에 맞는 집이 없어요. 필터를 풀거나 내 조건을 넓혀 보세요.'}
          </p>
          <Link className="btn btn--small" to="/onboarding">
            내 조건 수정하기
          </Link>
        </div>
      )}

      {ranked.map((house, index) => (
        <RecommendationItem key={house.houseId} rank={index + 1} house={house} onSelect={onSelect} onHidden={hide} />
      ))}

      {incomplete.length > 0 && (
        <details className="map-side__tools">
          <summary>정보 부족 · 공고문 확인 필요 ({incomplete.length}곳)</summary>
          <p className="muted small">보증금·임대료나 지역 정보가 없어 점수를 매길 수 없는 집이에요.</p>
          {incomplete.map((house) => (
            <RecommendationItem key={house.houseId} house={house} onSelect={onSelect} onHidden={hide} />
          ))}
        </details>
      )}
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
  // 목록 응답에는 마감일만 있다. 접수 시작일은 상세에서 채워진다.
  const schedule = applySchedule(house.status, null, house.closeDate);
  const mapped = house.lat != null && house.lng != null;
  return (
    <article className="rec-item">
      <button type="button" className="rec-item__main" onClick={() => onSelect(house.houseId)}>
        {rank !== undefined && <span className="rec-item__rank">{rank}</span>}
        <span className="rec-item__body">
          <strong>{house.housingName || house.address}</strong>
          <span className="muted small">
            {house.agencyName ?? house.agencyCode} · {won(house.deposit)} / {won(house.monthlyRent)}
          </span>
          <span className="rec-item__badges">
            <span className={`badge ${schedule.urgent ? 'badge--soon' : ''}`}>{schedule.label}</span>
            {house.eligibility === 'CHECK' && <span className="badge badge--check">지원조건 확인</span>}
            {!mapped && <span className="badge badge--check">지도 표시 불가</span>}
          </span>
          <ReasonList reasons={house.reasons} compact />
        </span>
        <span className="rec-item__score">{house.complete ? `${house.score}점` : '—'}</span>
      </button>
      <div className="rec-item__actions">
        <InterestButton noticeId={house.announcementId} compact />
        <FeedbackButton houseId={house.houseId} onHidden={onHidden} />
      </div>
    </article>
  );
}
