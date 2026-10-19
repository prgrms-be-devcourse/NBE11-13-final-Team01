import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { tuningApi } from '../api/tuning';
import { SCORE_ITEMS, type AhpComparison, type AhpResult, type ScoreItem } from '../types';
import { ITEM_LABEL } from '../utils/format';
import PriorityBars from './PriorityBars';

/** 질문 3개 = 비용·지역·인프라 세 항목의 모든 쌍. 서로 맞물려 있어 답이 엇갈리면 알아챌 수 있다. */
const QUESTIONS: { left: ScoreItem; right: ScoreItem }[] = [
  { left: 'cost', right: 'region' },
  { left: 'region', right: 'infra' },
  { left: 'cost', right: 'infra' },
];

/** 질문에 쓰는 말. 항목 이름보다 실제 고민에 가깝게 */
const PHRASE: Record<ScoreItem, string> = {
  cost: '월 비용이 싼 집',
  region: '원하는 동네',
  area: '넓은 집',
  rooms: '방이 많은 집',
  infra: '역·편의시설이 가까운 집',
  age: '새로 지은 집',
  type: '원하는 주택 유형',
};

const SCALE: { value: AhpComparison['value']; label: (l: string, r: string) => string }[] = [
  { value: 2, label: (l) => `${l} 훨씬` },
  { value: 1, label: (l) => `${l} 조금 더` },
  { value: 0, label: () => '비슷해요' },
  { value: -1, label: (_, r) => `${r} 조금 더` },
  { value: -2, label: (_, r) => `${r} 훨씬` },
];

interface Props {
  onDone: () => void;
  onSkip: () => void;
}

/** 온보딩 3단계: AHP 쌍대 비교 질문 3개로 추천 우선순위의 첫 값을 정한다. */
export default function AhpQuestions({ onDone, onSkip }: Props) {
  const [answers, setAnswers] = useState<(AhpComparison['value'] | null)[]>(QUESTIONS.map(() => null));
  const [preview, setPreview] = useState<AhpResult | null>(null);
  const complete = answers.every((a) => a !== null);
  const comparisons = (): AhpComparison[] =>
    QUESTIONS.map((q, i) => ({ left: q.left, right: q.right, value: answers[i]! }));

  // 세 문항에 다 답하면 저장하지 않고 결과만 미리 계산한다
  const previewMut = useMutation({ mutationFn: () => tuningApi.ahp(comparisons(), false), onSuccess: setPreview });
  useEffect(() => {
    if (complete) previewMut.mutate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [answers]);

  const save = useMutation({ mutationFn: () => tuningApi.ahp(comparisons(), true), onSuccess: onDone });

  return (
    <div className="form">
      <h1>무엇이 더 중요해요?</h1>
      <p className="muted">둘 중 하나를 고르면 추천 점수의 우선순위를 정해 드려요. 나중에 언제든 바꿀 수 있어요.</p>

      {QUESTIONS.map((q, i) => {
        const l = PHRASE[q.left];
        const r = PHRASE[q.right];
        return (
          <fieldset key={i} className="ahp">
            <legend>
              {i + 1}. <strong>{l}</strong> vs <strong>{r}</strong>
            </legend>
            <div className="ahp__scale" role="radiogroup">
              {SCALE.map((s) => (
                <button
                  key={s.value}
                  type="button"
                  role="radio"
                  aria-checked={answers[i] === s.value}
                  className={`ahp__opt ${answers[i] === s.value ? 'ahp__opt--on' : ''} ${s.value === 0 ? 'ahp__opt--mid' : ''}`}
                  onClick={() => setAnswers(answers.map((a, j) => (j === i ? s.value : a)))}
                >
                  {s.label(l, r)}
                </button>
              ))}
            </div>
          </fieldset>
        );
      })}

      {preview && complete && (
        <div className="ahp__result">
          <h2>이렇게 정해져요</h2>
          <PriorityBars priorities={preview.priorities} highlight={['cost', 'region', 'infra']} />
          {!preview.consistent && (
            <p className="warn">
              답의 순서가 서로 맞지 않아요. 예를 들어 {ITEM_LABEL.cost}이 {ITEM_LABEL.region}보다, {ITEM_LABEL.region}이{' '}
              {ITEM_LABEL.infra}보다 중요하면 보통 {ITEM_LABEL.cost}이 {ITEM_LABEL.infra}보다도 중요해요. 엇갈린 항목은 비슷하게
              매겨져요. 그대로 저장해도 돼요.
            </p>
          )}
          <small className="hint">
            {SCORE_ITEMS.filter((i) => !['cost', 'region', 'infra'].includes(i)).map((i) => ITEM_LABEL[i]).join('·')}은 지금 값을
            유지해요.
          </small>
        </div>
      )}

      <div className="form__actions">
        <button className="btn btn--ghost" type="button" onClick={onSkip}>
          건너뛰기
        </button>
        <button className="btn" type="button" disabled={!complete || save.isPending} onClick={() => save.mutate()}>
          저장하고 추천 보기
        </button>
      </div>
    </div>
  );
}
