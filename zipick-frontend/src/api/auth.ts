import { api, refreshOnce, unwrap } from './client';
import { clearSession, setAccessToken, setUser, type AuthUser } from '../hooks/useAuth';

/**
 * 인증 API. 로그인·회원가입 화면(pages/LoginPage, pages/SignupPage)이 이 모듈과 useAuth를 그대로 쓴다.
 * Access Token은 메모리, Refresh Token은 HttpOnly 쿠키라 여기서 쿠키를 직접 다루지 않는다.
 */
export const authApi = {
  // 가입 응답에는 토큰이 없다(userId·email만). 자동 로그인하려면 login을 한 번 더 불러야 한다.
  // verificationToken: 가입 이메일 인증을 마치고 받은 토큰. 서버가 인증을 요구하면(required) 없이는 400 EMAIL_NOT_VERIFIED.
  signup: (email: string, password: string, verificationToken?: string | null) =>
    unwrap<{ userId: number; email: string }>(api.post('/api/auth/signup', { email, password, verificationToken: verificationToken ?? null })),

  // 가입 이메일 인증. required=false 면 이 서버는 인증 없이 가입할 수 있다(로컬 개발) — 코드 입력 단계를 건너뛴다.
  sendVerification: (email: string) =>
    unwrap<{ required: boolean; expiresInMinutes: number; resendAfterSeconds: number }>(
      api.post('/api/auth/email-verifications', { email }),
    ),
  // 맞으면 인증 완료 토큰(10분). 틀리면 400 VERIFICATION_CODE_INVALID, 여러 번 틀리면 429 VERIFICATION_TOO_MANY_ATTEMPTS.
  confirmVerification: (email: string, code: string) =>
    unwrap<{ verificationToken: string; expiresInMinutes: number }>(api.post('/api/auth/email-verifications/confirm', { email, code })),

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
