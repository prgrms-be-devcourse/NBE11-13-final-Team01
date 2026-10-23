import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { getMapHousingDetail } from '../api/mapHousing';
import { recommendationApi } from '../api/recommendation';
import HousingDetail from '../components/HousingDetail';
import ReasonList from '../components/ReasonList';
import { useAuth } from '../hooks/useAuth';
import { openGate } from '../hooks/useAuthGate';
import { useOnboardingStatus, type OnboardingStatus } from '../hooks/useOnboardingStatus';
import { SCORE_ITEMS, type RecommendedHouse } from '../types';
import { ITEM_LABEL, won } from '../utils/format';

/**
 * 공고 상세 페이지. 공개 영역은 지도 우측 패널과 **같은 컴포넌트**(HousingDetail)를 써서
 * 비회원과 회원이 보는 기본 정보가 어긋나지 않게 한다.
 * 추천 점수·근거만 로그인 + 내 조건 입력을 마쳤을 때 덧붙인다.
 *
 * houseId(목록·추천) == housingId(지도 상세) == housing.id 다.
 * 백엔드 HouseSummaryResponse.from / RecommendedHouseResponse 가 둘 다 Candidate.housingId() 를 넣는다.
 */
export default function HouseDetailPage() {
  const { houseId } = useParams();
  const id = Number(houseId);
  const valid = Number.isFinite(id) && id > 0;

  const { user } = useAuth();
  const { status: onboarding } = useOnboardingStatus();
  const scored = !!user && onboarding === 'COMPLETE';

  const detailQuery = useQuery({
    queryKey: ['map-housing', id],
    queryFn: () => getMapHousingDetail(id),
    enabled: valid,
  });
  const scoreQuery = useQuery({
    queryKey: ['house-score', id],
    queryFn: () => recommendationApi.one(id),
    enabled: valid && scored,
    retry: false,
  });

  if (!valid) return <div className="error">잘못된 주소예요.</div>;
  if (detailQuery.isLoading) return <p className="muted">불러오는 중…</p>;

  if (detailQuery.isError || !detailQuery.data) {
    const error = detailQuery.error as ApiError | null;
    return (
      <div className="detail">
        <Link to="/map" className="back">
          ← 지도로
        </Link>
        <div className="empty">
          <h2>공고 정보를 불러오지 못했어요</h2>
          <p>
            {error?.code === 'HOUSE_NOT_FOUND'
              ? '접수가 끝났거나 취소된 공고일 수 있어요.'
              : (error?.message ?? '잠시 후 다시 시도해 주세요.')}
          </p>
          <Link className="btn" to="/map">
            지도로 돌아가기
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="detail detail--housing">
      <Link to={`/map?housing=${id}`} className="back">
        ← 지도에서 보기
      </Link>
      <HousingDetail
        detail={detailQuery.data}
        score={<ScoreSection signedIn={!!user} onboarding={onboarding} query={scoreQuery} />}
      />
    </div>
  );
}

interface ScoreProps {
  signedIn: boolean;
  onboarding: OnboardingStatus;
  query: ReturnType<typeof useQuery<RecommendedHouse, Error>>;
}

/** 개인화 영역. 로그인·온보딩이 안 됐으면 점수 대신 안내를 보여 주고 공통 팝업으로 이어 준다. */
function ScoreSection({ signedIn, onboarding, query }: ScoreProps) {
  if (!signedIn) {
    return (
      <Teaser
        title="로그인하면 추천 점수를 볼 수 있어요"
        desc="내 조건에 이 집이 얼마나 맞는지 점수와 근거로 알려 드려요."
        action="로그인하기"
        onClick={() => openGate('LOGIN')}
      />
    );
  }
  if (onboarding === 'LOADING') return <p className="muted">내 조건을 확인하는 중…</p>;
  if (onboarding === 'INCOMPLETE') {
    return (
      <Teaser
        title="내 조건을 입력하면 추천 점수를 볼 수 있어요"
        desc="나이·소득과 희망 지역·예산을 알려 주시면 이 집의 점수를 매겨 드려요."
        action="내 조건 입력하기"
        onClick={() => openGate('ONBOARDING')}
      />
    );
  }
  // 조건 조회 실패(ERROR)는 공개 정보까지 가리지 않도록 조용히 넘긴다.
  if (onboarding !== 'COMPLETE') return null;

  if (query.isLoading) return <p className="muted">추천 점수를 계산하는 중…</p>;
  if (query.isError || !query.data) {
    return <p className="muted small">이 집은 지금 추천 후보에 없어 점수를 매기지 않았어요.</p>;
  }

  const house = query.data;
  return (
    <section className="score-box">
      <div className="score-box__score">
        {house.complete ? (
          <>
            추천도 <strong>{house.score}</strong>점
          </>
        ) : (
          <span className="badge badge--check">정보 부족 · 점수 없음 (참고값 {house.score}점)</span>
        )}
        {house.eligibility === 'CHECK' && <span className="badge badge--check">지원조건 확인 필요</span>}
        {house.excludedBy && (
          <span className="badge badge--fail">
            추천 제외 ·{' '}
            {house.excludedBy === 'ELIGIBILITY'
              ? '지원조건 미충족'
              : `꼭 맞춰야 할 조건(${ITEM_LABEL[house.excludedBy as keyof typeof ITEM_LABEL] ?? house.excludedBy}) 불일치`}
          </span>
        )}
      </div>
      <ReasonList reasons={house.reasons} />
      <dl className="card__facts score-box__cost">
        <div><dt>보증금</dt><dd>{won(house.deposit)}</dd></div>
        <div><dt>월 임대료</dt><dd>{won(house.monthlyRent)}</dd></div>
        <div><dt>월 환산</dt><dd>{house.monthlyCost != null ? `${house.monthlyCost}만 원` : '—'}</dd></div>
      </dl>
      <table className="items">
        <thead>
          <tr>
            <th>항목</th>
            <th>점수 (0~1)</th>
          </tr>
        </thead>
        <tbody>
          {SCORE_ITEMS.map((item) => (
            <tr key={item}>
              <td>{ITEM_LABEL[item]}</td>
              <td>{house.itemScores[item] == null ? '계산 제외' : house.itemScores[item]!.toFixed(2)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function Teaser({ title, desc, action, onClick }: { title: string; desc: string; action: string; onClick: () => void }) {
  return (
    <section className="score-teaser">
      <div>
        <strong>{title}</strong>
        <p className="muted small">{desc}</p>
      </div>
      <button type="button" className="btn btn--small" onClick={onClick}>
        {action}
      </button>
    </section>
  );
}
