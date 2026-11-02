import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { interestApi } from '../api/interest';
import { clearPending, takePending } from './useAuthGate';
import { useAuth } from './useAuth';

/** 저장한 공고 id 집합과 저장/해제. 로그인 전에는 빈 집합 */
export function useInterest() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const ids = useQuery({ queryKey: ['interest-ids'], queryFn: interestApi.ids, enabled: !!user, staleTime: 60_000 });
  const saved = new Set(ids.data ?? []);

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['interest-ids'] });
    qc.invalidateQueries({ queryKey: ['interest-list'] });
    qc.invalidateQueries({ queryKey: ['interest-schedule'] });
  };
  const save = useMutation({ mutationFn: (noticeId: number) => interestApi.save(noticeId), onSuccess: invalidate });
  const remove = useMutation({ mutationFn: (noticeId: number) => interestApi.remove(noticeId), onSuccess: invalidate });

  return {
    enabled: !!user,
    isSaved: (noticeId: number) => saved.has(noticeId),
    toggle: (noticeId: number) => (saved.has(noticeId) ? remove.mutate(noticeId) : save.mutate(noticeId)),
    busy: save.isPending || remove.isPending,
  };
}

/**
 * 비로그인 상태에서 누른 ★ 를 로그인 직후 이어서 저장한다. Layout에서 한 번만 호출한다.
 *
 * takePending()이 꺼내면서 비우므로 여러 번 불려도 저장은 한 번만 일어난다
 * (개발 모드 StrictMode의 effect 이중 실행 포함).
 * 로그아웃하거나 사용자가 바뀌면 이전 보류 작업은 버린다.
 */
export function usePendingInterestRunner() {
  const { user } = useAuth();
  const qc = useQueryClient();

  useEffect(() => {
    if (!user) {
      clearPending();
      return;
    }
    const pending = takePending();
    if (pending?.type !== 'SAVE_INTEREST') return;

    interestApi
      .save(pending.noticeId)
      .then(() => {
        qc.invalidateQueries({ queryKey: ['interest-ids'] });
        qc.invalidateQueries({ queryKey: ['interest-list'] });
        qc.invalidateQueries({ queryKey: ['interest-schedule'] });
      })
      .catch(() => {
        // 이어 저장은 보조 동작이다. 실패해도 로그인 흐름 자체를 막지 않는다.
      });
  }, [user, qc]);
}
