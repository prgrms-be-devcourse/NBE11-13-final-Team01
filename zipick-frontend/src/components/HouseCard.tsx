import { Link } from 'react-router-dom';
import type { RecommendedHouse } from '../types';
import { HOUSING_LABEL, RENTAL_LABEL, STATUS_LABEL, area, dDay, won } from '../utils/format';
import ReasonList from './ReasonList';
import FeedbackButton from './FeedbackButton';

interface Props {
  rank: number;
  house: RecommendedHouse;
  onHidden: (houseId: number) => void;
}

export default function HouseCard({ rank, house, onHidden }: Props) {
  const d = dDay(house.closeDate);
  return (
    <article className="card">
      <div className="card__head">
        <span className="card__rank">{rank}</span>
        <span className="card__score">{house.score}점</span>
        <span className={`badge badge--${house.status.toLowerCase()}`}>{STATUS_LABEL[house.status]}</span>
        {d && <span className="badge badge--soon">{d}</span>}
        {house.eligibility === 'CHECK' && <span className="badge badge--check">확인 필요</span>}
        <span className="card__agency">
          {house.agency} · {RENTAL_LABEL[house.rentalType]}
        </span>
      </div>
      <h3 className="card__title">
        <Link to={`/houses/${house.houseId}`}>{house.title}</Link>
      </h3>
      <p className="card__addr">
        {house.address}
        {house.approximate && <span className="badge badge--approx">위치 대략</span>}
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
          <dt>유형</dt>
          <dd>{house.housingType ? HOUSING_LABEL[house.housingType] : '정보 없음'}</dd>
        </div>
        <div>
          <dt>준공</dt>
          <dd>{house.builtYear ?? '정보 없음'}</dd>
        </div>
      </dl>
      <ReasonList reasons={house.reasons} compact />
      <div className="card__actions">
        <FeedbackButton houseId={house.houseId} onHidden={onHidden} />
      </div>
    </article>
  );
}
