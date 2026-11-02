import { useEffect, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { openGate, returnPath } from '../hooks/useAuthGate';
import { useAuth } from '../hooks/useAuth';
import { useOnboardingStatus } from '../hooks/useOnboardingStatus';
import AuthGateIcon from './AuthGateIcon';
import styles from './AccessGate.module.css';
import '../design-system/tokens.css';

interface Props {
  /** LOGIN: 로그인만 필요 / ONBOARDING: 로그인 + 내 조건 입력까지 필요 */
  need: 'LOGIN' | 'ONBOARDING';
  children: ReactNode;
}

/**
 * 개인화 화면 앞에 두는 공통 관문. 조건이 모자라면 공통 팝업을 띄우고 안내 화면을 대신 보여 준다.
 * 비회원도 볼 수 있는 화면(/map, /houses)에는 쓰지 않는다.
 *
 * 로그인 복구(bootstrapAuth)는 main.tsx가 첫 렌더 전에 끝내므로, 여기서는 이미 판정 가능한 상태다.
 */
export default function AccessGate({ need, children }: Props) {
  const { user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const from = returnPath(location);
  // 로그인만 필요한 화면에서는 온보딩 조회를 아예 하지 않는다.
  const { status, retry } = useOnboardingStatus(need === 'ONBOARDING');

  const blockedBy = !user ? 'LOGIN' : status === 'INCOMPLETE' ? 'ONBOARDING' : null;

  useEffect(() => {
    if (blockedBy) openGate(blockedBy);
  }, [blockedBy]);

  if (blockedBy === 'LOGIN') {
    return (
      <div className={`zipick-theme ${styles.card}`}>
        <span className={styles.icon}><AuthGateIcon kind="LOGIN" /></span>
        <h2 className={styles.title}>로그인하면 이용할 수 있어요</h2>
        <p className={styles.description}>로그인하고 관심 공고와 일정, 맞춤 기능을 이용해 보세요.</p>
        <button type="button" className={styles.action} onClick={() => navigate('/login', { state: from ? { from } : undefined })}>
          로그인하기
        </button>
      </div>
    );
  }

  if (status === 'LOADING') return <p className={`zipick-theme ${styles.loading}`} role="status">내 조건을 확인하는 중…</p>;

  // 조회 실패를 '미입력'으로 보면 조건을 이미 넣은 사용자에게 온보딩을 다시 시키게 된다. 따로 안내한다.
  if (status === 'ERROR') {
    return (
      <div className={`zipick-theme ${styles.card}`}>
        <span className={styles.icon}><AuthGateIcon kind="ERROR" /></span>
        <h2 className={styles.title}>내 조건을 확인하지 못했어요</h2>
        <p className={styles.description}>잠시 후 다시 시도해 주세요.</p>
        <button type="button" className={styles.action} onClick={retry}>
          다시 시도
        </button>
      </div>
    );
  }

  if (blockedBy === 'ONBOARDING') {
    return (
      <div className={`zipick-theme ${styles.card}`}>
        <span className={styles.icon}><AuthGateIcon kind="ONBOARDING" /></span>
        <h2 className={styles.title}>먼저 내 조건을 입력해 주세요</h2>
        <p className={styles.description}>나이·소득·무주택 여부와 희망 지역·예산을 알려 주시면 맞는 집을 골라 드려요.</p>
        <button type="button" className={styles.action} onClick={() => navigate('/onboarding', { state: from ? { from } : undefined })}>
          내 조건 입력하기
        </button>
      </div>
    );
  }

  return <>{children}</>;
}
