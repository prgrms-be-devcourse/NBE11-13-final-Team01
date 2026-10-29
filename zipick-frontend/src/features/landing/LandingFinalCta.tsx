import { Link } from 'react-router-dom';
import styles from './Landing.module.css';
export default function LandingFinalCta(){return (<><section className={styles['finalcta']}><div className={styles['wrap']}><div className={styles['cta-panel']}><div><h2>내가 찾는 집, 이제 집픽에서.</h2><p>지도를 둘러보는 것부터 가볍게 시작해 보세요.</p></div><Link className={styles['white-btn']} to="/map">공공임대 주택 둘러보기 <span className={styles['arrow']}>↗</span></Link></div></div></section></>);}
