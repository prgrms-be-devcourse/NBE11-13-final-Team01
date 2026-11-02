import { useSyncExternalStore } from 'react';

/** 공통 안내 팝업의 종류. LOGIN = 로그인 필요, ONBOARDING = 내 조건 입력 필요 */
export type GateReason = 'LOGIN' | 'ONBOARDING';

/** 로그인한 뒤 이어서 할 작업. 지금은 관심 공고 저장 하나뿐이다. */
export interface PendingIntent {
  type: 'SAVE_INTEREST';
  noticeId: number;
}

interface Store {
  gate: GateReason | null;
  pending: PendingIntent | null;
}

// useAuth와 같은 방식(모듈 스토어 + useSyncExternalStore)으로 둔다.
// 보류 작업도 Access Token과 마찬가지로 메모리에만 둔다. 새로고침하면 사라지는 편이 맞다.
let store: Store = { gate: null, pending: null };

const listeners = new Set<() => void>();

function set(next: Store) {
  store = next;
  listeners.forEach((l) => l());
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => {
    listeners.delete(l);
  };
}

/** 로그인·온보딩 안내 팝업을 연다. */
export function openGate(reason: GateReason) {
  if (store.gate === reason) return;
  set({ ...store, gate: reason });
}

export function closeGate() {
  if (store.gate === null) return;
  set({ ...store, gate: null });
}

/**
 * 비로그인 상태에서 개인화 기능을 눌렀을 때. 로그인 팝업을 띄우고,
 * 로그인에 성공하면 이어서 할 작업을 함께 맡겨 둔다.
 */
export function requireLogin(intent?: PendingIntent) {
  set({ gate: 'LOGIN', pending: intent ?? null });
}

/**
 * 보류 작업을 꺼낸다. **꺼내면서 비우므로** 여러 번 호출해도 작업은 한 번만 실행된다
 * (개발 모드의 StrictMode 이중 실행 포함).
 */
export function takePending(): PendingIntent | null {
  const pending = store.pending;
  if (pending) set({ ...store, pending: null });
  return pending;
}

/** 꺼내지 않고 들여다보기만 한다. 카카오로 떠나기 전 작업을 따로 보관할 때 쓴다. */
export function peekPending(): PendingIntent | null {
  return store.pending;
}

/**
 * 카카오 로그인에서 돌아온 뒤 보류 작업을 되돌려 놓는다.
 *
 * 외부 페이지를 다녀오는 사이 이 모듈의 메모리 스토어가 초기화되므로,
 * 직렬화해 두었던 작업 정보를 여기서 다시 넣어 준다.
 * 이후 처리는 기존과 같다 — usePendingInterestRunner 가 takePending()으로 한 번만 꺼내 실행한다.
 */
export function restorePending(intent: PendingIntent) {
  set({ ...store, pending: intent });
}

/** 로그아웃·사용자 변경 때 이전 보류 작업을 버린다. */
export function clearPending() {
  if (store.pending === null) return;
  set({ ...store, pending: null });
}

export function useAuthGate() {
  return useSyncExternalStore(subscribe, () => store);
}

// 카카오 콜백은 토큰을 받는 중간 경유지다. 복귀 대상이 되면 로그인 후 다시 콜백으로 돌아가 버린다.
const AUTH_PATHS = ['/login', '/signup', '/oauth/kakao/callback'];

/** 로그인 후 돌아올 경로. 로그인·가입 화면 자신은 돌아올 곳이 아니다. */
export function returnPath(location: { pathname: string; search: string }): string | undefined {
  if (AUTH_PATHS.includes(location.pathname)) return undefined;
  return location.pathname + location.search;
}
