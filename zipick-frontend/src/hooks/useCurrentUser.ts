import { useCallback, useSyncExternalStore } from 'react';

// 로그인(한채희 담당)이 붙기 전 임시 사용자 전환. localStorage에 사용자 ID를 둔다.
const KEY = 'zipick.devUserId';
const listeners = new Set<() => void>();

export function getCurrentUserId(): number {
  const raw = localStorage.getItem(KEY);
  const n = raw ? Number(raw) : 1;
  return Number.isFinite(n) && n > 0 ? n : 1;
}

function setCurrentUserId(id: number) {
  localStorage.setItem(KEY, String(id));
  listeners.forEach((l) => l());
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function useCurrentUser() {
  const userId = useSyncExternalStore(subscribe, getCurrentUserId);
  const setUserId = useCallback((id: number) => setCurrentUserId(id), []);
  return { userId, setUserId };
}
