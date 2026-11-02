import styles from './Landing.module.css';
export default function LandingFooter() {
  return (
    <footer className={styles['footer']}>
      <div className={styles['wrap'] + ' ' + styles['footer-inner']}>
        <div>
          <div className={styles['footer-title']}>집픽 ZIPICK</div>
          <p>공공임대주택 탐색부터 맞춤 추천, 일정 확인과 신청 준비까지.</p>
          <p>최종 신청 자격과 모집 일정은 공식 모집공고문을 확인해 주세요.</p>
          <p>이 페이지의 지도·주택 정보·추천 점수·일정은 서비스 이해를 돕기 위한 예시입니다.</p>
        </div>
      </div>
    </footer>
  );
}
