import { useEffect, useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { authApi, login } from '../api/auth';
import PasswordField from '../components/PasswordField';
import { useAuth } from '../hooks/useAuth';
import { closeGate } from '../hooks/useAuthGate';
import { DEFAULT_LANDING, type AuthNavState } from './LoginPage';
import { AuthFooter, AuthHeading } from '../components/AuthCard';
import styles from '../features/auth/AuthPage.module.css';

// 서버 SignupRequest의 검증 규칙을 그대로 옮겼다. 규칙이 바뀌면 양쪽을 함께 고쳐야 한다.
// BCrypt가 72바이트를 넘는 입력을 처리하지 못해 멀티바이트 문자를 받지 않는다.
const ALLOWED = /^[A-Za-z0-9!@#$%^&*()_+=-]+$/;

function lengthAndMixOk(value: string) {
  return value.length >= 8 && value.length <= 64 && /[A-Za-z]/.test(value) && /[0-9]/.test(value);
}

function validateEmail(value: string): string | null {
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return '이메일 형식이 올바르지 않습니다.';
  if (value.length > 255) return '이메일은 255자 이하여야 합니다.';
  return null;
}

export default function SignupPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const state = (location.state ?? null) as AuthNavState | null;
  const target = state?.from ?? DEFAULT_LANDING;

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  // 이메일 인증: idle → sent(코드 입력) → verified. 서버가 인증을 요구하지 않으면(required=false) 바로 verified.
  const [verify, setVerify] = useState<'idle' | 'sent' | 'verified'>('idle');
  const [verifiedEmail, setVerifiedEmail] = useState('');
  const [code, setCode] = useState('');
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [verifyBusy, setVerifyBusy] = useState(false);
  const [resendIn, setResendIn] = useState(0);
  const [codeLeft, setCodeLeft] = useState(0); // 코드 남은 시간(초)
  const [verificationToken, setVerificationToken] = useState<string | null>(null);

  // 1초마다: 재전송 대기와 코드 남은 시간을 같이 줄인다
  useEffect(() => {
    if (resendIn <= 0 && codeLeft <= 0) return;
    const t = setTimeout(() => {
      setResendIn((n) => (n > 0 ? n - 1 : 0));
      setCodeLeft((n) => (n > 0 ? n - 1 : 0));
    }, 1000);
    return () => clearTimeout(t);
  }, [resendIn, codeLeft]);
  const mmss = (sec: number) => `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;

  // 인증한 뒤 이메일을 바꾸면 처음부터 다시
  useEffect(() => {
    if (verify !== 'idle' && email.trim().toLowerCase() !== verifiedEmail) {
      setVerify('idle');
      setCode('');
      setVerifyError(null);
    }
  }, [email, verify, verifiedEmail]);

  if (user) return <Navigate to={target} replace />;

  const sendCode = async () => {
    setVerifyBusy(true);
    setVerifyError(null);
    setFields({});
    try {
      const r = await authApi.sendVerification(email.trim());
      setVerifiedEmail(email.trim().toLowerCase());
      setVerificationToken(null);
      if (!r.required) {
        setVerify('verified');
      } else {
        setVerify('sent');
        setCode('');
        setResendIn(r.resendAfterSeconds);
        setCodeLeft(r.expiresInMinutes * 60);
      }
    } catch (err) {
      if (err instanceof ApiError && err.code === 'DUPLICATE_EMAIL') setFields({ email: err.message });
      else setVerifyError(err instanceof ApiError ? err.message : '인증 메일을 보내지 못했어요.');
    } finally {
      setVerifyBusy(false);
    }
  };

  const confirmCode = async () => {
    setVerifyBusy(true);
    setVerifyError(null);
    try {
      const r = await authApi.confirmVerification(verifiedEmail, code.trim());
      setVerificationToken(r.verificationToken);
      setVerify('verified');
    } catch (err) {
      setVerifyError(err instanceof ApiError ? err.message : '인증에 실패했어요.');
      // 여러 번 틀리면 코드를 다시 받아야 한다
      if (err instanceof ApiError && err.code === 'VERIFICATION_TOO_MANY_ATTEMPTS') {
        setVerify('idle');
        setCode('');
      }
    } finally {
      setVerifyBusy(false);
    }
  };

  const emailError = validateEmail(email);
  // 비밀번호 규칙은 빨간 글씨 대신 충족 여부를 그 자리에서 보여 준다.
  const rules = [
    { ok: lengthAndMixOk(password), text: '8~64자, 영문과 숫자를 모두 포함' },
    { ok: password.length > 0 && ALLOWED.test(password), text: '사용 가능한 특수문자: ! @ # $ % ^ & * ( ) _ + = -' },
  ];
  const confirmError = password !== confirm ? '비밀번호가 일치하지 않아요.' : null;
  const valid = !emailError && verify === 'verified' && rules.every((r) => r.ok) && !confirmError;
  // 입력을 시작했거나 한 번 제출한 뒤부터 오류를 보여 준다. 빈 폼에 빨간 글씨부터 띄우지 않는다.
  const show = (value: string) => submitted || value.length > 0;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (!valid) {
      setError(verify !== 'verified' && !emailError ? '이메일 인증을 먼저 완료해 주세요.' : '입력한 내용을 다시 확인해 주세요.');
      return;
    }

    setBusy(true);
    setError(null);
    setFields({});

    try {
      await authApi.signup(email, password, verificationToken);
    } catch (err) {
      if (err instanceof ApiError) {
        // 이메일 중복(409 DUPLICATE_EMAIL)은 이메일 입력란 옆에 붙여 준다.
        setFields(err.code === 'DUPLICATE_EMAIL' ? { email: err.message } : (err.fields ?? {}));
        setError(err.message);
        // 인증이 오래돼 만료된 경우: 코드부터 다시 받게 한다
        if (err.code === 'EMAIL_NOT_VERIFIED') {
          setVerify('idle');
          setVerificationToken(null);
        }
      } else {
        setError('회원가입에 실패했습니다.');
      }
      setBusy(false);
      return;
    }

    // 가입 응답에 토큰이 없어 로그인을 한 번 더 부른다.
    try {
      await login(email, password);
    } catch {
      // 가입은 됐는데 로그인만 실패한 경우. 가입을 되돌리지 않고 로그인 화면으로 안내한다.
      navigate('/login', { replace: true, state: { from: state?.from, email, notice: 'SIGNED_UP' } });
      return;
    }

    closeGate();
    queryClient.resetQueries();
    // 온보딩을 강제하지 않는다. 보던 화면이 있으면 그리로, 없으면 지도로 돌아간다.
    navigate(target, { replace: true });
  };

  return (
    <form className={`${styles.panel} ${styles.signup}`} onSubmit={submit} noValidate aria-busy={busy}>
      <AuthHeading title="회원가입" description="가입하고 나에게 맞는 공공임대주택을 찾아보세요." />
      <div className={styles.signupIntro}>
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 11v6M12 7.5v.3" strokeWidth="2" strokeLinecap="round" />
        </svg>
        <span>이메일 인증과 비밀번호 설정만 마치면 가입할 수 있어요.</span>
      </div>

      <div className={styles.fields}>
        <div className={styles.field}>
          <label htmlFor="signup-email">이메일</label>
          <div className={styles.inlineControl}>
            <input
              id="signup-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              placeholder="이메일을 입력해 주세요"
              required
              autoFocus
              className={verify === 'verified' ? styles.verifiedInput : undefined}
              aria-invalid={!!fields.email || (show(email) && !!emailError)}
              aria-describedby={fields.email || (show(email) && emailError) ? 'signup-email-error' : undefined}
            />
            <button
              type="button"
              className={styles.secondaryAction}
              onClick={sendCode}
              disabled={verifyBusy || !!emailError || verify !== 'idle'}
            >
              {verify === 'verified' ? '인증 완료' : verify === 'sent' ? '코드 발송됨' : verifyBusy ? '보내는 중…' : '인증 코드 보내기'}
            </button>
          </div>
          {(fields.email || (show(email) && emailError)) && (
            <small id="signup-email-error" className={styles.fieldError}>{fields.email || emailError}</small>
          )}

          {/* 상태·유효 시간·재발송 간격은 기존 서버 응답을 그대로 사용한다. */}
          {verify === 'verified' ? (
            <p className={styles.verified} role="status">✓ 이메일 인증 완료</p>
          ) : verify === 'sent' ? (
            <div className={styles.verifyBox}>
              <div className={styles.verifyHeading}>
                <label htmlFor="signup-code">인증 코드 입력</label>
                <span className={styles.micro}>6자리</span>
              </div>
              <p className={styles.verifyDescription}>{verifiedEmail}(으)로 보낸 코드를 입력해 주세요.</p>
              <div className={styles.codeRow}>
                <input
                  id="signup-code"
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                  placeholder="000000"
                  autoComplete="one-time-code"
                  aria-invalid={!!verifyError}
                  aria-describedby={verifyError ? 'code-expiry signup-verify-error' : 'code-expiry'}
                />
                <button type="button" className={styles.secondaryAction} onClick={confirmCode} disabled={verifyBusy || code.length !== 6 || codeLeft <= 0}>
                  {verifyBusy ? '확인 중…' : '인증 확인'}
                </button>
              </div>
              <p id="code-expiry" className={codeLeft > 0 ? styles.micro : styles.fieldError}>
                {codeLeft > 0 ? `남은 시간 ${mmss(codeLeft)}` : '만료됨 — 코드를 다시 받아 주세요'}
              </p>
              <div className={styles.verifyBottom}>
                <small className={styles.micro}>메일이 보이지 않으면 스팸함을 확인해 주세요.</small>
                <button type="button" className={styles.resend} onClick={sendCode} disabled={verifyBusy || resendIn > 0}>
                  {resendIn > 0 ? `다시 보내기 (${resendIn}초)` : '코드 다시 보내기'}
                </button>
              </div>
            </div>
          ) : null}
          {verifyError && <small id="signup-verify-error" className={styles.fieldError} role="alert">{verifyError}</small>}
        </div>

        <div className={styles.field}>
          <PasswordField
            label="비밀번호"
            value={password}
            onChange={setPassword}
            placeholder="비밀번호를 입력해 주세요"
            autoComplete="new-password"
            error={fields.password}
            describedBy="signup-password-rules"
          />
          <ul id="signup-password-rules" className={styles.rules}>
            {rules.map((rule) => (
              <li key={rule.text} className={rule.ok ? styles.rulePassed : undefined}>
                <span className={styles.ruleMark} aria-hidden="true">✓</span>
                <span><span className={styles.srOnly}>{rule.ok ? '충족: ' : '미충족: '}</span>{rule.text}</span>
              </li>
            ))}
          </ul>
        </div>

        <PasswordField
          label="비밀번호 확인"
          value={confirm}
          onChange={setConfirm}
          placeholder="비밀번호를 한 번 더 입력해 주세요"
          autoComplete="new-password"
          error={show(confirm) ? confirmError : null}
        />
      </div>

      {error && <div className={styles.errorNotice} role="alert">{error}</div>}
      <button className={styles.submit} type="submit" disabled={busy}>
        {busy ? '가입 중…' : '회원가입'}
      </button>
      <p className={styles.alternative}>
        이미 계정이 있으신가요?{' '}
        <Link to="/login" state={state?.from ? { from: state.from } : undefined}>로그인</Link>
      </p>
      <AuthFooter />
    </form>
  );
}
