import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import type { ApiResponse } from '../types';
import { clearSession, getAccessToken, setAccessToken } from '../hooks/useAuth';

// 기존 import 경로(`from '../api/client'`)를 유지하려고 다시 내보낸다.
export { ApiError, unwrap } from './http';

const baseURL = import.meta.env.VITE_API_BASE_URL || '';

export const api = axios.create({
  baseURL,
  timeout: 10_000,
  // Refresh Token(HttpOnly 쿠키)을 주고받으려면 필요하다. 프록시를 쓰면 동일 출처라 없어도 되지만,
  // VITE_API_BASE_URL을 채워 교차 출처가 되는 순간 쿠키가 빠지므로 항상 켜 둔다.
  withCredentials: true,
});

// 재발급 전용 인스턴스. 인터셉터를 달지 않아 reissue 응답이 다시 401 처리를 타지 않는다.
const refreshHttp = axios.create({ baseURL, timeout: 10_000, withCredentials: true });

// 자동 재발급에서 뺄 엔드포인트.
// login/signup의 401은 토큰 만료가 아니라 자격 증명 오류이고, reissue는 재발급 그 자체다.
// logout은 Access Token이 필요한 보호 API이므로 일반 요청과 똑같이 재발급 후 재시도한다.
const NO_REFRESH = ['/api/auth/login', '/api/auth/signup', '/api/auth/reissue'];

api.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Refresh Token Rotation이라 동시에 여러 번 재발급하면 서로를 무효화한다.
// 먼저 시작한 요청 하나만 보내고 나머지는 그 결과를 기다린다(single-flight).
let inflight: Promise<string | null> | null = null;

export function refreshOnce(): Promise<string | null> {
  if (!inflight) {
    inflight = refreshHttp
      .post<ApiResponse<{ accessToken: string }>>('/api/auth/reissue')
      .then((res) => {
        const token = res.data?.data?.accessToken ?? null;
        if (token) setAccessToken(token);
        return token;
      })
      .catch(() => null)
      .finally(() => {
        inflight = null;
      });
  }
  return inflight;
}

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

api.interceptors.response.use(undefined, async (error: AxiosError) => {
  const config = error.config as RetriableConfig | undefined;

  const skip =
    !config ||
    error.response?.status !== 401 ||
    config._retry ||
    NO_REFRESH.some((path) => (config.url ?? '').startsWith(path));

  if (skip) throw error;

  config._retry = true; // 재시도는 요청당 최대 1회

  const token = await refreshOnce();
  if (!token) {
    clearSession();
    throw error;
  }

  // 재요청하면 요청 인터셉터가 새 토큰을 다시 붙인다.
  return api(config);
});
