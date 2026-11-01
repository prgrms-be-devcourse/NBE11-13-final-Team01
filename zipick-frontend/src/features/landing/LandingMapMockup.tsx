import styles from './Landing.module.css';

// Presentation only: the landing preview must not initialize the map SDK or fetch data.
export function MapArtwork() {
  return (
    <svg className={styles['map-art']} viewBox="0 0 650 480" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <rect width="650" height="480" fill="#edf3f5" />
      <path d="M0 0 650 0v480H0z" fill="#eff4f4" />
      <path d="M0 430C124 383 202 464 324 369c87-69 196-91 326-101" fill="none" stroke="#c2dfed" strokeWidth="67" />
      <path d="M-35 55 691 400" stroke="#fff" strokeWidth="23" />
      <path d="M-15 85 605 480M-30 230 525-20M52 480 645 86M135-25 312 500M363-45 504 505" stroke="#fff" strokeWidth="14" />
      <path d="M-15 85 605 480M-30 230 525-20M52 480 645 86M135-25 312 500M363-45 504 505" stroke="#dfe7ea" strokeWidth="2" />
      <path d="M8 313 204 244 241 318 48 386Z" fill="#dcebdc" />
      <path d="m431 27 121 51-47 89-111-45Z" fill="#d9eadb" />
      <path d="m20 109 84-19 24 74-80 18ZM213 37 277 59 257 126 185 97ZM342 150 409 178 386 235 320 207ZM504 348 601 338 607 395 525 410Z" fill="#e1e7e9" />
      <path d="M286 273 342 299 304 357 264 331Z" fill="#e0e7e8" />
      <g stroke="#d9e3e7" strokeWidth="1.5">
        <path d="M56 0v80M88 0v65M448 0v82M595 0v100M0 171h120M550 220h100M50 410h116M380 450h200" />
      </g>
    </svg>
  );
}

export function ClusterChip({ region, count, className }: { region: string; count: number; className: string }) {
  return (
    <span className={`${styles['cluster-chip']} ${className}`}>
      <span>서울 · {region}</span><strong>{count}</strong>
    </span>
  );
}

export function HouseMarker({ className, size = 13 }: { className: string; size?: number }) {
  return (
    <span className={`${styles['tiny-house-marker']} ${className}`} title="예시 주택 위치">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden="true">
        <path d="m3 11 9-7 9 7v9H3z" /><path d="M9 20v-7h6v7" />
      </svg>
    </span>
  );
}

export default function LandingMapMockup() {
  return (
    <div className={`${styles['feature-visual']} ${styles['feature-visual--map']}`}>
      <div className={styles['feature-map-preview']} role="group" aria-label="집픽 지도 탐색 기능 예시. 지도에서 주택 핀과 행정구역별 클러스터를 보여줍니다.">
        <MapArtwork />
        <div className={styles['feature-map-count']}>주택 <strong>343곳</strong></div>
        <span className={`${styles['map-place']} ${styles['f-place-1']}`}>인천</span>
        <span className={`${styles['map-place']} ${styles['f-place-2']}`}>서울</span>
        <span className={`${styles['map-place']} ${styles['f-place-3']}`}>경기도</span>
        <ClusterChip region="강서구" count={7} className={`${styles['feature-chip']} ${styles['fchip-1']}`} />
        <ClusterChip region="마포구" count={3} className={`${styles['feature-chip']} ${styles['fchip-2']}`} />
        <ClusterChip region="성북구" count={12} className={`${styles['feature-chip']} ${styles['fchip-3']}`} />
        <ClusterChip region="동작구" count={2} className={`${styles['feature-chip']} ${styles['fchip-4']}`} />
        <HouseMarker className={styles['fpin-1']} size={12} />
        <HouseMarker className={styles['fpin-2']} size={12} />
        <div className={styles['feature-map-zoom']} aria-hidden="true"><span>＋</span><span>－</span><span>⛶</span></div>
      </div>
    </div>
  );
}
