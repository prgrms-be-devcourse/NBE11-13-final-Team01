import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { tuningApi } from '../api/tuning';
import PriorityBars from '../components/PriorityBars';
import ReasonList from '../components/ReasonList';
import type { PairChoice, RecommendedHouse } from '../types';
import { area, housingLabel, ITEM_LABEL, regionText, rentalLabel, won } from '../utils/format';
import styles from '../features/tune/TunePage.module.css';

/** 점수는 가리고 집 자체를 비교한다. 최소 응답 수 이후에도 비교를 이어갈 수 있다. */
export default function TunePage() {
  const qc = useQueryClient();
  const actionLock = useRef(false);
  const pair = useQuery({ queryKey: ['tune-pair'], queryFn: tuningApi.nextPair, retry: false });
  const result = useQuery({ queryKey: ['tune-result'], queryFn: tuningApi.result, retry: false });
  const choose = useMutation({
    mutationFn: (c: PairChoice) => tuningApi.choose(pair.data!.left!.houseId, pair.data!.right!.houseId, c),
    onSuccess: (r) => {
      qc.setQueryData(['tune-result'], r);
      // 다음 쌍이 준비될 때까지 선택을 잠가 같은 쌍을 연속 전송하지 않는다.
      return qc.invalidateQueries({ queryKey: ['tune-pair'] });
    },
  });
  const apply = useMutation({
    mutationFn: tuningApi.apply,
    onSuccess: () => Promise.all([
      qc.invalidateQueries({ queryKey: ['tune-result'] }),
      qc.invalidateQueries({ queryKey: ['recommendations'] }),
      qc.invalidateQueries({ queryKey: ['rec-profiles'] }),
    ]),
  });
  const reset = useMutation({
    mutationFn: tuningApi.reset,
    onSuccess: () => {
      apply.reset();
      choose.reset();
      return Promise.all([
        qc.invalidateQueries({ queryKey: ['tune-pair'] }),
        qc.invalidateQueries({ queryKey: ['tune-result'] }),
      ]);
    },
  });
  const busy = choose.isPending || apply.isPending || reset.isPending;
  // React가 disabled를 반영하기 전 같은 순간에 들어오는 요청도 막는다.
  function runAction(action: () => Promise<unknown>) {
    if (actionLock.current || busy) return;
    actionLock.current = true;
    void action().catch(() => {
      // 각 mutation의 오류를 화면에 표시하고 같은 동작을 다시 시도할 수 있게 한다.
    }).finally(() => { actionLock.current = false; });
  }
  function pick(choice: PairChoice) {
    if (!pair.data?.left || !pair.data.right || pair.isFetching || pair.isError || result.isFetching || result.isError) return;
    runAction(() => {
      apply.reset();
      reset.reset();
      return choose.mutateAsync(choice);
    });
  }

  const missingConditions = [pair.error, result.error].some((error) =>
    error instanceof ApiError && (error.code === 'PROFILE_NOT_FOUND' || error.code === 'PREFERENCE_NOT_FOUND'));
  if (missingConditions) {
    return <div className={styles.page}><section className={styles.state}>
      <h1>먼저 내 조건을 입력해 주세요</h1><p>희망 지역·예산이 있어야 두 집을 비교할 수 있어요.</p>
      <Link className={styles.primaryButton} to="/onboarding">내 조건 입력하기</Link>
    </section></div>;
  }

  const r = result.data;
  const p = pair.data;
  const answered = r?.answered ?? p?.answered;
  const minRequired = r?.minRequired ?? p?.minRequired;
  const progress = answered != null && minRequired != null && minRequired > 0 ? Math.min(1, answered / minRequired) : 0;
  const canPick = !busy && !pair.isFetching && !pair.isError && !result.isFetching && !result.isError;
  const hasPair = !!p?.left && !!p.right;

  return (
    <div className={styles.page}>
      <section className={styles.hero} aria-labelledby="tune-title">
        <div>
          <p className={styles.eyebrow}>나에게 더 잘 맞는 추천을 위해</p>
          <h1 id="tune-title">둘 중 더 마음에 드는 집은?</h1>
          <p className={styles.description}>두 집을 비교하고 골라 주세요. {minRequired ?? 5}번 선택하면 추천 기준에 반영할 수 있어요.</p>
        </div>
        {answered != null && minRequired != null && (
          <div className={styles.progressWrap}>
            <div className={styles.progressHeader} aria-live="polite">
              <strong>{hasPair ? `${answered + 1}번째 비교` : r?.ready ? '비교 완료' : '비교 진행 상태'}</strong>
              <span>{answered} / {minRequired}회 완료</span>
            </div>
            <div className={styles.progress} role="progressbar" aria-label="추천 반영까지 비교 진행률"
              aria-valuemin={0} aria-valuemax={minRequired} aria-valuenow={Math.min(answered, minRequired)}
              aria-valuetext={`${answered}회 비교, 최소 ${minRequired}회부터 추천 반영 가능`}>
              {Array.from({ length: 5 }, (_, i) => <span key={i} aria-hidden="true"
                className={i + 1 <= progress * 5 ? styles.progressFilled : undefined} />)}
            </div>
          </div>
        )}
      </section>
      {pair.isLoading && <div className={styles.state} role="status">비교할 집을 고르는 중…</div>}
      {pair.isError && <div className={styles.error} role="alert">
        <p>비교할 집을 불러오지 못했어요. {pair.error.message}</p>
        <button type="button" className={styles.secondaryButton} disabled={pair.isFetching || busy}
          onClick={() => void pair.refetch()}>비교 다시 불러오기</button>
      </div>}
      {result.isLoading && <p className={styles.status} role="status">비교 진행 상태를 확인하는 중…</p>}
      {result.isError && <div className={styles.error} role="alert">
        <p>선택 결과를 불러오지 못했어요. {result.error.message}</p>
        <button type="button" className={styles.secondaryButton} disabled={result.isFetching || busy}
          onClick={() => void result.refetch()}>결과 다시 불러오기</button>
      </div>}
      {p?.left && p.right && !pair.isError && <ComparisonBoard
        key={`${p.left.houseId}-${p.right.houseId}`} left={p.left} right={p.right}
        disabled={!canPick} loading={pair.isFetching || busy} onPick={pick} />}
      <div className={styles.status} role="status">
        {choose.isPending ? '선택을 저장하고 다음 비교를 준비하는 중…' : reset.isPending ? '비교 기록을 초기화하는 중…'
          : hasPair && pair.isFetching ? '다음 비교를 준비하는 중…' : ''}
      </div>
      {choose.isError && <p className={styles.error} role="alert">선택을 저장하지 못했어요. {choose.error.message} 다시 선택해 주세요.</p>}
      {reset.isError && <p className={styles.error} role="alert">초기화하지 못했어요. {reset.error.message} 다시 시도해 주세요.</p>}
      {p && !hasPair && !pair.isError && !pair.isFetching && <section className={styles.state}>
        <h2>더 비교할 집이 없어요</h2>
        <p>{r?.ready ? '지금 후보끼리는 다 비교했어요. 아래 결과를 추천 기준에 적용할 수 있어요.'
          : '지금 후보끼리는 다 비교했어요. 추천 반영에는 더 많은 선택이 필요해요. 내 조건을 확인하거나 처음부터 다시 해 보세요.'}</p>
        <div className={styles.actions}>
          <Link className={styles.secondaryButton} to="/onboarding">내 조건 확인하기</Link>
          {!r?.answered && <button type="button" className={styles.secondaryButton} disabled={busy}
            onClick={() => runAction(() => reset.mutateAsync())}>처음부터</button>}
        </div>
      </section>}
      {r && r.answered > 0 && !result.isError && <section className={styles.result} aria-labelledby="tune-result-title">
        <div className={styles.resultHead}>
          <h2 id="tune-result-title">내 선택으로 찾은 우선순위</h2>
          <span className={styles.resultBadge}>{r.ready ? '추천 반영 가능' : `${Math.max(0, r.minRequired - r.answered)}회 더 비교해 주세요`}</span>
        </div>
        <p className={styles.resultHelp}>{r.ready ? '추천 기준에 반영할 준비가 됐어요. 더 비교하거나 아래 버튼으로 적용해 주세요.'
          : '선택할 때마다 우선순위가 조정돼요. 비교를 더 진행하면 추천 기준에 적용할 수 있어요.'}</p>
        <PriorityBars priorities={r.learnedPriorities} before={r.currentPriorities} />
        {r.agreement != null && <p className={styles.resultHelp}>
          고른 답의 {Math.round(r.agreement * 100)}%를 이 우선순위로 설명할 수 있어요.
          {r.agreement < 0.7 && ' 답이 조금 엇갈려요. 몇 번 더 고르면 더 정확해져요.'}
        </p>}
        {apply.isSuccess && <p className={styles.success} role="status">추천 기준에 적용했어요. 추천 보기에서 확인해 보세요.</p>}
        {apply.isError && <p className={styles.error} role="alert">추천 기준에 적용하지 못했어요. {apply.error.message}</p>}
        <div className={styles.actions}>
          <button type="button" className={styles.secondaryButton} disabled={busy} onClick={() => runAction(() => reset.mutateAsync())}>처음부터</button>
          <Link className={styles.secondaryButton} to="/recommendations">추천 보기</Link>
          <button type="button" className={styles.primaryButton} disabled={!r.ready || busy || result.isFetching}
            onClick={() => runAction(() => apply.mutateAsync())}>{apply.isPending ? '추천 기준에 적용하는 중…' : '이 우선순위로 바꾸기'}</button>
        </div>
      </section>}
    </div>
  );
}

