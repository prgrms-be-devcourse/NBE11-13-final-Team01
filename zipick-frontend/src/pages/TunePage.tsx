import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { tuningApi } from '../api/tuning';
import PriorityBars from '../components/PriorityBars';
import ReasonList from '../components/ReasonList';
import type { PairChoice, RecommendedHouse } from '../types';
import { area, housingLabel, regionText, rentalLabel, won } from '../utils/format';

/**
 * 취향 찾기: 실제 집 두 곳 중 더 마음에 드는 쪽을 고르면, 그 선택으로 우선순위를 학습한다(쌍대 선택 학습).
 * 점수는 일부러 보여 주지 않는다. 점수를 보면 점수가 높은 쪽을 고르게 돼서 학습이 안 된다.
 */
export default function TunePage() {
  const qc = useQueryClient();
  const pair = useQuery({ queryKey: ['tune-pair'], queryFn: tuningApi.nextPair, retry: false });
  const result = useQuery({ queryKey: ['tune-result'], queryFn: tuningApi.result, retry: false });

  const choose = useMutation({
    mutationFn: (c: PairChoice) => tuningApi.choose(pair.data!.left!.houseId, pair.data!.right!.houseId, c),
    onSuccess: (r) => {
      qc.setQueryData(['tune-result'], r);
      qc.invalidateQueries({ queryKey: ['tune-pair'] });
    },
  });
  const apply = useMutation({
    mutationFn: tuningApi.apply,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['tune-result'] });
      qc.invalidateQueries({ queryKey: ['recommendations'] });
      qc.invalidateQueries({ queryKey: ['rec-profiles'] });
    },
  });
  const reset = useMutation({
    mutationFn: tuningApi.reset,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['tune-pair'] });
      qc.invalidateQueries({ queryKey: ['tune-result'] });
      apply.reset();
    },
  });

  const err = (pair.error ?? result.error) as ApiError | null;
  if (err && (err.code === 'PROFILE_NOT_FOUND' || err.code === 'PREFERENCE_NOT_FOUND')) {
    return (
      <div className="empty">
        <h2>먼저 내 조건을 입력해 주세요</h2>
        <p>희망 지역·예산이 있어야 두 집을 비교할 수 있어요.</p>
        <Link className="btn" to="/onboarding">
          내 조건 입력하기
        </Link>
      </div>
    );
  }

  const r = result.data;
  const p = pair.data;
  const progress = r ? Math.min(1, r.answered / r.minRequired) : 0;

  return (
    <div className="tune">
      <div className="content__head">
        <h1>두 집 중 어디가 더 좋아요?</h1>
        <p className="muted">고를수록 추천 우선순위가 내 취향에 맞춰져요. 점수는 일부러 가렸어요.</p>
      </div>

      {r && (
        <div className="tune__progress">
          <div className="tune__bar">
            <span style={{ width: `${progress * 100}%` }} />
          </div>
          <span className="small muted">
            {r.answered}번 골랐어요{!r.ready && ` · ${r.minRequired - r.answered}번 더 고르면 결과를 적용할 수 있어요`}
          </span>
        </div>
      )}

      {pair.isLoading && <p className="muted">비교할 집을 고르는 중…</p>}

      {p && p.left && p.right && (
        <>
          <div className="tune__pair">
            <CompareCard house={p.left} side="왼쪽" onPick={() => choose.mutate('LEFT')} disabled={choose.isPending} />
            <CompareCard house={p.right} side="오른쪽" onPick={() => choose.mutate('RIGHT')} disabled={choose.isPending} />
          </div>
          <div className="tune__tie">
            <button className="btn btn--ghost" disabled={choose.isPending} onClick={() => choose.mutate('TIE')}>
              비슷해요
            </button>
          </div>
        </>
      )}

      {p && !p.left && (
        <div className="empty">
          <h2>더 비교할 집이 없어요</h2>
          <p>지금 후보끼리는 다 비교했어요. 아래 결과를 적용하거나 처음부터 다시 해 보세요.</p>
        </div>
      )}

      {r && r.answered > 0 && (
        <section className="panel tune__result">
          <h2 className="panel__title">내 선택으로 찾은 우선순위</h2>
          <PriorityBars priorities={r.learnedPriorities} before={r.currentPriorities} />
          {r.agreement != null && (
            <p className="small muted">
              고른 답의 {Math.round(r.agreement * 100)}%를 이 우선순위로 설명할 수 있어요.
              {r.agreement < 0.7 && ' 답이 조금 엇갈려요. 몇 번 더 고르면 더 정확해져요.'}
            </p>
          )}
          {apply.isSuccess && <p className="ok">추천 기준에 적용했어요.</p>}
          {apply.isError && <div className="error">{(apply.error as ApiError).message}</div>}
          <div className="panel__actions">
            <button className="btn btn--ghost btn--small" disabled={reset.isPending} onClick={() => reset.mutate()}>
              처음부터
            </button>
            <Link className="btn btn--ghost btn--small" to="/recommendations">
              추천 보기
            </Link>
            <button className="btn btn--small" disabled={!r.ready || apply.isPending} onClick={() => apply.mutate()}>
              이 우선순위로 바꾸기
            </button>
          </div>
        </section>
      )}
    </div>
  );
}

function CompareCard({ house: h, side, onPick, disabled }: { house: RecommendedHouse; side: string; onPick: () => void; disabled: boolean }) {
  // 점수·지원조건 판정은 빼고, 집 자체에 대한 근거만 보여 준다
  const reasons = h.reasons.filter((r) => r.item !== 'ELIGIBILITY' && r.item !== 'coverage');
  return (
    <article className="card compare">
      <p className="small muted">
        {h.agencyName ?? h.agencyCode} · {rentalLabel(h)}
      </p>
      <h3 className="card__title">{h.housingName || h.address}</h3>
      <p className="small muted">{regionText(h)}{h.housingName && ` · ${h.address}`}</p>
      <dl className="compare__facts">
        <div><dt>보증금</dt><dd>{won(h.deposit)}</dd></div>
        <div><dt>월 임대료</dt><dd>{won(h.monthlyRent)}</dd></div>
        <div><dt>월 환산</dt><dd>{h.monthlyCost != null ? `${h.monthlyCost}만 원` : '—'}</dd></div>
        <div><dt>전용면적</dt><dd>{area(h.exclusiveArea)}</dd></div>
        <div><dt>유형</dt><dd>{housingLabel(h)}</dd></div>
        <div><dt>방 수</dt><dd>{h.roomCount != null ? `${h.roomCount}개` : '정보 없음'}</dd></div>
      </dl>
      <ReasonList reasons={reasons} />
      <button className="btn compare__pick" disabled={disabled} onClick={onPick} aria-label={`${side} 집 선택`}>
        이 집이 더 좋아요
      </button>
    </article>
  );
}
