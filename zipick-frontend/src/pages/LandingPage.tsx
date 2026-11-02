import LandingHeader from '../features/landing/LandingHeader';
import LandingHero from '../features/landing/LandingHero';
import LandingConfidence from '../features/landing/LandingConfidence';
import LandingFeatures from '../features/landing/LandingFeatures';
import LandingJourney from '../features/landing/LandingJourney';
import LandingFinalCta from '../features/landing/LandingFinalCta';
import LandingFooter from '../features/landing/LandingFooter';
import '../design-system/tokens.css';
import styles from '../features/landing/Landing.module.css';
export default function LandingPage(){return <div className={'zipick-theme '+styles.landing}><LandingHeader/><main><LandingHero/><LandingConfidence/><LandingFeatures/><LandingJourney/><LandingFinalCta/><LandingFooter/></main></div>;}
