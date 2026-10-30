import { useSyncExternalStore } from 'react';

export interface AuthUser {
  userId: number;
  email: string;
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
