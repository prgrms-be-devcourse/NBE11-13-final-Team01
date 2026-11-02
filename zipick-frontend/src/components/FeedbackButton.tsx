import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { recommendationApi } from '../api/recommendation';
import type { FeedbackReason } from '../types';
import { REASON_LABEL } from '../utils/format';

interface Props {
  houseId: number;
  onHidden: (houseId: number) => void;
}

/** "별로예요" → 이유 선택 → 숨김. 같은 이유가 반복되면 서버가 조건 변경을 제안한다. */
export default function FeedbackButton({ houseId, onHidden }: Props) {
  const [open, setOpen] = useState(false);
  const [suggestion, setSuggestion] = useState<string | null>(null);

  const m = useMutation({
    mutationFn: (reason: FeedbackReason) => recommendationApi.feedback(houseId, 'DISLIKE', reason),
    onSuccess: (res) => {
      setOpen(false);
      if (res.suggestion) {
        setSuggestion(res.suggestion.message);
      } else {
        onHidden(houseId);
      }
    },
  });

  if (suggestion) {
    return (
      <div className="suggest">
        <p>{suggestion}</p>
        <small>조건 변경은 "내 조건" 화면에서 직접 할 수 있어요.</small>
        <button
          className="btn btn--small"
          onClick={() => {
            setSuggestion(null);
            onHidden(houseId);
          }}
        >
          확인
        </button>
      </div>
    );
  }

  return (
    <div className="feedback">
      {!open ? (
        <button className="btn btn--ghost btn--small" onClick={() => setOpen(true)}>
          별로예요
        </button>
      ) : (
        <div className="feedback__reasons">
          <span>이유를 골라 주세요</span>
          {(Object.keys(REASON_LABEL) as FeedbackReason[]).map((r) => (
            <button key={r} className="chip" disabled={m.isPending} onClick={() => m.mutate(r)}>
              {REASON_LABEL[r]}
            </button>
          ))}
          <button className="btn btn--ghost btn--small" onClick={() => setOpen(false)}>
            취소
          </button>
        </div>
      )}
      {m.isError && <span className="error">{(m.error as Error).message}</span>}
    </div>
  );
}
