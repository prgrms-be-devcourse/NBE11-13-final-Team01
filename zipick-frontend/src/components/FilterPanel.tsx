import { useQuery } from '@tanstack/react-query';
import { houseApi } from '../api/house';
import type { AnnouncementStatus, HouseFilter, HousingType, RentalType } from '../types';
import { EMPTY_FILTER } from '../types';
import { HOUSING_LABEL, RENTAL_LABEL, STATUS_LABEL } from '../utils/format';

interface Props {
  filter: HouseFilter;
  onChange: (f: HouseFilter) => void;
  resultCount: number;
  /** 내 관심 지역. 있으면 지역 칩 맨 앞에 보여 준다 */
  regionOptions: { code: string; name: string }[];
}

const HOUSING: HousingType[] = ['APARTMENT', 'MULTI_HOUSEHOLD', 'ROW_HOUSE', 'OFFICETEL', 'URBAN_HOUSING'];
const RENTAL: RentalType[] = [
  'HAPPY', 'NATIONAL', 'YOUTH_PURCHASE', 'PURCHASE', 'DEUNDEUN', 'PERMANENT', 'INTEGRATED', 'PUBLIC', 'JEONSE_LEASE',
];
/** 모집 상태: 전체(공고중+접수중) / 공고중 / 접수중 */
const STATUS_MODES: { label: string; value: AnnouncementStatus[] }[] = [
  { label: '전체', value: ['UPCOMING', 'OPEN'] },
  { label: STATUS_LABEL.UPCOMING, value: ['UPCOMING'] },
  { label: STATUS_LABEL.OPEN, value: ['OPEN'] },
];

/**
 * 기본 필터링. 저장된 추천조건은 바꾸지 않고 지금 보이는 결과만 좁힌다.
 * 기관·유형·지역 칩은 실제 데이터가 있는 값만 켜진다(옆 숫자 = 모집 중인 주택 수).
 */
export default function FilterPanel({ filter, onChange, resultCount, regionOptions }: Props) {
  const options = useQuery({ queryKey: ['filter-options'], queryFn: houseApi.filterOptions, staleTime: 5 * 60_000 });
  const count = (group: 'housingTypes' | 'rentalTypes', v: string): number | undefined =>
    options.data ? ((options.data[group] as Record<string, number>)[v] ?? 0) : undefined;
  const sameSet = (a: string[], b: string[]) => a.length === b.length && a.every((x) => b.includes(x));

  const toggle = <T extends string>(key: keyof HouseFilter, v: T) => {
    const cur = filter[key] as T[];
    const next = cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v];
    onChange({ ...filter, [key]: next });
  };
  const num = (key: keyof HouseFilter, raw: string) => {
    const v = raw === '' ? undefined : Number(raw);
    onChange({ ...filter, [key]: v });
  };

  // 지역 칩: 내 관심 지역 먼저, 그다음 데이터가 있는 지역(시도별 묶음)
  const mine = new Set(regionOptions.map((r) => r.code));
  const regionCount = new Map((options.data?.regions ?? []).map((r) => [r.key, r.count]));
  const provinces = new Map<string, { key: string; district?: string | null; count: number }[]>();
  for (const r of options.data?.regions ?? []) {
    if (mine.has(r.key)) continue;
    if (!provinces.has(r.province)) provinces.set(r.province, []);
    provinces.get(r.province)!.push(r);
  }

  return (
    <section className="panel">
      <h2 className="panel__title">조건에 맞는 집 {resultCount}곳</h2>

      <fieldset className="field">
        <legend>지역</legend>
        <div className="chips">
          {regionOptions.map((r) => (
            <OptionChip key={r.code} on={filter.regions.includes(r.code)} count={regionCount.get(r.code) ?? 0} onClick={() => toggle('regions', r.code)}>
              ★ {r.name}
            </OptionChip>
          ))}
        </div>
        {filter.regions.some((k) => !mine.has(k)) && (
          <div className="chips chips--dense">
            {filter.regions.filter((k) => !mine.has(k)).map((k) => (
              <button key={k} className="chip chip--on" onClick={() => toggle('regions', k)}>
                {k} ×
              </button>
            ))}
          </div>
        )}
        <details className="region-more">
          <summary className="small muted">다른 지역 고르기 ({provinces.size}개 시도)</summary>
          {[...provinces.entries()].map(([province, list]) => (
            <div key={province} className="region-group">
              <span className="small muted">{province}</span>
              <div className="chips chips--dense">
                {list.filter((r) => !filter.regions.includes(r.key)).map((r) => (
                  <button key={r.key} className="chip" onClick={() => toggle('regions', r.key)}>
                    {r.district ?? r.key} <span className="chip__count">{r.count}</span>
                  </button>
                ))}
              </div>
            </div>
          ))}
        </details>
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
            <OptionChip key={t} on={filter.housingTypes.includes(t)} count={count('housingTypes', t)} onClick={() => toggle('housingTypes', t)}>
              {HOUSING_LABEL[t]}
            </OptionChip>
          ))}
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>모집 상태</legend>
        <div className="chips">
          {STATUS_MODES.map((m) => (
            <button
              key={m.label}
              className={`chip ${sameSet(filter.statuses, m.value) ? 'chip--on' : ''}`}
              onClick={() => onChange({ ...filter, statuses: m.value })}
            >
              {m.label}
            </button>
          ))}
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>공급기관</legend>
        <div className="chips">
          {(options.data?.agencies ?? []).map((a) => (
            <OptionChip key={a.code} on={filter.agencies.includes(a.code)} count={a.count} onClick={() => toggle('agencies', a.code)}>
              {a.name}
            </OptionChip>
          ))}
          {options.data && options.data.agencies.length === 0 && <small className="hint">수집된 기관이 없어요.</small>}
        </div>
      </fieldset>

      <fieldset className="field">
        <legend>공공임대 유형</legend>
        <div className="chips">
          {RENTAL.map((t) => (
            <OptionChip key={t} on={filter.rentalTypes.includes(t)} count={count('rentalTypes', t)} onClick={() => toggle('rentalTypes', t)}>
              {RENTAL_LABEL[t]}
            </OptionChip>
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

/** 데이터가 0건이면 꺼진다. 이미 고른 칩은 0건이어도 풀 수 있게 켜 둔다. */
function OptionChip({ on, count, onClick, children }: { on: boolean; count?: number; onClick: () => void; children: React.ReactNode }) {
  const empty = count === 0 && !on;
  return (
    <button className={`chip ${on ? 'chip--on' : ''}`} disabled={empty} title={empty ? '모집 중인 집이 없어요' : ''} onClick={onClick}>
      {children}
      {count !== undefined && <span className="chip__count">{count}</span>}
    </button>
  );
}
