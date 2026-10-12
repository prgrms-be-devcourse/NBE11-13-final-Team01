import axios, { AxiosError } from 'axios';
import type { ApiResponse } from '../types';
import { getCurrentUserId } from '../hooks/useCurrentUser';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10_000,
});

// 임시 인증: 로그인(한채희 담당)이 붙기 전까지 X-User-Id 헤더로 사용자를 식별한다.
// JWT가 완성되면 이 인터셉터만 Authorization 헤더로 바꾸면 된다.
api.interceptors.request.use((config) => {
  const userId = getCurrentUserId();
  if (userId) config.headers['X-User-Id'] = String(userId);
  return config;
});

export class ApiError extends Error {
  code: string;
  fields?: Record<string, string>;
  status?: number;
  constructor(code: string, message: string, status?: number, fields?: Record<string, string>) {
    super(message);
    this.code = code;
    this.status = status;
    this.fields = fields;
  }
}

/** 공통 응답 형식을 풀어 data만 돌려주고, 실패는 ApiError로 던진다. */
export async function unwrap<T>(p: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  try {
    const res = await p;
    if (!res.data.success || res.data.error) {
      const e = res.data.error;
      throw new ApiError(e?.code ?? 'UNKNOWN', e?.message ?? '알 수 없는 오류', undefined, e?.fields);
    }
    return res.data.data;
  } catch (err) {
    if (err instanceof ApiError) throw err;
    const ax = err as AxiosError<ApiResponse<unknown>>;
    const body = ax.response?.data;
    if (body?.error) {
      throw new ApiError(body.error.code, body.error.message, ax.response?.status, body.error.fields);
    }
    throw new ApiError('NETWORK', '서버에 연결할 수 없습니다.', ax.response?.status);
  }
}
