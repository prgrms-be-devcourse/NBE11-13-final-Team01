import type { Agency, AnnouncementStatus, HouseFilter, HousingType, RentalType } from '../types';
import { EMPTY_FILTER } from '../types';
import { HOUSING_LABEL, RENTAL_LABEL, STATUS_LABEL } from '../utils/format';

interface Props {
  filter: HouseFilter;
  onChange: (f: HouseFilter) => void;
  resultCount: number;
  regionOptions: { code: string; name: string }[];
}

const AGENCIES: Agency[] = ['LH', 'SH', 'GH', 'HUG'];
const COLLECTED: Agency[] = ['LH']; // 아직 수집하지 않는 기관은 비활성
const STATUSES: AnnouncementStatus[] = ['NOTICE', 'RECEIVING'];
const HOUSING: HousingType[] = ['APARTMENT', 'MULTI_HOUSEHOLD', 'OFFICETEL', 'ROW_HOUSE'];
const RENTAL: RentalType[] = ['PURCHASE', 'HAPPY', 'DEUNDEUN'];

/** 추천 공고 외 필터링. 필터는 추천 계산 전에 후보를 거른다. */
export default function FilterPanel({ filter, onChange, resultCount, regionOptions }: Props) {
  const toggle = <T extends string>(key: keyof HouseFilter, v: T) => {
    const cur = filter[key] as T[];
    const next = cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v];
    onChange({ ...filter, [key]: next });
  };
  const num = (key: keyof HouseFilter, raw: string) => {
    const v = raw === '' ? undefined : Number(raw);
    onChange({ ...filter, [key]: v });
  };

  return (
    <section className="panel">
      <h2 className="panel__title">조건에 맞는 집 {resultCount}곳</h2>

      <fieldset className="field">
        <legend>지역</legend>
        <div className="chips">
          {regionOptions.map((r) => (
            <button
              key={r.code}
              className={`chip ${filter.regions.includes(r.code) ? 'chip--on' : ''}`}
              onClick={() => toggle('regions', r.code)}
            >
              {r.name}
            </button>
          ))}
          {regionOptions.length === 0 && <small className="hint">내 조건의 관심 지역이 여기 보여요.</small>}
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>보증금 (만 원)</legend>
        <div className="range">
          <input type="number" placeholder="최소" value={filter.depositMin ?? ''} onChange={(e) => num('depositMin', e.target.value)} />
          <span>~</span>
          <input type="number" placeholder="최대" value={filter.depositMax ?? ''} onChange={(e) => num('depositMax', e.target.value)} />
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>월 임대료 (만 원)</legend>
        <div className="range">
          <input type="number" placeholder="최소" value={filter.rentMin ?? ''} onChange={(e) => num('rentMin', e.target.value)} />
          <span>~</span>
          <input type="number" placeholder="최대" value={filter.rentMax ?? ''} onChange={(e) => num('rentMax', e.target.value)} />
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>전용면적 (㎡)</legend>
        <div className="range">
          <input type="number" placeholder="최소" value={filter.areaMin ?? ''} onChange={(e) => num('areaMin', e.target.value)} />
          <span>~</span>
          <input type="number" placeholder="최대" value={filter.areaMax ?? ''} onChange={(e) => num('areaMax', e.target.value)} />
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>주택 유형</legend>
        <div className="chips">
          {HOUSING.map((t) => (
            <button key={t} className={`chip ${filter.housingTypes.includes(t) ? 'chip--on' : ''}`} onClick={() => toggle('housingTypes', t)}>
              {HOUSING_LABEL[t]}
            </button>
          ))}
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>모집 상태</legend>
        <div className="chips">
          {STATUSES.map((s) => (
            <button key={s} className={`chip ${filter.statuses.includes(s) ? 'chip--on' : ''}`} onClick={() => toggle('statuses', s)}>
              {STATUS_LABEL[s]}
            </button>
          ))}
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>공급기관</legend>
        <div className="chips">
          {AGENCIES.map((a) => (
            <button
              key={a}
              className={`chip ${filter.agencies.includes(a) ? 'chip--on' : ''}`}
              disabled={!COLLECTED.includes(a)}
              title={COLLECTED.includes(a) ? '' : '수집 준비 중'}
              onClick={() => toggle('agencies', a)}
            >
              {a}
            </button>
          ))}
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>공공임대 유형</legend>
        <div className="chips">
          {RENTAL.map((t) => (
            <button key={t} className={`chip ${filter.rentalTypes.includes(t) ? 'chip--on' : ''}`} onClick={() => toggle('rentalTypes', t)}>
              {RENTAL_LABEL[t]}
            </button>
          ))}
        </div>
      </fieldset>

      <label className="toggle">
        <input
          type="checkbox"
          checked={filter.includeUnknown}
          onChange={(e) => onChange({ ...filter, includeUnknown: e.target.checked })}
        />
        정보 없는 집도 보기
      </label>

      <button className="btn btn--ghost" onClick={() => onChange({ ...EMPTY_FILTER })}>
        필터 초기화
      </button>
    </section>
  );
}
