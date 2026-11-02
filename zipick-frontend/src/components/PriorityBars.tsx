import { SCORE_ITEMS, type ScoreItem } from '../types';
import { ITEM_LABEL } from '../utils/format';

interface Props {
  priorities: Record<ScoreItem, number>;
  /** 비교 대상(있으면 화살표로 변화 표시) */
  before?: Record<ScoreItem, number>;
  /** 강조할 항목 */
  highlight?: ScoreItem[];
}

/** 우선순위 1~5를 막대 5칸으로 보여 준다. */
export default function PriorityBars({ priorities, before, highlight }: Props) {
  return (
    <ul className="pbars">
      {SCORE_ITEMS.map((item) => {
        const v = priorities[item];
        const b = before?.[item];
        const dim = highlight && !highlight.includes(item);
        return (
          <li key={item} className={`pbar ${dim ? 'pbar--dim' : ''}`}>
            <span className="pbar__label">{ITEM_LABEL[item]}</span>
            <span className="pbar__cells" aria-label={`${v} / 5`}>
              {[1, 2, 3, 4, 5].map((n) => (
                <span key={n} className={`pbar__cell ${n <= v ? 'pbar__cell--on' : ''}`} />
              ))}
            </span>
            <span className="pbar__value">
              {b !== undefined && b !== v ? (
                <>
                  <span className="muted">{b} →</span> <strong className={v > b ? 'up' : 'down'}>{v}</strong>
                </>
              ) : (
                v
              )}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
