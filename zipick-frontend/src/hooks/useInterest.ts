import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { interestApi } from '../api/interest';
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
