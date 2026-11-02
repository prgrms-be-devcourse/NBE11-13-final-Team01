import type { AxiosError } from 'axios';
import type { ApiResponse } from '../types';

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
