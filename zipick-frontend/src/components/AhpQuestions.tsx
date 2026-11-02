import { useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { tuningApi } from '../api/tuning';
import { SCORE_ITEMS, type AhpComparison } from '../types';
import { ITEM_LABEL } from '../utils/format';
import styles from '../features/onboarding/OnboardingPage.module.css';

/** API의 비교 방향과 질문 순서를 유지한다. 양수는 왼쪽, 음수는 오른쪽 우선이다. */
const QUESTIONS = [
  { left: 'cost', right: 'region' },
  { left: 'region', right: 'infra' },
  { left: 'cost', right: 'infra' },
] as const;
const LABEL = { cost: '월 비용', region: '희망 지역', infra: '주변 인프라' };
const ITEMS = ['cost', 'region', 'infra'] as const;
const VALUES: AhpComparison['value'][] = [2, 1, 0, -1, -2];

function answerText(question: typeof QUESTIONS[number], value: AhpComparison['value'] | null) {
  if (value === null) return '선택 전';
  if (value === 0) return '두 조건이 비슷해요';
  const item = value > 0 ? question.left : question.right;
  return `${LABEL[item]}${item === 'infra' ? '를' : '을'} ${Math.abs(value) === 2 ? '훨씬' : '조금'} 더 우선해요`;
}

interface Props {
  onBack: () => void;
  onDone: () => void;
  onSkip: () => void;
  onSavingChange: (saving: boolean) => void;
}

/** 온보딩 3단계. 중앙에 있는 초기 손잡이와 실제 중앙 응답(0)을 구별한다. */
export default function AhpQuestions({ onBack, onDone, onSkip, onSavingChange }: Props) {
  const [answers, setAnswers] = useState<(AhpComparison['value'] | null)[]>(QUESTIONS.map(() => null));
  const actionInFlight = useRef(false);
  const count = answers.filter((answer) => answer !== null).length;
  const complete = count === QUESTIONS.length;
  const comparisons: AhpComparison[] = QUESTIONS.map((question, index) => ({ ...question, value: answers[index]! }));

  // 답변별 쿼리로 요청을 분리한다. 늦게 도착한 이전 응답이 현재 답변의 미리보기를 덮지 못한다.
  const preview = useQuery({
    queryKey: ['ahp-preview', answers],
    queryFn: () => tuningApi.ahp(comparisons, false),
    enabled: complete,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const save = useMutation({
    mutationFn: (snapshot: AhpComparison[]) => tuningApi.ahp(snapshot, true),
    onSuccess: onDone,
    onError: () => {
      actionInFlight.current = false;
      onSavingChange(false);
    },
  });

  function select(index: number, position: number) {
    if (actionInFlight.current) return;
    const value = VALUES[position];
    setAnswers((current) => current[index] === value ? current : current.map((answer, i) => i === index ? value : answer));
    if (save.isError) save.reset();
  }

  function leave(action: () => void) {
    if (actionInFlight.current) return;
    actionInFlight.current = true;
    action();
  }

  return (
    <section className={`${styles.profilePanel} ${styles.priorityPanel}`} aria-labelledby="priority-title" aria-busy={save.isPending}>
      <div className={styles.heading}>
        <h2 id="priority-title">우선순위</h2>
        <p>집을 고를 때 어떤 조건이 더 중요한지 알려 주세요. 이 단계는 건너뛰어도 돼요.</p>
      </div>
      <div className={`${styles.fieldFrame} ${styles.priorityFrame}`}>
        <div className={styles.priorityHeader}>
          <h3 id="priority-questions-title">중요한 조건 비교 <span className={styles.requirement}>선택</span></h3>
          <span className={styles.regionCount} role="status" aria-live="polite" aria-label={`${count} / 3문항 응답`}><strong>{count}</strong> / 3</span>
        </div>
        <p className={styles.priorityDescription}>두 조건 중 더 중요한 쪽으로 슬라이더를 움직여 주세요.</p>
        <div role="group" aria-labelledby="priority-questions-title">
          {QUESTIONS.map((question, index) => {
            const answer = answers[index];
            const position = answer === null ? 2 : 2 - answer;
            const percent = position * 25;
            const id = `priority-slider-${index + 1}`;
            const response = answerText(question, answer);
            return (
              <section key={id} className={`${styles.priorityQuestion} ${answer !== null ? styles.priorityAnswered : ''}`} aria-labelledby={`${id}-title`}>
                <div className={styles.questionHeading}>
                  <div className={styles.questionTitle}>
                    <span className={styles.questionNumber} aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
                    <h4 id={`${id}-title`}>{LABEL[question.left]} vs {LABEL[question.right]}</h4>
                  </div>
                  <output className={styles.questionResponse} id={`${id}-response`} htmlFor={id} aria-live="polite">{response}</output>
                </div>
                <div className={styles.sliderLine}>
                  <div className={styles.sliderTrack} aria-hidden="true">
                    <span className={styles.sliderHighlight} style={{ left: `${Math.min(50, percent)}%`, width: `${Math.abs(percent - 50)}%` }} />
                    {VALUES.map((value, i) => <span key={value} className={styles.sliderTick} style={{ left: `${i * 25}%` }} />)}
                  </div>
                  <input
                    className={styles.prioritySlider}
                    id={id}
                    type="range" min={0} max={4} step={1} value={position}
                    disabled={save.isPending}
                    aria-labelledby={`${id}-title`}
                    aria-describedby={`${id}-response`}
                    aria-valuetext={response}
                    onChange={(event) => select(index, Number(event.currentTarget.value))}
                    // 같은 중앙 위치 클릭은 change가 발생하지 않으므로 명시적으로 응답한다.
                    onPointerUp={(event) => select(index, Number(event.currentTarget.value))}
                    onClick={(event) => select(index, Number(event.currentTarget.value))}
                    onKeyDown={(event) => {
                      const next: Record<string, number> = {
                        ArrowLeft: Math.max(0, position - 1), ArrowDown: Math.max(0, position - 1),
                        ArrowRight: Math.min(4, position + 1), ArrowUp: Math.min(4, position + 1),
                        Home: 0, End: 4, ' ': position, Enter: position,
                      };
                      if (event.key in next) {
                        event.preventDefault();
                        select(index, next[event.key]);
                      }
                    }}
                  />
                </div>
                <div className={styles.sliderLabels} aria-hidden="true"><span>{LABEL[question.left]}</span><span>비슷해요</span><span>{LABEL[question.right]}</span></div>
              </section>
            );
          })}
        </div>

        {complete && (
          <section className={styles.priorityResult} aria-labelledby="priority-result-title" aria-busy={preview.isFetching}>
            <h4 id="priority-result-title">내 우선순위 미리보기</h4>
            {preview.isFetching ? <p role="status">우선순위를 계산하는 중이에요…</p> : preview.isError ? (
              <div className={styles.previewError} role="alert">
                <p>미리보기를 불러오지 못했어요. 다시 시도해 주세요.</p>
                <button className={styles.secondaryButton} type="button" disabled={save.isPending} onClick={() => void preview.refetch()}>다시 시도</button>
              </div>
            ) : preview.data && (
              <>
                <p>세 번의 선택을 바탕으로 중요도를 비교했어요.</p>
                <div className={styles.priorityBars}>
                  {ITEMS.map((item) => {
                    const percent = (preview.data.weights[item] ?? 0) * 100;
                    return (
                      <div className={styles.priorityBarRow} key={item}>
                        <div className={styles.barLabel}><span>{LABEL[item]}</span><span>중요도 {preview.data.priorities[item]} / 5 <strong>{Math.round(percent)}%</strong></span></div>
                        <div className={styles.barTrack} role="meter" aria-label={`${LABEL[item]} 중요도 비율`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} aria-valuetext={`${Math.round(percent)}%, 중요도 ${preview.data.priorities[item]} / 5`}>
                          <div className={styles.barFill} style={{ width: `${percent}%` }} />
                        </div>
                      </div>
                    );
                  })}
                </div>
                {!preview.data.consistent && <p className={styles.priorityWarning} role="status">선택한 우선순위가 일부 엇갈려요. 엇갈린 항목은 비슷하게 매겨져요. 그대로 진행하거나 다시 조정할 수 있어요.</p>}
                <p className={styles.priorityResultNote}>{SCORE_ITEMS.filter((item) => !ITEMS.some((compared) => compared === item)).map((item) => ITEM_LABEL[item]).join('·')}은 지금 값을 유지해요.</p>
              </>
            )}
          </section>
        )}
      </div>

      {save.isError && <p className={styles.prioritySaveError} role="alert">우선순위를 저장하지 못했어요. 다시 저장해 주세요.</p>}
      <div className={styles.priorityActions}>
        <button className={styles.preferenceBack} type="button" disabled={save.isPending} onClick={() => leave(onBack)}>← 희망 주거 조건</button>
        <div className={styles.priorityRightActions}>
          <button className={styles.prioritySkip} type="button" disabled={save.isPending} onClick={() => leave(onSkip)}>건너뛰기</button>
          <button className={styles.preferenceNext} type="button" disabled={!complete || save.isPending} onClick={() => {
            if (!complete || actionInFlight.current) return;
            // React 렌더 전의 연속 클릭도 막고, 저장할 답변을 요청 시점에 고정한다.
            actionInFlight.current = true;
            onSavingChange(true);
            save.mutate(comparisons);
          }}>{save.isPending ? '저장 중…' : '저장하고 추천 보기'}</button>
        </div>
      </div>
      <p className={styles.priorityFooterNote}>건너뛰면 우선순위는 변경되지 않아요.</p>
    </section>
  );
}
