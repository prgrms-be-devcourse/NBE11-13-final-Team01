import { SCORE_ITEMS, type ScoreItem } from '../types';
import { Link } from 'react-router-dom';
import { ITEM_LABEL } from '../utils/format';

interface Props {
  priorities: Record<ScoreItem, number>;
  onChange: (next: Record<ScoreItem, number>) => void;
  dirty: boolean;
  saving: boolean;
  onSave: () => void;
  onReset: () => void;
}

const PRESETS: Record<string, Record<ScoreItem, number>> = {
  가성비형: { cost: 5, region: 3, area: 2, infra: 2, type: 2 },
  역세권형: { cost: 3, region: 3, area: 2, infra: 5, type: 2 },
  '넓은 집형': { cost: 2, region: 3, area: 5, infra: 2, type: 3 },
};

/** 항목별 우선순위 1~5. 움직이면 즉시 목록이 다시 계산되고, "적용"을 눌러야 저장된다. */
export default function PrioritySliders({ priorities, onChange, dirty, saving, onSave, onReset }: Props) {
  return (
    <section className="panel">
      <h2 className="panel__title">우선순위</h2>
      <div className="presets">
        {Object.entries(PRESETS).map(([name, p]) => (
          <button key={name} className="chip" onClick={() => onChange(p)}>
            {name}
          </button>
        ))}
      </div>
      {SCORE_ITEMS.map((item) => (
        <label key={item} className="slider">
          <span className="slider__label">{ITEM_LABEL[item]}</span>
          <input
            type="range"
            min={1}
            max={5}
            step={1}
            value={priorities[item]}
            onChange={(e) => onChange({ ...priorities, [item]: Number(e.target.value) })}
          />
          <span className="slider__value">{priorities[item]}</span>
        </label>
      ))}
      <div className="panel__actions">
        <button className="btn btn--ghost btn--small" disabled={!dirty || saving} onClick={onReset}>
          되돌리기
        </button>
        <button className="btn btn--small" disabled={!dirty || saving} onClick={onSave}>
          {saving ? '저장 중…' : '적용'}
        </button>
      </div>
      {dirty && <small className="hint">저장 전에도 목록에 바로 반영돼요.</small>}
      <Link to="/tune" className="tune-link">
        뭘 올려야 할지 모르겠다면? 두 집 비교로 찾기 →
      </Link>
    </section>
  );
}
