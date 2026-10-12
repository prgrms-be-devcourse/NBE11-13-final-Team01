import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { recommendationApi } from '../api/recommendation';
import { ApiError } from '../api/client';
import FilterPanel from '../components/FilterPanel';
import HouseCard from '../components/HouseCard';
import PrioritySliders from '../components/PrioritySliders';
import RelaxSuggestions from '../components/RelaxSuggestions';
import { EMPTY_FILTER, SCORE_ITEMS, type HouseFilter, type ScoreItem } from '../types';
import { applyPriorities } from '../utils/score';

const DEFAULT_PRIORITIES: Record<ScoreItem, number> = { cost: 3, region: 3, area: 3, infra: 3, type: 3 };

export default function RecommendationsPage() {
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const filter = useMemo(() => paramsToFilter(params), [params]);
  const setFilter = (f: HouseFilter) => setParams(filterToSearch(f), { replace: true });

  // 후보 전체를 점수와 함께 받아 두고(preview), 슬라이더는 브라우저에서 다시 계산한다
  const rec = useQuery({
    queryKey: ['recommendations', filter],
    queryFn: () => recommendationApi.list(filter, true),
  });
  const pref = useQuery({ queryKey: ['preference'], queryFn: profileApi.getPreference, retry: false });
  const profiles = useQuery({ queryKey: ['rec-profiles'], queryFn: recommendationApi.profiles });
  const active = profiles.data?.find((p) => p.active) ?? null;

  const serverPriorities = rec.data?.priorities ?? DEFAULT_PRIORITIES;
  const [priorities, setPriorities] = useState<Record<ScoreItem, number>>(DEFAULT_PRIORITIES);
  useEffect(() => {
    if (rec.data) setPriorities(rec.data.priorities);
  }, [rec.data]);
  const dirty = SCORE_ITEMS.some((i) => priorities[i] !== serverPriorities[i]);

  const [hidden, setHidden] = useState<Set<number>>(new Set());
  const ranked = useMemo(() => {
    if (!rec.data) return [];
    const visible = rec.data.houses.filter((h) => !hidden.has(h.houseId));
    return applyPriorities(visible, rec.data.baseWeights, priorities);
  }, [rec.data, priorities, hidden]);

  const save = useMutation({
    mutationFn: async () => {
      if (active) return recommendationApi.updateProfile(active.id, { weights: priorities });
      return recommendationApi.createProfile({ name: '기본', weights: priorities });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['recommendations'] });
      qc.invalidateQueries({ queryKey: ['rec-profiles'] });
    },
  });

  if (rec.isError) {
    const e = rec.error as ApiError;
    if (e.code === 'PROFILE_NOT_FOUND' || e.code === 'PREFERENCE_NOT_FOUND') {
      return (
        <div className="empty">
          <h2>먼저 내 조건을 입력해 주세요</h2>
          <p>나이·소득·무주택 여부와 희망 지역·예산을 알려 주시면 맞는 집을 골라 드려요.</p>
          <Link className="btn" to="/onboarding">
            내 조건 입력하기
          </Link>
        </div>
      );
    }
    return <div className="error">{e.message}</div>;
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <FilterPanel
          filter={filter}
          onChange={setFilter}
          resultCount={ranked.length}
          regionOptions={pref.data?.regions ?? []}
        />
        <PrioritySliders
          priorities={priorities}
          onChange={setPriorities}
          dirty={dirty}
          saving={save.isPending}
          onSave={() => save.mutate()}
          onReset={() => setPriorities(serverPriorities)}
        />
      </aside>

      <section className="content">
        <div className="content__head">
          <h1>추천 공급주택</h1>
          {rec.data && (
            <p className="muted">
              후보 {rec.data.candidateTotal}곳 중 지원조건·정보 부족으로 {rec.data.excluded}곳 제외 · 추천{' '}
              <strong>{ranked.length}곳</strong>
              {active && ` · 기준 "${active.name}" v${active.version}`}
            </p>
          )}
        </div>

        {rec.isLoading && <p className="muted">추천을 계산하는 중…</p>}

        {rec.data && <RelaxSuggestions filter={filter} currentCount={ranked.length} />}

        {rec.data && ranked.length === 0 && !rec.isLoading && (
          <div className="empty">
            <h2>조건에 맞는 집이 없어요</h2>
            <p>필터를 풀거나 내 조건을 넓혀 보세요.</p>
          </div>
        )}

        <div className="cards">
          {ranked.map((h, i) => (
            <HouseCard
              key={h.houseId}
              rank={i + 1}
              house={h}
              onHidden={(id) => setHidden((s) => new Set(s).add(id))}
            />
          ))}
        </div>
      </section>
    </div>
  );
}

// ---- 필터 ↔ URL 쿼리 (새로고침·링크 공유에도 유지) ----

function paramsToFilter(p: URLSearchParams): HouseFilter {
  const list = (k: string) => (p.get(k) ? p.get(k)!.split(',').filter(Boolean) : []);
  const num = (k: string) => (p.get(k) ? Number(p.get(k)) : undefined);
  const statuses = list('statuses') as HouseFilter['statuses'];
  return {
    regions: list('regions'),
    depositMin: num('depositMin'),
    depositMax: num('depositMax'),
    rentMin: num('rentMin'),
    rentMax: num('rentMax'),
    areaMin: num('areaMin'),
    areaMax: num('areaMax'),
    housingTypes: list('housingTypes') as HouseFilter['housingTypes'],
    statuses: statuses.length ? statuses : EMPTY_FILTER.statuses,
    agencies: list('agencies') as HouseFilter['agencies'],
    rentalTypes: list('rentalTypes') as HouseFilter['rentalTypes'],
    includeUnknown: p.get('includeUnknown') !== 'false',
  };
}

function filterToSearch(f: HouseFilter): Record<string, string> {
  const out: Record<string, string> = {};
  const set = (k: string, v: string[] | number | undefined) => {
    if (Array.isArray(v)) {
      if (v.length) out[k] = v.join(',');
    } else if (v !== undefined && !Number.isNaN(v)) out[k] = String(v);
  };
  set('regions', f.regions);
  set('depositMin', f.depositMin);
  set('depositMax', f.depositMax);
  set('rentMin', f.rentMin);
  set('rentMax', f.rentMax);
  set('areaMin', f.areaMin);
  set('areaMax', f.areaMax);
  set('housingTypes', f.housingTypes);
  set('statuses', f.statuses);
  set('agencies', f.agencies);
  set('rentalTypes', f.rentalTypes);
  if (!f.includeUnknown) out.includeUnknown = 'false';
  return out;
}
