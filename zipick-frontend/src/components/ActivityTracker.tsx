import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { api } from '../api/client';

/**
 * 화면 진입(PAGE_VIEW)·클릭(CLICK)을 모아 POST /api/v1/activity 로 보낸다. 운영자 화면 "사용자 행동" 에서 본다.
 * - 운영자 화면(/admin)은 기록하지 않는다.
 * - 비회원도 기록하되, 같은 브라우저는 localStorage 의 임의 id(anonymousId)로 묶는다. 로그인하면 서버가 토큰의 userId 를 함께 남긴다.
 * - 5초마다, 또는 탭을 떠날 때 sendBeacon 으로 묶어 보낸다. 실패해도 화면에는 아무 영향이 없다.
 * - 입력값·비밀번호는 보내지 않는다. 클릭은 요소의 이름(data-track > aria-label > 글자 앞 80자)만 남긴다.
 */

interface Event { type: 'PAGE_VIEW' | 'CLICK'; path: string; label?: string; target?: string; at: string }

const ANON_KEY = 'zipick_aid';
const FLUSH_MS = 5000;
const MAX_QUEUE = 50;

function anonymousId(): string | undefined {
  try {
    let id = localStorage.getItem(ANON_KEY);
    if (!id) {
      id = (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`).slice(0, 64);
      localStorage.setItem(ANON_KEY, id);
    }
    return id;
  } catch {
    return undefined;
  }
}

/** 클릭한 요소에서 사람이 알아볼 이름과 대상 id 를 뽑는다 */
function describe(el: Element): { label: string; target?: string } | null {
  const hit = el.closest<HTMLElement>('a, button, [role="button"], summary, input[type="submit"], [data-track]');
  if (!hit) return null;
  const tracked = hit.closest<HTMLElement>('[data-track]');
  let label = tracked?.dataset.track || hit.getAttribute('aria-label') || hit.getAttribute('title') || '';
  if (!label) label = (hit.textContent || '').replace(/\s+/g, ' ').trim();
  if (!label && hit instanceof HTMLAnchorElement) label = hit.getAttribute('href') || '';
  if (!label) return null;
  let target: string | undefined;
  const href = hit instanceof HTMLAnchorElement ? hit.getAttribute('href') : null;
  const m = href?.match(/\/houses\/(\d+)|\/interest-notices\/(\d+)/);
  if (m) target = m[1] ? `housing:${m[1]}` : `notice:${m[2]}`;
  else if (href && href.startsWith('/')) target = href.slice(0, 200);
  const dataTarget = hit.closest<HTMLElement>('[data-track-target]')?.dataset.trackTarget;
  if (dataTarget) target = dataTarget;
  return { label: label.slice(0, 80), target };
}

export default function ActivityTracker() {
  const location = useLocation();
  const queue = useRef<Event[]>([]);
  const timer = useRef<number | null>(null);

  useEffect(() => {
    const flush = (beacon = false) => {
      if (queue.current.length === 0) return;
      const events = queue.current.splice(0, MAX_QUEUE);
      const body = JSON.stringify({ anonymousId: anonymousId(), events });
      if (beacon && navigator.sendBeacon) {
        // 탭을 떠날 때. 헤더를 못 붙이므로 토큰 없이 가고, 서버는 anonymousId 로만 묶는다
        const base = (import.meta.env.VITE_API_BASE_URL as string | undefined) || '';
        navigator.sendBeacon(`${base}/api/v1/activity`, new Blob([body], { type: 'application/json' }));
        return;
      }
      api.post('/api/v1/activity', body, { headers: { 'Content-Type': 'application/json' } }).catch(() => {
        /* 기록 실패는 무시한다 */
      });
    };
    const schedule = () => {
      if (timer.current != null) return;
      timer.current = window.setTimeout(() => { timer.current = null; flush(); }, FLUSH_MS);
    };
    const push = (e: Event) => {
      queue.current.push(e);
      if (queue.current.length >= MAX_QUEUE) flush();
      else schedule();
    };

    const onClick = (ev: MouseEvent) => {
      if (location.pathname.startsWith('/admin')) return;
      const el = ev.target instanceof Element ? ev.target : null;
      if (!el) return;
      const d = describe(el);
      if (!d) return;
      push({ type: 'CLICK', path: location.pathname + location.search, label: d.label, target: d.target, at: new Date().toISOString() });
    };
    const onHide = () => { if (document.visibilityState === 'hidden') flush(true); };

    document.addEventListener('click', onClick, true);
    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('pagehide', () => flush(true));
    return () => {
      document.removeEventListener('click', onClick, true);
      document.removeEventListener('visibilitychange', onHide);
      if (timer.current != null) { window.clearTimeout(timer.current); timer.current = null; }
      flush();
    };
    // location 이 바뀔 때마다 클릭 핸들러가 새 경로를 보게 다시 건다
  }, [location.pathname, location.search]);

  useEffect(() => {
    if (location.pathname.startsWith('/admin')) return;
    const path = location.pathname + location.search;
    // 개발 모드(StrictMode)의 effect 이중 실행이나 같은 화면 재렌더로 같은 진입이 연달아 쌓이는 것을 막는다
    const last = queue.current[queue.current.length - 1];
    if (last && last.type === 'PAGE_VIEW' && last.path === path && Date.now() - new Date(last.at).getTime() < 1000) return;
    queue.current.push({ type: 'PAGE_VIEW', path, at: new Date().toISOString() });
    if (timer.current == null) {
      timer.current = window.setTimeout(() => {
        timer.current = null;
        const events = queue.current.splice(0, MAX_QUEUE);
        if (events.length === 0) return;
        api.post('/api/v1/activity', JSON.stringify({ anonymousId: anonymousId(), events }), { headers: { 'Content-Type': 'application/json' } }).catch(() => {});
      }, FLUSH_MS);
    }
  }, [location.pathname, location.search]);

  return null;
}
