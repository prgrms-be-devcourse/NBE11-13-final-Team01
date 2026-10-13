import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { recommendationApi } from '../api/recommendation';
import { useHouseFilter } from './useHouseFilter';

/**
 * URL 쿼리의 필터로 추천 목록을 받는다. 추천·지도·캘린더 화면이 같은 캐시를 쓴다.
 * preview=true면 60점 미만 후보까지 항목 점수와 함께 받는다(슬라이더 재계산용).
 */
export function useRecommendationList(preview = false) {
  const { filter, setFilter, search } = useHouseFilter();
  const query = useQuery({
    queryKey: ['recommendations', filter, preview],
    queryFn: () => recommendationApi.list(filter, preview),
    placeholderData: keepPreviousData,   // 필터를 바꿔도 새 결과가 올 때까지 이전 목록 유지
  });
  return { filter, setFilter, search, ...query };
}