function houseName(h: RecommendedHouse) {
  return h.housingName || h.address || '주택명 미확인';
}

function houseAddress(h: RecommendedHouse) {
  return [regionText(h), h.housingName && h.address].filter(Boolean).join(' · ');
}

function ComparisonBoard({ left, right, disabled, loading, onPick }: {
  left: RecommendedHouse; right: RecommendedHouse; disabled: boolean; loading: boolean; onPick: (choice: PairChoice) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLButtonElement>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);

  useEffect(() => {
    if (!detailsOpen) return;
    const dialog = dialogRef.current!;
    const opener = openerRef.current;
    const previousOverflow = document.body.style.overflow;
    const previousPadding = document.body.style.paddingRight;
    // 스크롤바 공간을 유지해 모달 개폐가 카드 너비·버튼 위치를 바꾸지 않게 한다.
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
    document.body.style.paddingRight = `${parseFloat(getComputedStyle(document.body).paddingRight) + scrollbarWidth}px`;
    document.body.style.overflow = 'hidden';
    dialog.showModal();
    return () => {
      dialog.close();
      document.body.style.overflow = previousOverflow;
      document.body.style.paddingRight = previousPadding;
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    };
  }, [detailsOpen]);

  return <>
    <div className={styles.pair} aria-busy={loading}>
      <CompareCard house={left} side="왼쪽" letter="A" onPick={() => onPick('LEFT')} disabled={disabled} />
      <span className={styles.versus} aria-hidden="true">VS</span>
      <CompareCard house={right} side="오른쪽" letter="B" onPick={() => onPick('RIGHT')} disabled={disabled} />
    </div>
    <div className={styles.choiceFooter}>
      <button ref={openerRef} type="button" className={styles.detailButton} disabled={loading}
        aria-haspopup="dialog" onClick={() => setDetailsOpen(true)}>두 집 조건·추천 근거 비교하기</button>
      <div className={styles.tie}>
        <span>어느 쪽도 더 끌리지 않나요?</span>
        <button type="button" className={styles.secondaryButton} disabled={disabled} onClick={() => onPick('TIE')}>비슷해요</button>
      </div>
    </div>
    <dialog ref={dialogRef} className={styles.dialog} aria-labelledby="tune-detail-title" aria-describedby="tune-detail-description"
      onKeyDown={(event) => {
        if (event.key !== 'Tab') return;
        const targets = event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), [tabindex="0"]');
        const first = targets[0];
        const last = targets[targets.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }}
      onClose={() => setDetailsOpen(false)} onClick={(event) => {
        if (event.target === event.currentTarget) dialogRef.current?.close();
      }}>
      <div className={styles.dialogContent}>
        <header className={styles.dialogHead}>
          <div><p className={styles.dialogEyebrow}>두 집의 추천 근거</p><h2 id="tune-detail-title">상세 조건 비교</h2>
            <p id="tune-detail-description">두 집의 전체 추천 근거를 확인해 보세요.</p></div>
          <button type="button" className={styles.closeButton} aria-label="상세 비교 닫기" onClick={() => dialogRef.current?.close()}>×</button>
        </header>
        <div className={styles.dialogScroll} tabIndex={0} role="region" aria-label="두 주택 상세 추천 근거">
          <p className={styles.legend}>✓ 충족 · △ 부분 충족 · ? 확인 필요 또는 비교 제외 · ✗ 미충족</p>
          <div className={styles.detailColumns}>
            {[left, right].map((house, i) => <section key={house.houseId} className={styles.detailHouse} aria-labelledby={`tune-detail-${i}`}>
              <h3 id={`tune-detail-${i}`}><span className={styles.letter}>{i === 0 ? 'A' : 'B'}</span>{houseName(house)}</h3>
              <p className={styles.detailAddress}>{houseAddress(house)}</p>
              <p className={styles.detailAddress}>{house.agencyName ?? house.agencyCode ?? '기관 미확인'} · {rentalLabel(house)} · {housingLabel(house)}</p>
              {house.reasons.length ? <ReasonList reasons={house.reasons} /> : <p>제공된 추천 근거가 없어요.</p>}
            </section>)}
          </div>
        </div>
        <footer className={styles.dialogFoot}>
          <button type="button" className={styles.secondaryButton} onClick={() => dialogRef.current?.close()}>비교 화면으로 돌아가기</button>
        </footer>
      </div>
    </dialog>
  </>;
}

