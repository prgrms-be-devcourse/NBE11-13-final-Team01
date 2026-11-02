import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { recommendationApi } from '../api/recommendation';
import { SCORE_ITEMS, type HouseFilter, type ScoreItem } from '../types';
import { applyPriorities } from '../utils/score';

const DEFAULT_PRIORITIES: Record<ScoreItem, number> = {
  cost: 3, region: 3, area: 3, rooms: 3, infra: 3, age: 3, type: 3,
};

/**
 * 맞춤 추천 화면의 상태를 한곳에 모은다. 기존 RecommendationsPage 가 하던 일을 그대로 옮긴 것이다.
 *
 * ⚠️ 추천 기준은 서버 것을 쓴다. 프론트에서 다른 기준을 만들지 않는다.
 * preview=true 로 후보와 항목 점수를 함께 받아, 우선순위 슬라이더를 움직일 때만
 * utils/score 의 **서버와 같은 공식**으로 다시 계산해 즉시 보여 준다.
 * 슬라이더를 건드리지 않으면 서버가 매긴 순위·점수와 같은 결과가 나온다.
 * '적용'을 누르면 추천 기준(프로필)에 저장되어 서버 쪽 계산도 같이 바뀐다.
 */
export function useRecommendationBoard(filter: HouseFilter, enabled: boolean) {
  const qc = useQueryClient();

  const query = useQuery({
    queryKey: ['recommendations', filter, true],
    queryFn: () => recommendationApi.list(filter, true),
    enabled,
    placeholderData: keepPreviousData,
  });
  const profiles = useQuery({ queryKey: ['rec-profiles'], queryFn: recommendationApi.profiles, enabled });
  const active = profiles.data?.find((p) => p.active) ?? null;

  const serverPriorities = query.data?.priorities ?? DEFAULT_PRIORITIES;
  const [priorities, setPriorities] = useState<Record<ScoreItem, number>>(DEFAULT_PRIORITIES);
  useEffect(() => {
    if (query.data) setPriorities(query.data.priorities);
  }, [query.data]);
  const dirty = SCORE_ITEMS.some((item) => priorities[item] !== serverPriorities[item]);

  // "별로예요"로 숨긴 집. 서버가 피드백을 저장하고, 화면에서는 바로 빼 준다.
  const [hidden, setHidden] = useState<Set<number>>(new Set());
  const hide = (houseId: number) => setHidden((prev) => new Set(prev).add(houseId));

  const ranked = useMemo(() => {
    if (!query.data) return [];
    const visible = query.data.houses.filter((h) => !hidden.has(h.houseId));
    return applyPriorities(visible, query.data.baseWeights, priorities);
  }, [query.data, priorities, hidden]);

  const incomplete = useMemo(
    () => (query.data?.incomplete ?? []).filter((h) => !hidden.has(h.houseId)),
    [query.data, hidden],
  );

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

  return {
    query,
    active,
    ranked,
    incomplete,
    priorities,
    setPriorities,
    serverPriorities,
    dirty,
    save,
    hide,
  };
}
