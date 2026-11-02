import styles from './MapBrand.module.css';

/** 승인된 PNG를 그대로 사용하고 기존의 낮은 서비스 헤더도 유지한다. */
export default function MapBrand({ compact = false }: { compact?: boolean }) {
  return (
    <img
      className={compact ? `${styles.image} ${styles.compact}` : styles.image}
      src="/brand/zipick-logo-header.png"
      alt="집픽"
    />
  );
}
