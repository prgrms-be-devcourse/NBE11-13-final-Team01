import { api, refreshOnce, unwrap } from './client';
import { clearSession, setAccessToken, setUser, type AuthUser } from '../hooks/useAuth';

/**
 * 인증 API. 정식 로그인 화면을 만들 때 이 모듈과 useAuth를 그대로 재사용하면 된다.
 * Access Token은 메모리, Refresh Token은 HttpOnly 쿠키라 여기서 쿠키를 직접 다루지 않는다.
 */
export const authApi = {
  login: (email: string, password: string) =>
    unwrap<{ accessToken: string }>(api.post('/api/auth/login', { email, password })),

  // 204 No Content라 공통 응답 본문이 없다. unwrap을 쓰지 않는다.
  logout: () => api.post('/api/auth/logout').then(() => undefined),

  me: () => unwrap<AuthUser>(api.get('/api/users/me')),
};

export async function login(email: string, password: string) {
  const { accessToken } = await authApi.login(email, password);
  setAccessToken(accessToken); // me() 요청에 바로 실리도록 먼저 넣는다
  setUser(await authApi.me());
}

export async function logout() {
  try {
    await authApi.logout();
  } catch {
    // 서버 호출이 실패해도(네트워크 오류 등) 로그아웃은 되돌리지 않는다. 세션은 아래에서 정리된다.
  } finally {
    clearSession();
  }
}

/** 앱 시작 시 1회. 쿠키의 Refresh Token으로 로그인을 되살린다. 실패하면 그냥 비로그인 상태다. */
export async function bootstrapAuth() {
  const token = await refreshOnce();
  if (!token) return;

  try {
    setUser(await authApi.me());
  } catch {
    clearSession();
  }
}
