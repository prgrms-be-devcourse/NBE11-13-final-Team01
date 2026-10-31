import { useEffect, useId, useRef, useState } from 'react';
import FilterPanel from '../../components/FilterPanel';
import { EMPTY_FILTER, type HouseFilter } from '../../types';
import { filterToSearch } from '../../utils/filterParams';
import styles from './MapFilterDrawer.module.css';

interface Props {
  filter: HouseFilter;
  contextKey: string;
  regionOptions: { code: string; name: string }[];
  onApply: (filter: HouseFilter) => Promise<boolean>;
}

function copyFilter(filter: HouseFilter): HouseFilter {
  return { ...filter, regions: [...filter.regions], housingTypes: [...filter.housingTypes], statuses: [...filter.statuses], agencies: [...filter.agencies], rentalTypes: [...filter.rentalTypes] };
}

function filterKey(filter: HouseFilter) {
  return JSON.stringify(filterToSearch({ ...filter, regions: [...filter.regions].sort(), housingTypes: [...filter.housingTypes].sort(), statuses: [...filter.statuses].sort(), agencies: [...filter.agencies].sort(), rentalTypes: [...filter.rentalTypes].sort() }));
}

function rangeError(filter: HouseFilter): string | null {
  for (const [min, max, label, integer] of [
    [filter.depositMin, filter.depositMax, '보증금', true],
    [filter.rentMin, filter.rentMax, '월 임대료', true],
    [filter.areaMin, filter.areaMax, '전용면적', false],
  ] as const) {
    if ([min, max].some((value) => value !== undefined && (!Number.isFinite(value) || value < 0 || (integer && !Number.isInteger(value))))) return `${label}에 0 이상의 ${integer ? '정수' : '숫자'}를 입력해 주세요.`;
    if (min !== undefined && max !== undefined && min > max) return `${label}의 최소값이 최대값보다 클 수 없어요.`;
  }
  return null;
}

