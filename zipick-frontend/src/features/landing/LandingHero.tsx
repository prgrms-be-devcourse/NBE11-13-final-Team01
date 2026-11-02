import { Link } from 'react-router-dom';
import { MapArtwork, ClusterChip, HouseMarker } from './LandingMapMockup';
import styles from './Landing.module.css';
export default function LandingHero(){return (<><section className={styles['hero']}><div className={styles['wrap'] + ' ' + styles['hero-grid']}><div><div className={styles['overline']}>공공임대주택 탐색, 이제 더 쉽게</div><h1>나에게 맞는 집,<br/><span className={styles['blue']}>지도로 쉽게 찾고</span><br/>차근차근 준비해요.</h1><p className={styles['hero-desc']}>여기저기 흩어진 공공임대 공고부터<br/>내 조건에 맞는 추천, 놓치기 쉬운 신청 일정까지.<br/>집픽에서 한 번에 살펴보세요.</p><div className={styles['cta-row']}><Link className={styles['primary']} to="/map">지도로 집 찾기 <span className={styles['arrow']}>↗</span></Link><a className={styles['secondary']} href="#journey">집픽 이용 방법 <span className={styles['arrow']}>→</span></a></div><p className={styles['hero-note']}>로그인 없이도 지도와 주택 정보를 둘러볼 수 있어요.</p></div>
<div className={styles['hero-visual']}>
<div aria-label="집픽 실제 지도와 주택 상세 화면을 축약한 UI 예시" className={styles['map-frame'] + ' ' + styles['real-map-preview']} role="group">
<MapArtwork />
<div className={styles['map-toolbar'] + ' ' + styles['map-toolbar--real']}>
<div className={styles['map-search']}><svg aria-hidden="true" fill="none" height="15" stroke="#4d6386" strokeWidth="2.1" viewBox="0 0 24 24" width="15"><circle cx="11" cy="11" r="7"></circle><path d="m16 16 5 5"></path></svg><span>서울특별시 · 공공임대 전체</span></div>
<span className={styles['mini-available']}>주택 <strong>343곳</strong></span>
</div>
<span className={styles['map-place'] + ' ' + styles['place-1']}>서대문구</span><span className={styles['map-place'] + ' ' + styles['place-2']}>영등포구</span><span className={styles['map-place'] + ' ' + styles['place-3']}>강서구</span>
<ClusterChip region="마포구" count={3} className={styles['hero-chip'] + ' ' + styles['hero-chip--one']} />
<ClusterChip region="성북구" count={12} className={styles['hero-chip'] + ' ' + styles['hero-chip--two']} />
<ClusterChip region="영등포구" count={8} className={styles['hero-chip'] + ' ' + styles['hero-chip--three']} />
<HouseMarker className={styles['h1']} size={13} />
<HouseMarker className={styles['h2']} size={13} />
<HouseMarker className={styles['chosen']} size={15} />
<div className={styles['hero-housing-card']}>
<div className={styles['housing-card-flags']}><span className={styles['flag'] + ' ' + styles['blue-flag']}>접수 중</span><span className={styles['flag'] + ' ' + styles['agency-flag']}>LH</span><span className={styles['housing-save']}>☆ 관심 공고</span></div>
<div className={styles['hero-house-name']}>강서구 행복주택</div>
<div className={styles['hero-house-address']}><svg aria-hidden="true" fill="none" height="13" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" width="13"><path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"></path><circle cx="12" cy="10" r="2.5"></circle></svg>서울특별시 강서구 · 예시 주택</div>
<div className={styles['hero-card-separator']}></div>
<div className={styles['hero-supply-heading']}>공급 정보</div>
<div className={styles['hero-supply-grid']}><div><small>공급 유형</small><strong>행복주택</strong></div><div><small>전용면적</small><strong>29㎡</strong></div></div>
<span className={styles['hero-housing-link']}>AI 신청 준비 가이드 보기 <span aria-hidden="true">↗</span></span>
</div>
<span className={styles['map-bottom-label']}>ZIPICK · MAP PREVIEW</span>
</div>
<div className={styles['callout']}><span className={styles['callout-icon']}><svg aria-hidden="true" fill="none" height="17" stroke="currentColor" strokeWidth="2.4" viewBox="0 0 24 24" width="17"><path d="m5 12 5 5L20 7"></path></svg></span>내 조건에 맞춰 추천 근거도 확인</div>
<span className={styles['demo-tag']}>※ 이해를 돕기 위한 예시 화면입니다.</span>
</div></div></section></>);}
