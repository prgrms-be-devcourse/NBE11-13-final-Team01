import type { Reason } from '../types';
import { MARK_SYMBOL } from '../utils/format';

export default function ReasonList({ reasons, compact = false }: { reasons: Reason[]; compact?: boolean }) {
  const list = compact ? pickCompact(reasons) : reasons;
  return (
    <ul className={`reasons ${compact ? 'reasons--compact' : ''}`}>
      {list.map((r, i) => (
        <li key={i} className={`reason reason--${r.mark.toLowerCase()}`}>
          <span className="reason__mark">{MARK_SYMBOL[r.mark]}</span>
          <span>{r.text}</span>
        </li>
      ))}
    </ul>
  );
}

/** 카드용: 지원조건은 요약 한 줄, 항목 근거는 전부 */
function pickCompact(reasons: Reason[]): Reason[] {
  const elig = reasons.filter((r) => r.item === 'ELIGIBILITY');
  const items = reasons.filter((r) => r.item !== 'ELIGIBILITY');
  const worst = elig.find((r) => r.mark === 'FAIL') ?? elig.find((r) => r.mark === 'CHECK');
  const summary: Reason | null = elig.length
    ? worst ?? { mark: 'PASS', item: 'ELIGIBILITY', text: '지원조건 충족' }
    : null;
  return summary ? [summary, ...items] : items;
}
