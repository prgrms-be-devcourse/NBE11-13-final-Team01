import { Link } from 'react-router-dom';
import MapBrand from './MapBrand';
import styles from '../features/auth/AuthPage.module.css';

export function AuthHeading({ title, description }: { title: string; description: string }) {
  return (
    <div className={styles.heading}>
      <Link to="/map" className={styles.cardBrand} aria-label="집픽 지도 탐색으로 이동">
        <MapBrand />
      </Link>
      <h1>{title}</h1>
      <p>{description}</p>
    </div>
  );
}

export function AuthFooter() {
  return (
    <div className={styles.footer}>
      집을 먼저 둘러보고 싶다면 <Link to="/map">지도 탐색으로 이동</Link>
    </div>
  );
}
