import { useLayoutEffect, useRef, type ReactNode } from 'react';
import styles from './Header.module.css';

/** 본문과 스크롤 정책은 유지하고 헤더의 우측 기준만 뷰포트에 맞춘다. */
export default function HeaderInner({ children, className = '' }: { children: ReactNode; className?: string }) {
  const ref = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    // 인증 화면의 stable gutter는 vw에서도 빠지므로 실제 예약 너비를 측정한다.
    const update = () => {
      // stable gutter가 있는 인증 화면도 동일한 뷰포트 기준으로 좌우 여백을 계산한다.
      element.style.setProperty('--header-viewport-width', `${window.innerWidth}px`);
      element.style.setProperty('--header-scrollbar-width', `${Math.max(0, window.innerWidth - element.getBoundingClientRect().width)}px`);
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    window.addEventListener('resize', update);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', update);
    };
  }, []);

  return <div ref={ref} className={`${styles.inner} ${className}`}>{children}</div>;
}
