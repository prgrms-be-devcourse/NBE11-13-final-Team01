import { Link } from 'react-router-dom';
import type { RecommendedHouse } from '../types';
import { STATUS_LABEL, area, dDay, housingLabel, regionText, rentalLabel, won } from '../utils/format';
import ReasonList from './ReasonList';
import FeedbackButton from './FeedbackButton';
import InterestButton from './InterestButton';

interface Props {
  /** 순위 묶음이면 순위, 정보 부족 묶음이면 undefined */
  rank?: number;
  house: RecommendedHouse;
  onHidden: (houseId: number) => void;
}

export default function HouseCard({ rank, house, onHidden }: Props) {
  const d = dDay(house.closeDate);
  return (
    <article className={`card ${house.complete ? '' : 'card--incomplete'}`}>
      <div className="card__head">
        {rank !== undefined && <span className="card__rank">{rank}</span>}
        {house.complete ? (
          <span className="card__score">{house.score}점</span>
        ) : (
          <span className="badge badge--check">정보 부족 · 점수 없음</span>
        )}
        <span className={`badge badge--${house.status.toLowerCase()}`}>{STATUS_LABEL[house.status]}</span>
        {d && <span className="badge badge--soon">{d}</span>}
        {house.eligibility === 'CHECK' && <span className="badge badge--check">지원조건 확인 필요</span>}
        <span className="card__agency">
          {house.agencyName ?? house.agencyCode} · {rentalLabel(house)}
        </span>
      </div>
      <h3 className="card__title">
        <Link to={`/houses/${house.houseId}`}>{house.housingName || house.address}</Link>
      </h3>
      <p className="card__addr">
        {regionText(house)}
        {house.housingName && ` · ${house.address}`}
        <span className="muted small"> · {house.title}</span>
      </p>
      <dl className="card__facts">
        <div>
          <dt>보증금</dt>
          <dd>{won(house.deposit)}</dd>
        </div>
        <div>
          <dt>월 임대료</dt>
          <dd>{won(house.monthlyRent)}</dd>
        </div>
        <div>
          <dt>월 환산</dt>
          <dd>{house.monthlyCost != null ? `${house.monthlyCost}만 원` : '—'}</dd>
        </div>
        <div>
          <dt>전용면적</dt>
          <dd>{area(house.exclusiveArea)}</dd>
        </div>
        <div>
          <dt>방 수</dt>
          <dd>{house.roomCount != null ? `${house.roomCount}개` : '정보 없음'}</dd>
        </div>
        <div>
          <dt>유형</dt>
          <dd>{housingLabel(house)}</dd>
        </div>
      </dl>
      <ReasonList reasons={house.reasons} compact />
      <div className="card__actions">
        {house.detailUrl && (
          <a className="btn btn--ghost btn--small" href={house.detailUrl} target="_blank" rel="noreferrer">
            공고문 보기
          </a>
        )}
        <InterestButton noticeId={house.announcementId} />
        <FeedbackButton houseId={house.houseId} onHidden={onHidden} />
      </div>
    </article>
  );
}
