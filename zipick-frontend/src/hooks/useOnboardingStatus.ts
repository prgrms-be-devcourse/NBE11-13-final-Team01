import { useQuery } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { profileApi } from '../api/profile';
import { useAuth } from './useAuth';

/** UNCHECKED = 확인하지 않음(= 막지 않음) */
export type OnboardingStatus = 'ANONYMOUS' | 'UNCHECKED' | 'LOADING' | 'ERROR' | 'INCOMPLETE' | 'COMPLETE';

/**
 * 맞춤 추천을 쓸 수 있는 상태인지 판정한다.
 *
 * 온보딩 완료 = 기본 정보(profile) + 희망 주거조건(preference)이 **둘 다** 저장된 상태.
 * 두 리소스는 서버에서 따로 저장되고, 없으면 각각 404 PROFILE_NOT_FOUND / PREFERENCE_NOT_FOUND가 온다.
 * 3단계 AHP 질문은 건너뛸 수 있으므로 완료 조건에 넣지 않는다.
 *
 * ⚠️ 조회 중(LOADING)이나 그 밖의 실패(ERROR)를 미완료로 보면 안 된다.
 * 조건을 이미 입력한 사용자에게 온보딩 팝업을 띄우게 된다. 그래서 상태를 네 가지로 나눴다.
 *
 * 쿼리 키는 기존 화면(`['profile']`, `['preference']`)과 같아 캐시를 함께 쓴다.
 */
export function useOnboardingStatus(enabled = true) {
  const { user } = useAuth();
  const active = enabled && !!user;

  const profile = useQuery({ queryKey: ['profile'], queryFn: profileApi.get, retry: false, enabled: active });
  const preference = useQuery({
    queryKey: ['preference'],
    queryFn: profileApi.getPreference,
    retry: false,
    enabled: active,
  });

  const status = resolve();

  function resolve(): OnboardingStatus {
    if (!user) return 'ANONYMOUS';
    if (!enabled) return 'UNCHECKED';
    if (profile.isPending || preference.isPending) return 'LOADING';
    if (isUnexpected(profile.error, 'PROFILE_NOT_FOUND')) return 'ERROR';
    if (isUnexpected(preference.error, 'PREFERENCE_NOT_FOUND')) return 'ERROR';
    if (profile.isError || preference.isError) return 'INCOMPLETE';
    return 'COMPLETE';
  }

  return {
    status,
    retry: () => {
      void profile.refetch();
      void preference.refetch();
    },
  };
}

/** '아직 입력 안 함'(404)이 아닌 실패인지. 네트워크 오류·500 등이 여기에 들어온다. */
function isUnexpected(error: unknown, missingCode: string): boolean {
  if (!error) return false;
  return !(error instanceof ApiError) || error.code !== missingCode;
}
