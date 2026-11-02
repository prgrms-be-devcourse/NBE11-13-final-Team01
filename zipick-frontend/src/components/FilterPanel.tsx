import { useId, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { houseApi } from '../api/house';
import type { HouseFilter, HousingType, RentalType, RegionOption } from '../types';
import { HOUSING_LABEL, RENTAL_LABEL } from '../utils/format';
import styles from './FilterPanel.module.css';

interface Props {
  filter: HouseFilter;
  onChange: (filter: HouseFilter) => void;
  regionOptions: { code: string; name: string }[];
}

const HOUSING: HousingType[] = ['APARTMENT', 'MULTI_HOUSEHOLD', 'ROW_HOUSE', 'OFFICETEL', 'URBAN_HOUSING'];
const RENTAL: RentalType[] = ['HAPPY', 'NATIONAL', 'YOUTH_PURCHASE', 'PURCHASE', 'DEUNDEUN', 'PERMANENT', 'INTEGRATED', 'PUBLIC', 'JEONSE_LEASE'];

/** 필터 옵션의 기존 집계와 키를 그대로 사용한다. 이 컴포넌트는 draft만 수정한다. */
export default function FilterPanel({ filter, onChange, regionOptions }: Props) {
  const options = useQuery({ queryKey: ['filter-options'], queryFn: houseApi.filterOptions, staleTime: 5 * 60_000 });
  const [province, setProvince] = useState('');
  const [search, setSearch] = useState('');
  const searchId = useId();
  const all: (Omit<RegionOption, 'count'> & { count?: number })[] = [...(options.data?.regions ?? [])];
  // 현재 집계에 없는 저장값도 복원하고 해제할 수 있다. 건수는 임의로 0을 만들지 않는다.
  for (const code of [...regionOptions.map((r) => r.code), ...filter.regions]) {
    if (all.some((r) => r.key === code)) continue;
    const split = code.indexOf(' ');
    all.push({ key: code, province: split < 0 ? code : code.slice(0, split), district: split < 0 ? null : code.slice(split + 1) });
  }
  const provinces = [...new Set(all.map((r) => r.province))];
  const selectedProvince = all.find((r) => r.key === filter.regions[0])?.province;
  const current = provinces.includes(province) ? province : selectedProvince || provinces[0] || '';
  const districts = all.filter((r) => r.province === current && r.key !== current && r.key.includes(search.trim()));
  const provinceCount = (name: string) => all.find((r) => r.key === name)?.count;
  const count = (group: 'housingTypes' | 'rentalTypes', value: string) => options.data ? ((options.data[group] as Record<string, number>)[value] ?? 0) : undefined;
  const toggle = (key: 'regions' | 'housingTypes' | 'agencies' | 'rentalTypes', value: string) => {
    const values: string[] = filter[key];
    onChange({ ...filter, [key]: values.includes(value) ? values.filter((v) => v !== value) : [...values, value] });
  };

  return (
    <div className={styles.fields}>
      <fieldset className={styles.section}>
        <legend>지역</legend>
        <div className={styles.sectionHead}><p>시·도를 선택하고, 원하는 시·군·구를 골라 주세요.</p><span className={styles.tag}>{filter.regions.length}곳 선택</span></div>
        <div className={styles.selected} role="group" aria-label="선택한 지역">
          {filter.regions.map((code) => <span className={styles.selectedChip} key={code}><span>{code}</span><button type="button" aria-label={`${code} 선택 해제`} onClick={() => toggle('regions', code)}>×</button></span>)}
          {filter.regions.length === 0 && <p>선택한 지역이 없어요. 전국을 기준으로 찾습니다.</p>}
        </div>
        <button type="button" className={styles.nationwide} aria-pressed={filter.regions.length === 0} onClick={() => onChange({ ...filter, regions: [] })}>전국</button>
        {options.isPending ? <p className={styles.notice} role="status">지역 목록을 불러오는 중이에요…</p> : options.isError ? (
          <p className={styles.notice} role="alert">지역 목록을 불러오지 못했어요. <button type="button" onClick={() => void options.refetch()}>다시 시도</button></p>
        ) : (
          <div className={styles.regionPicker}>
            <div className={styles.provinces} role="group" aria-label="시·도 선택">
              {provinces.map((name) => <button type="button" key={name} aria-pressed={name === current} onClick={() => { setProvince(name); setSearch(''); }}><span>{name}</span><Quantity count={provinceCount(name)} /></button>)}
            </div>
            <div className={styles.districtPanel}>
              <strong>{current || '선택 가능한 지역이 없어요'}</strong>
              <label className={styles.search} htmlFor={searchId}><span className={styles.srOnly}>시·군·구 검색</span><input id={searchId} type="search" placeholder="시·군·구 검색" value={search} autoComplete="off" onChange={(e) => setSearch(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') e.preventDefault(); }} /></label>
              <div className={styles.districts} role="group" aria-label="시·군·구 선택">
                {current && !search.trim() && <button type="button" className={styles.district} aria-pressed={filter.regions.includes(current)} onClick={() => toggle('regions', current)}><span>{current} 전체</span><Quantity count={provinceCount(current)} /></button>}
                {districts.map((r) => <button type="button" key={r.key} className={styles.district} aria-pressed={filter.regions.includes(r.key)} onClick={() => toggle('regions', r.key)}><span>{r.district ?? r.key}</span><Quantity count={r.count} /></button>)}
                {districts.length === 0 && <p className={styles.notice} role="status">{search.trim() ? '검색 결과가 없어요.' : '선택 가능한 시·군·구가 없어요.'}</p>}
              </div>
            </div>
          </div>
        )}
        <p className={styles.note}>숫자는 모집 중인 공고의 주택 수예요. 현재 지도 필터와 별도로 집계됩니다.</p>
      </fieldset>

      <fieldset className={styles.section}>
        <legend>예산과 면적</legend>
        <p className={styles.description}>필요한 항목만 입력해도 돼요. 입력하지 않으면 범위를 제한하지 않아요.</p>
        <div className={styles.budgets}>
          {([
            ['보증금', '만 원', 'depositMin', 'depositMax'],
            ['월 임대료', '만 원', 'rentMin', 'rentMax'],
            ['전용면적', '㎡', 'areaMin', 'areaMax'],
          ] as const).map(([label, unit, min, max]) => <div key={min} className={styles.budget}>
            <h3>{label} <small>({unit})</small></h3>
            <div className={styles.range}>{[min, max].map((key, index) => <span key={key} className={styles.amount}>
              {index === 1 && <span aria-hidden="true">~</span>}
              <input type="number" min="0" step={min === 'areaMin' ? 'any' : '1'} inputMode={min === 'areaMin' ? 'decimal' : 'numeric'} aria-label={`${label} ${index === 0 ? '최소' : '최대'}`} placeholder={index === 0 ? '최소' : '최대'} value={filter[key] ?? ''} onChange={(e) => onChange({ ...filter, [key]: e.target.value === '' ? undefined : Number(e.target.value) })} />
            </span>)}</div>
          </div>)}
        </div>
      </fieldset>

      <fieldset className={styles.section}><legend>주택 유형</legend><div className={styles.choices}>
        {HOUSING.map((type) => <OptionChip key={type} on={filter.housingTypes.includes(type)} count={count('housingTypes', type)} onClick={() => toggle('housingTypes', type)}>{HOUSING_LABEL[type]}</OptionChip>)}
      </div></fieldset>
      <fieldset className={styles.section}><legend>공급기관</legend><div className={styles.choices}>
        {(options.data?.agencies ?? []).map((agency) => <OptionChip key={agency.code} on={filter.agencies.includes(agency.code)} count={agency.count} onClick={() => toggle('agencies', agency.code)}>{agency.name}</OptionChip>)}
        {options.data && options.data.agencies.length === 0 && <p className={styles.notice}>수집된 기관이 없어요.</p>}
      </div></fieldset>
      <fieldset className={styles.section}><legend>공공임대 유형</legend><div className={styles.choices}>
        {RENTAL.map((type) => <OptionChip key={type} on={filter.rentalTypes.includes(type)} count={count('rentalTypes', type)} onClick={() => toggle('rentalTypes', type)}>{RENTAL_LABEL[type]}</OptionChip>)}
      </div></fieldset>
    </div>
  );
}

function Quantity({ count }: { count?: number }) {
  return count !== undefined ? <span className={styles.quantity}>{count.toLocaleString('ko-KR')}</span> : null;
}

function OptionChip({ on, count, onClick, children }: { on: boolean; count?: number; onClick: () => void; children: ReactNode }) {
  const empty = count === 0 && !on;
  return <button type="button" className={styles.choice} aria-pressed={on} disabled={empty} title={empty ? '모집 중인 집이 없어요' : undefined} onClick={onClick}><span>{children}</span><Quantity count={count} /></button>;
}
