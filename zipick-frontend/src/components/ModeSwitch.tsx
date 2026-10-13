import { NavLink } from 'react-router-dom';

/** 추천 공급주택 ↔ 전체 공급주택 전환. 필터(URL 쿼리)는 그대로 넘긴다. */
export default function ModeSwitch({ search }: { search: string }) {
  const q = search ? `?${search}` : '';
  return (
    <nav className="mode-switch" aria-label="보기 모드">
      <NavLink to={`/recommendations${q}`}>추천 공급주택</NavLink>
      <NavLink to={`/houses${q}`} end>
        전체 공급주택
      </NavLink>
    </nav>
  );
}