function CompareCard({ house: h, side, letter, onPick, disabled }: {
  house: RecommendedHouse; side: string; letter: string; onPick: () => void; disabled: boolean;
}) {
  // 순위는 region 판정과 서버의 정확한 문구가 함께 일치할 때만 표시한다. 단어 포함 여부로 추측하지 않는다.
  const region = h.reasons.find((r) => r.item === 'region');
  const rank = region?.text.match(/^희망 지역 일치 \(([123])순위\)$/)?.[1];
  const rankedRegion = rank && (rank === '1' ? region?.mark === 'PASS' : region?.mark === 'PARTIAL');
  const generalMatches = Object.entries(ITEM_LABEL).filter(([item]) => item !== 'region'
    && h.reasons.find((r) => r.item === item)?.mark === 'PASS');
  // 지역 순위가 있으면 한 자리를 남겨 두고, 나머지는 실제 PASS 조건으로만 채운다.
  const highlights = generalMatches.slice(0, rankedRegion ? 1 : 2);
  const regionClass = rank === '1' ? styles.regionFirst : rank === '2' ? styles.regionSecond : styles.regionThird;
  const costFits = h.reasons.some((r) => r.item === 'cost' && r.mark === 'PASS');
  const name = houseName(h);
  const address = houseAddress(h);
  return <article className={styles.house} aria-labelledby={`tune-house-${letter}`}>
    <p className={styles.houseEyebrow}><span className={styles.letter}>{letter}</span><span>{h.agencyName ?? h.agencyCode ?? '기관 미확인'} · {rentalLabel(h)} · {housingLabel(h)}</span></p>
    <h2 className={styles.houseName} id={`tune-house-${letter}`} title={name}>{name}</h2>
    <p className={styles.address} title={address}>
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
        <path d="M20 10c0 5-8 12-8 12S4 15 4 10a8 8 0 1 1 16 0Z" /><circle cx="12" cy="10" r="2.5" />
      </svg>
      <span className={styles.addressText}>{address}</span>
    </p>
    <section className={styles.price} aria-label={`${side} 주택 월 비용`}>
      <div className={styles.priceHead}><span>월 환산 비용</span>{costFits && <span className={styles.priceFit}>✓ 내 예산 이내</span>}</div>
      <p className={`${styles.priceValue} ${h.monthlyCost == null ? styles.missing : ''}`}><Money value={h.monthlyCost} /></p>
    </section>
    <dl className={styles.facts}>
      <div><dt>보증금</dt><dd className={h.deposit == null ? styles.missing : undefined}><Money value={h.deposit} /></dd></div>
      <div><dt>월 임대료</dt><dd className={h.monthlyRent == null ? styles.missing : undefined}><Money value={h.monthlyRent} /></dd></div>
      <div><dt>전용면적</dt><dd className={`${styles.area} ${h.exclusiveArea == null ? styles.missing : ''}`}>{h.exclusiveArea == null ? '정보 없음'
        : area(h.exclusiveArea).split(' ').map((part, i) => <span key={i}>{part}</span>)}</dd></div>
      <div><dt>방 수</dt><dd className={h.roomCount == null ? styles.missing : undefined}>{h.roomCount != null ? `${h.roomCount}개` : '정보 없음'}</dd></div>
    </dl>
    <section className={styles.highlights} aria-label={`${side} 주택과 맞는 조건`}>
      <h3>내 조건과 맞는 점</h3>
      <div className={styles.fitTags}>
        {highlights.map(([item, label]) => <span className={styles.fitTag} key={item}>✓ {label} 조건 충족</span>)}
        {rankedRegion && <span className={`${styles.fitTag} ${regionClass}`}>{rank === '1' && '✓ '}희망 지역 {rank}순위</span>}
        {!highlights.length && !rankedRegion && <p className={styles.resultHelp}>상세 비교에서 조건별 판정을 확인해 주세요.</p>}
      </div>
    </section>
    <div className={styles.cardFooter}>
      <button type="button" className={styles.primaryButton} disabled={disabled} onClick={onPick}
        aria-label={`${side} 주택 ${name}이 더 좋아요`}>이 집이 더 좋아요</button>
    </div>
  </article>;
}

function Money({ value }: { value?: number | null }) {
  // 억 단위 경계에서만 줄바꿈을 허용하고 숫자와 '만 원' 단위는 한 덩어리로 유지한다.
  return <>{won(value).split(/ (?=\d)/).map((part, i) => <span className={styles.numberGroup} key={i}>{i > 0 && ' '}{part}</span>)}</>;
}