export default function MapFilterDrawer({ filter, contextKey, regionOptions, onApply }: Props) {
  const [session, setSession] = useState<string | null>(null);
  const [draft, setDraft] = useState(() => copyFilter(filter));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resetVersion, setResetVersion] = useState(0);
  const [present, setPresent] = useState(false);
  const [shown, setShown] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const applyingRef = useRef(false);
  const mountedRef = useRef(true);
  const titleId = useId();
  const descriptionId = useId();
  const open = session !== null && session === contextKey;
  const openRef = useRef(open);
  openRef.current = open;
  const dirty = filterKey(draft) !== filterKey(filter);

  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false; }; }, []);
  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)');
    const update = () => setReducedMotion(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    if (open && !present) { setShown(false); setPresent(true); }
  }, [open, present]);
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!present || !dialog) return;
    // 스크롤바 공간도 함께 보존해 모달을 열어도 지도와 패널의 폭이 바뀌지 않게 한다.
    const bodyOverflow = document.body.style.overflow;
    const gutter = document.documentElement.style.scrollbarGutter;
    const scrollbarGap = window.innerWidth - document.documentElement.clientWidth;
    dialog.style.setProperty('--filter-scrollbar-gap', `${scrollbarGap}px`);
    // stable gutter 아래에서는 vw도 좁아질 수 있어 드로어에만 실제 뷰포트 폭을 전달한다.
    const measureViewport = () => dialog.style.setProperty('--filter-viewport-width', `${window.innerWidth}px`);
    measureViewport();
    window.addEventListener('resize', measureViewport);
    if (scrollbarGap > 0) document.documentElement.style.scrollbarGutter = 'stable';
    document.body.style.overflow = 'hidden';
    dialog.showModal();
    closeRef.current?.focus();
    return () => {
      dialog.close();
      window.removeEventListener('resize', measureViewport);
      document.body.style.overflow = bodyOverflow;
      document.documentElement.style.scrollbarGutter = gutter;
      triggerRef.current?.focus({ preventScroll: true });
    };
  }, [present]);
  useEffect(() => {
    if (!present) return;
    let firstFrame: number | undefined;
    let secondFrame: number | undefined;
    let closeTimer: ReturnType<typeof setTimeout> | undefined;
    if (open) {
      if (reducedMotion) setShown(true);
      else {
        // 첫 위치를 그린 뒤 전환한다. 재열기 시 이전 닫기 작업은 cleanup에서 취소한다.
        firstFrame = requestAnimationFrame(() => {
          secondFrame = requestAnimationFrame(() => { if (openRef.current) setShown(true); });
        });
      }
    } else {
      setShown(false);
      if (reducedMotion) setPresent(false);
      // 전환이 시작되기 전에 닫힌 경우에도 모달/스크롤 잠금이 남지 않게 한다.
      else closeTimer = setTimeout(() => { if (!openRef.current) setPresent(false); }, 270);
    }
    return () => {
      if (firstFrame !== undefined) cancelAnimationFrame(firstFrame);
      if (secondFrame !== undefined) cancelAnimationFrame(secondFrame);
      if (closeTimer !== undefined) clearTimeout(closeTimer);
    };
  }, [open, present, reducedMotion]);

  const close = () => { openRef.current = false; setSession(null); };
  const apply = async () => {
    if (applyingRef.current) return;
    const message = rangeError(draft);
    if (message) { setError(message); return; }
    if (!dirty) { close(); return; }
    applyingRef.current = true;
    setPending(true);
    setError(null);
    try {
      if (await onApply(copyFilter(draft)) && mountedRef.current) close();
    } catch {
      if (mountedRef.current) setError('필터를 적용하지 못했어요. 기존 지도는 유지됩니다. 잠시 후 다시 적용해 주세요.');
    } finally {
      applyingRef.current = false;
      if (mountedRef.current) setPending(false);
    }
  };

  return (
    <div className="map-filter">
      <button ref={triggerRef} type="button" className="map-filter__trigger" disabled={pending} aria-haspopup="dialog" aria-expanded={open} aria-controls={titleId + '-dialog'} onClick={() => {
        if (open) close();
        else {
          openRef.current = true;
          if (present) setShown(true);
          setDraft(copyFilter(filter));
          setError(null);
          setResetVersion((value) => value + 1);
          bodyRef.current?.scrollTo(0, 0);
          closeRef.current?.focus({ preventScroll: true });
          setSession(contextKey);
        }
      }}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 7h16M7 12h10M10 17h4" /></svg>
        필터
      </button>
      <dialog ref={dialogRef} id={titleId + '-dialog'} className={styles.drawer} data-state={!present ? 'closed' : open ? (shown ? 'open' : 'opening') : 'closing'} aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId} onTransitionEnd={(event) => {
        if (event.target === event.currentTarget && event.propertyName === 'transform' && !openRef.current) setPresent(false);
      }} onKeyDown={(event) => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); return; }
        if (event.key !== 'Tab') return;
        const controls = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled)')].filter((node) => node.getClientRects().length > 0);
        const first = controls[0];
        const last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }} onCancel={(event) => { event.preventDefault(); close(); }} onClick={(event) => {
        if (event.target !== event.currentTarget) return;
        const rect = event.currentTarget.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) close();
      }}>
        {present && <form className={styles.form} onSubmit={(event) => { event.preventDefault(); if (open) void apply(); }}>
          <header className={styles.header}>
            <div><h2 id={titleId}>전체 공고 필터</h2><p id={descriptionId}>조건을 고른 뒤 적용해 주세요.</p></div>
            <button ref={closeRef} type="button" className={styles.close} aria-label="필터 닫기" onClick={close}><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>
          </header>
          <div ref={bodyRef} className={styles.body}>
            <fieldset className={styles.fields} disabled={pending}>
              <FilterPanel key={resetVersion} filter={draft} onChange={(next) => { if (open) { setDraft(next); setError(null); } }} regionOptions={regionOptions} />
            </fieldset>
          </div>
          <footer className={styles.footer}>
            <p className={error ? styles.error : styles.hint} role={error ? 'alert' : 'status'}>{error ?? (dirty ? '변경한 조건은 적용하기를 누른 뒤 반영돼요.' : '현재 지도에 적용된 필터 조건입니다.')}</p>
            <div className={styles.actions}>
              <button type="button" className={styles.reset} disabled={pending} onClick={() => { if (open) { setDraft(copyFilter(EMPTY_FILTER)); setError(null); setResetVersion((value) => value + 1); } }}>초기화</button>
              <button type="submit" className={styles.apply} disabled={pending}>{pending ? '적용 중…' : '적용하기'}</button>
            </div>
          </footer>
        </form>}
      </dialog>
    </div>
  );
}
