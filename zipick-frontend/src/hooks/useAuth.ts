import { useSyncExternalStore } from 'react';

/** 인증 수단. 계정 메뉴의 가입 유형·이메일 표시를 여기에 맞춘다. */
export type AuthProvider = 'LOCAL' | 'KAKAO';

export interface AuthUser {
  userId: number;
  /**
   * 가입 이메일. 카카오가 검증된 이메일을 주지 않으면 null 이다.
   * 서버가 가짜 이메일을 만들지 않으므로 화면에서 없는 경우를 직접 다뤄야 한다.
   */
  email: string | null;
  provider: AuthProvider;
  /** 운영자용 항목(슬랙 알림 등)을 보일지만 정한다. 권한 검사는 서버가 한다 */
  role?: 'ROLE_USER' | 'ROLE_ADMIN';
}

export function isAdmin(user: AuthUser | null): boolean {
  return user?.role === 'ROLE_ADMIN';
}

// Access Token은 메모리에만 둔다. localStorage에 두면 XSS로 그대로 샌다.
// 새로고침하면 사라지지만, HttpOnly 쿠키의 Refresh Token으로 앱 시작 시 다시 받는다.
let accessToken: string | null = null;
let user: AuthUser | null = null;

const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((l) => l());
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null) {
  accessToken = token;
  emit();
}

export function setUser(next: AuthUser | null) {
  user = next;
  emit();
}

export function clearSession() {
  accessToken = null;
  user = null;
  emit();
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => {
    listeners.delete(l);
  };
}

function getSnapshot(): AuthUser | null {
  return user;
}

/** 로그인한 사용자. 정식 로그인 화면이 생겨도 이 훅을 그대로 쓰면 된다. */
export function useAuth() {
  const current = useSyncExternalStore(subscribe, getSnapshot);
  return { user: current, isAuthenticated: current !== null };
}
