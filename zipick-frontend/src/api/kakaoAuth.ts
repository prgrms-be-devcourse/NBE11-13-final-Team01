import { api, unwrap } from './client';
import { authApi } from './auth';
import { setAccessToken, setUser } from '../hooks/useAuth';
import { restorePending, type PendingIntent } from '../hooks/useAuthGate';

/**
 * 카카오 로그인.
 *
 * 카카오 동의 화면으로 나갔다 오는 사이 앱이 통째로 언로드되므로,
 * 메모리에만 있던 "복귀 경로"와 "보류 작업"이 사라진다.
 * 왕복 동안만 sessionStorage 에 맡겨 두고 돌아오자마자 꺼내 쓴다.
 *
 * 토큰은 여기에 저장하지 않는다. 일반 로그인과 똑같이 Access Token 은 메모리,
 * Refresh Token 은 HttpOnly 쿠키다.
 */

const ROUND_TRIP_KEY = 'zipick.kakao.roundtrip';

/** 카카오로 떠나기 직전의 화면 상태. 직렬화 가능한 값만 담는다(함수·콜백은 담지 않는다). */
interface RoundTrip {
  /** 로그인 후 돌아갈 경로 */
  from?: string;
  /** 로그인 후 이어서 할 작업 (관심 공고 저장 등) */
  pending?: PendingIntent | null;
}

export const KAKAO_CALLBACK_PATH = '/oauth/kakao/callback';

function saveRoundTrip(value: RoundTrip) {
  try {
    sessionStorage.setItem(ROUND_TRIP_KEY, JSON.stringify(value));
  } catch {
    // 시크릿 모드 등에서 막힐 수 있다. 복귀 경로를 잃을 뿐 로그인 자체는 된다.
  }
}

/** 꺼내면서 지운다. 다음 로그인에 지난 번 복귀 경로가 따라붙지 않게 한다. */
function takeRoundTrip(): RoundTrip {
  try {
    const raw = sessionStorage.getItem(ROUND_TRIP_KEY);
    sessionStorage.removeItem(ROUND_TRIP_KEY);
    return raw ? (JSON.parse(raw) as RoundTrip) : {};
  } catch {
    return {};
  }
}

export const kakaoAuthApi = {
  // state 는 서버가 만들어 HttpOnly 쿠키에 담아 준다. 프론트는 주소만 받는다.
  authorize: () => unwrap<{ authorizeUrl: string }>(api.get('/api/auth/kakao/authorize')),

  // 응답 형태가 일반 로그인과 같다(accessToken + refreshToken 쿠키).
  login: (code: string, state: string) =>
    unwrap<{ accessToken: string }>(api.post('/api/auth/kakao/login', { code, state })),
};

/** 카카오 동의 화면으로 이동한다. 이 함수가 끝나면 페이지가 떠난다. */
export async function startKakaoLogin(roundTrip: RoundTrip) {
  const { authorizeUrl } = await kakaoAuthApi.authorize();
  saveRoundTrip(roundTrip);
  window.location.assign(authorizeUrl);
}

/**
 * 콜백에서 받은 인가 코드로 로그인을 마친다.
 * 성공하면 떠나기 전의 보류 작업을 메모리 스토어에 되돌려 놓고 복귀 경로를 알려준다.
 */
export async function finishKakaoLogin(code: string, state: string): Promise<{ from?: string }> {
  const { accessToken } = await kakaoAuthApi.login(code, state);

  setAccessToken(accessToken); // me() 요청에 바로 실리도록 먼저 넣는다
  setUser(await authApi.me());

  const roundTrip = takeRoundTrip();
  if (roundTrip.pending) restorePending(roundTrip.pending);

  return { from: roundTrip.from };
}

/**
 * 로그인을 끝내지 못하고 돌아왔을 때(동의 취소·오류).
 *
 * 보류 작업은 그대로 되돌려 놓는다. 카카오를 취소하고 이메일로 로그인해도
 * 처음 누른 관심 공고가 그대로 저장되게 하기 위해서다.
 */
export function abortKakaoLogin(): { from?: string } {
  const roundTrip = takeRoundTrip();
  if (roundTrip.pending) restorePending(roundTrip.pending);
  return { from: roundTrip.from };
}
