import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { recommendationApi } from '../api/recommendation';
import { ApiError } from '../api/client';
import FilterPanel from '../components/FilterPanel';
import HouseCard from '../components/HouseCard';
import PrioritySliders from '../components/PrioritySliders';
import RelaxSuggestions from '../components/RelaxSuggestions';
import ModeSwitch from '../components/ModeSwitch';
import { useRecommendationList } from '../hooks/useRecommendationList';
import { SCORE_ITEMS, type ScoreItem } from '../types';
import { applyPriorities } from '../utils/score';

const DEFAULT_PRIORITIES: Record<ScoreItem, number> = { cost: 3, region: 3, area: 3, infra: 3, type: 3 };

export default function RecommendationsPage() {
  const qc = useQueryClient();
  // 후보 전체를 점수와 함께 받아 두고(preview), 슬라이더는 브라우저에서 다시 계산한다
  const rec = useRecommendationList(true);
  const { filter, setFilter, search } = rec;
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
        <ModeSwitch search={search} />
        <div className="content__head">
          <h1>추천 공급주택</h1>
          {rec.data && (
            <p className="muted">
              후보 {rec.data.candidateTotal}곳 중 지원조건·정보 부족으로 {rec.data.excluded}곳 제외 · 추천{' '}
              <strong>{ranked.length}곳</strong>
              {active && ` · 기준 "${active.name}" v${active.version}`}
            </p>
          )}
          <nav className="view-switch">
            <Link to={`/map${search ? `?${search}` : ''}`}>지도로 보기</Link>
            <Link to={`/calendar${search ? `?${search}` : ''}`}>일정 보기</Link>
          </nav>
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
