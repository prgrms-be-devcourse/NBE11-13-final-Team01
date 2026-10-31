import { Link } from 'react-router-dom';
import styles from './Landing.module.css';
import MapBrand from '../../components/MapBrand';
import headerStyles from '../../components/Header.module.css';
export default function LandingHeader(){return (<><header className={styles['sitehead'] + ' ' + headerStyles.header}><div className={headerStyles.inner + ' ' + styles['head-inner']}><Link className={styles['logo']} to="/landing" aria-label="집픽 홈"><MapBrand /></Link><nav className={styles['menu']} aria-label="주요 메뉴"><a className={headerStyles.navLink} href="#features">서비스 소개</a><a className={headerStyles.navLink} href="#journey">이용 방법</a><Link className={headerStyles.navLink} to="/map">지도 탐색</Link></nav><div className={styles['head-actions']}><Link className={styles['signin'] + ' ' + headerStyles.secondary} to="/login" state={{ from: "/landing" }}>로그인</Link><Link className={styles['nav-btn'] + ' ' + headerStyles.primary} to="/map">집 찾기 <span className={styles['arrow']}>↗</span></Link></div></div></header>
</>);}
