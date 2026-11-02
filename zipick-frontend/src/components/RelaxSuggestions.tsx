import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { recommendationApi } from '../api/recommendation';
import type { HouseFilter } from '../types';

/** 추천이 5곳 미만일 때 조건을 하나씩 풀면 몇 곳이 되는지 보여준다. */
export default function RelaxSuggestions({ filter, currentCount }: { filter: HouseFilter; currentCount: number }) {
  const q = useQuery({
    queryKey: ['relax', filter],
    queryFn: () => recommendationApi.relax(filter),
    enabled: currentCount < 5,
  });

  if (currentCount >= 5 || !q.data || q.data.suggestions.length === 0) return null;

  return (
    <aside className="relax">
      <h3>추천이 {currentCount}곳뿐이에요. 조건을 조금 풀어 볼까요?</h3>
      <ul>
        {q.data.suggestions.map((s) => (
          <li key={s.field}>
            {s.change} → <strong>{s.countAfter}곳</strong>
          </li>
        ))}
      </ul>
      <Link to="/onboarding" className="btn btn--small">
        내 조건 수정하기
      </Link>
    </aside>
  );
}
