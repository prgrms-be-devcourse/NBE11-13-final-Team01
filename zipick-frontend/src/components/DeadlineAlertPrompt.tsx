import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { notificationApi } from '../api/notification';
import { useAuth } from '../hooks/useAuth';
import { openGate } from '../hooks/useAuthGate';
import '../design-system/tokens.css';
import styles from './DeadlineAlertPrompt.module.css';

const DEFAULT_DAYS = [3, 1, 0];

function dayText(days: number[]) {
  return days.map((d) => (d === 0 ? 'D-day' : `D-${d}`)).join(', ');
}

/**
 * "마감 전에 알려드릴까요? [켜기]" 한 줄 카드. 관심 공고·일정 화면에 둔다.
 *
 * - 비회원: 켜기 → 로그인 팝업
 * - 받을 이메일이 아직 없음: 켜기 → 알림 설정 페이지로 (주소부터 넣어야 한다)
 * - 받을 곳이 있음: 켜기 → 그 자리에서 알림 켜고 마감 알림을 D-3·D-1·D-day 로 저장
 * - 이미 켜져 있음: "켜짐 · D-3, D-1, D-day" 와 설정 링크
 */
export default function DeadlineAlertPrompt() {
  const { user } = useAuth();
  const nav = useNavigate();
  const qc = useQueryClient();
  const setting = useQuery({ queryKey: ['notification-setting'], queryFn: notificationApi.setting, enabled: !!user });
  const s = setting.data;

  const turnOn = useMutation({
    mutationFn: () =>
      notificationApi.save({
        enabled: true,
        // 주소·광고 동의는 그대로 둔다(null = 유지)
        slackWebhookUrl: null,
        email: null,
        marketingConsent: null,
        deadlineDays: (s && s.deadlineDays.length > 0 ? s.deadlineDays : DEFAULT_DAYS).join(','),
        newMatchEnabled: s?.newMatchEnabled ?? true,
      }),
    onSuccess: (data) => qc.setQueryData(['notification-setting'], data),
  });

  const hasChannel = !!s && (s.slackConfigured || !!s.email);
  const on = !!s && s.enabled && s.deadlineDays.length > 0 && hasChannel;
  const days = s && s.deadlineDays.length > 0 ? s.deadlineDays : DEFAULT_DAYS;

  const onClick = () => {
    if (!user) return openGate('LOGIN');
    if (!hasChannel) return nav('/notifications');
    turnOn.mutate();
  };

  return (
    <section className={`zipick-theme ${styles.card} ${on ? styles.on : ''}`}>
      <span className={styles.icon} aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
      </span>
      <div className={styles.text}>
        <strong>{on ? '마감 알림이 켜져 있어요' : '마감 전에 알려드릴까요?'}</strong>
        <span className={styles.sub}>
          관심 공고 {dayText(days)}에 이메일로 알림
          {turnOn.isError && ' · 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.'}
        </span>
      </div>
      {on ? (
        <Link to="/notifications" className={styles.ghost}>설정</Link>
      ) : (
        <button type="button" className={styles.primary} onClick={onClick} disabled={turnOn.isPending}>켜기</button>
      )}
    </section>
  );
}
