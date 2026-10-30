import { useEffect, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { houseApi } from '../api/house';
import { ApiError } from '../api/client';
import type { HousingType, Region, RentalType, StudentStatus, UserPreference, UserProfile } from '../types';
import { HOUSING_LABEL, RENTAL_LABEL } from '../utils/format';
import AhpQuestions from '../components/AhpQuestions';
import styles from '../features/onboarding/OnboardingPage.module.css';

/** 사전 기입: 1단계 기본 정보, 2단계 희망 주거조건, 3단계 우선순위 질문(AHP). 저장하면 추천이 다시 계산된다. */
export default function OnboardingPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const [step, setStep] = useState<1 | 2 | 3>(1);

  const profileQ = useQuery({ queryKey: ['profile'], queryFn: profileApi.get, retry: false });
  const prefQ = useQuery({ queryKey: ['preference'], queryFn: profileApi.getPreference, retry: false });

  const [profile, setProfile] = useState<UserProfile>({
    birthDate: '',
    married: false,
    householdSize: 1,
    monthlyIncome: null,
    totalAssets: null,
    carValue: null,
    homeless: true,
    studentStatus: 'NONE',
  });
  // 혼인·무주택은 기본값이 들어가면 안 고르고 넘어갈 수 있어 명시적으로 고르게 한다
  const [marriedPicked, setMarriedPicked] = useState(false);
  const [homelessPicked, setHomelessPicked] = useState(false);
  const [profileInitialized, setProfileInitialized] = useState(false);
  const [profileSaveAttempted, setProfileSaveAttempted] = useState(false);
  const [pref, setPref] = useState<UserPreference>({
    regions: [],
    depositMax: null,
    rentMax: null,
    areaMin: null,
    areaMax: null,
    housingTypes: [],
    rentalTypes: [],
    monthlyBudget: null,
    roomsMin: null,
  });

  useEffect(() => {
    // 최초 조회만 복원한다. 재조회나 단계 왕복이 편집 중인 초안을 덮어쓰면 안 된다.
    if (profileInitialized) return;
    if (profileQ.data) {
      setProfile(profileQ.data);
      setMarriedPicked(true);
      setHomelessPicked(true);
      setProfileInitialized(true);
    } else if (profileQ.error instanceof ApiError && profileQ.error.code === 'PROFILE_NOT_FOUND') {
      setProfileInitialized(true);
    }
  }, [profileQ.data, profileQ.error, profileInitialized]);
  useEffect(() => {
    if (prefQ.data) setPref(prefQ.data);
  }, [prefQ.data]);

  const saveProfile = useMutation({
    mutationFn: () => profileApi.save(profile),
    onSuccess: (savedProfile) => {
      qc.setQueryData(['profile'], savedProfile);
      qc.invalidateQueries({ queryKey: ['profile'] });
      setStep(2);
    },
  });
  const savePref = useMutation({
    mutationFn: () => profileApi.savePreference(pref),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['preference'] });
      qc.invalidateQueries({ queryKey: ['recommendations'] });
      setStep(3);
    },
  });

  const err = (m: { isError: boolean; error: unknown }) => (m.isError ? (m.error as ApiError) : null);

  // 필수 항목. 서버 검증(UserProfileRequest·UserPreferenceRequest)과 추천 순위 필수 항목(cost·region)에 맞춘다.
  // 여기 없는 항목은 비워도 다음으로 넘어갈 수 있다.
  const missingProfile = [
    !profile.birthDate && '생년월일',
    !marriedPicked && '혼인 여부',
    !homelessPicked && '무주택 여부',
    !(profile.householdSize >= 1 && profile.householdSize <= 10) && '세대원 수',
  ].filter(Boolean) as string[];
  // 기존 필수 판정을 그대로 사용하고, 저장을 시도한 뒤에만 필드별 오류를 보여 준다.
  const profileValidation = {
    birthDate: missingProfile.includes('생년월일') ? '생년월일을 입력해 주세요.' : undefined,
    married: missingProfile.includes('혼인 여부') ? '혼인 여부를 선택해 주세요.' : undefined,
    homeless: missingProfile.includes('무주택 여부') ? '무주택 여부를 선택해 주세요.' : undefined,
    householdSize: missingProfile.includes('세대원 수')
      ? profile.householdSize === 0 ? '세대원 수를 입력해 주세요.' : '세대원 수는 1~10명으로 입력해 주세요.'
      : undefined,
  };
  const profileErrors: Partial<typeof profileValidation> = profileSaveAttempted ? profileValidation : {};
  // 예산이 하나도 없으면 비용 점수를 못 매겨 모든 집이 '정보 부족' 묶음으로 빠진다 (ScoreCalculator.scoreCost)
  const hasBudget = [pref.depositMax, pref.rentMax, pref.monthlyBudget].some((v) => v != null && v > 0);
  const missingPref = [pref.regions.length === 0 && '관심 지역', !hasBudget && '예산(보증금·월 임대료 상한, 월 예산 중 하나)'].filter(
    Boolean,
  ) as string[];

  return (
    <div className={`${styles.page} ${step === 1 ? styles.profileStage : 'onboarding'}`}>
      <header className={styles.intro} aria-labelledby="onboarding-page-title">
        <p className={styles.introEyebrow}>나에게 맞는 집을 찾는 첫걸음</p>
        <h1 className={styles.introTitle} id="onboarding-page-title">내 조건 입력</h1>
        <p className={styles.introDescription}>필수 조건만 알려 주세요. 나머지는 나중에 더해도 괜찮아요.</p>
      </header>

      <ol className={styles.stepper} aria-label="온보딩 진행 단계">
        <li className={`${styles.step} ${step === 1 ? styles.currentStep : profileQ.data ? styles.completedStep : ''}`} aria-current={step === 1 ? 'step' : undefined}>
          <button type="button" className={styles.stepButton} onClick={() => setStep(1)} disabled={saveProfile.isPending}>
            <span className={styles.stepNumber} aria-hidden="true">1</span>
            <span>기본 정보</span>
            {step !== 1 && profileQ.data && <span className={styles.srOnly}>저장 완료</span>}
          </button>
        </li>
        <li className={`${styles.step} ${step === 2 ? styles.currentStep : prefQ.data ? styles.completedStep : ''}`} aria-current={step === 2 ? 'step' : undefined}>
          <button type="button" className={styles.stepButton} onClick={() => setStep(2)} disabled={saveProfile.isPending}>
            <span className={styles.stepNumber} aria-hidden="true">2</span>
            <span>희망 주거 조건</span>
            {step !== 2 && prefQ.data && <span className={styles.srOnly}>저장 완료</span>}
          </button>
        </li>
        <li className={`${styles.step} ${step === 3 ? styles.currentStep : ''}`} aria-current={step === 3 ? 'step' : undefined}>
          <button type="button" className={styles.stepButton} onClick={() => setStep(3)} disabled={!prefQ.data || saveProfile.isPending}>
            <span className={styles.stepNumber} aria-hidden="true">3</span>
            <span>우선순위</span>
          </button>
        </li>
      </ol>

      {step === 1 && (
        <form
          className={styles.profilePanel}
          noValidate
          aria-labelledby="profile-title"
          aria-busy={saveProfile.isPending || (!profileInitialized && profileQ.isFetching)}
          onSubmit={(e) => {
            e.preventDefault();
            if (!profileInitialized || saveProfile.isPending) return;
            setProfileSaveAttempted(true);
            const firstError = Object.entries(profileValidation).find(([, message]) => message)?.[0];
            if (firstError) {
              e.currentTarget.querySelector<HTMLInputElement>(`#${firstError}, input[name="${firstError}"]`)?.focus();
              return;
            }
            // min/max/step 등 기존 네이티브 검증도 통과해야 저장한다.
            if (e.currentTarget.reportValidity()) saveProfile.mutate();
          }}
        >
          <div className={styles.heading}>
            <h2 id="profile-title">기본 정보</h2>
            <p>공고 지원 조건을 확인하기 위한 정보예요.<br />필수 정보만 입력해도 다음으로 진행할 수 있어요.</p>
          </div>

          {!profileInitialized ? (
            profileQ.isError && !profileQ.isFetching ? (
              <div className={styles.loadState} role="alert">
                <p>기본 정보를 불러오지 못했어요. 다시 시도해 주세요.</p>
                <button className={styles.secondaryButton} type="button" onClick={() => void profileQ.refetch()}>다시 시도</button>
              </div>
            ) : <p className={styles.loadState} role="status">기본 정보를 불러오는 중이에요…</p>
          ) : (
            <>
              <fieldset className={styles.fieldFrame} disabled={saveProfile.isPending}>
                <legend className={styles.srOnly}>기본 정보 입력</legend>
                <section className={styles.section} aria-labelledby="required-profile-title">
                  <div className={styles.sectionHeading}>
                    <h2 id="required-profile-title">필수 정보</h2>
                    <p>지원 조건 확인에 꼭 필요한 정보예요.</p>
                  </div>
                  <ProfileField id="birthDate" label="생년월일" required error={profileErrors.birthDate}>
                    <input id="birthDate" className={styles.control} type="date" required aria-invalid={profileErrors.birthDate ? true : undefined} aria-describedby={profileErrors.birthDate ? 'birthDate-error' : undefined} value={profile.birthDate} onChange={(e) => setProfile({ ...profile, birthDate: e.target.value })} />
                  </ProfileField>
                  <ProfileRadio
                    name="married"
                    label="혼인 여부"
                    error={profileErrors.married}
                    options={[{ value: false, label: '미혼' }, { value: true, label: '기혼' }]}
                    help="혼인 여부에 따라 신청할 수 있는 공급 유형이 달라져요."
                    value={marriedPicked ? profile.married : null}
                    onChange={(married) => {
                      setProfile({ ...profile, married });
                      setMarriedPicked(true);
                    }}
                  />
                  <ProfileRadio
                    name="homeless"
                    label="무주택 여부"
                    error={profileErrors.homeless}
                    options={[{ value: true, label: '예' }, { value: false, label: '아니오' }]}
                    help="본인을 포함한 세대구성원 모두 무주택인지 선택해 주세요."
                    value={homelessPicked ? profile.homeless : null}
                    onChange={(homeless) => {
                      setProfile({ ...profile, homeless });
                      setHomelessPicked(true);
                    }}
                  />
                  <ProfileField id="householdSize" label="세대원 수" required unit="명" error={profileErrors.householdSize} help="본인 포함 1~10명. 소득·면적 기준을 확인하는 데 사용해요.">
                    <div className={styles.unitControl}>
                      <input id="householdSize" className={styles.control} type="number" min={1} max={10} required aria-invalid={profileErrors.householdSize ? true : undefined} aria-describedby={`householdSize-help${profileErrors.householdSize ? ' householdSize-error' : ''}`} value={profile.householdSize || ''} onChange={(e) => setProfile({ ...profile, householdSize: Number(e.target.value) })} />
                      <span aria-hidden="true">명</span>
                    </div>
                  </ProfileField>
                </section>

                <section className={`${styles.section} ${styles.optionalSection}`} aria-labelledby="optional-profile-title">
                  <div className={styles.sectionHeading}>
                    <h2 id="optional-profile-title">선택 정보</h2>
                    <p>비워도 진행할 수 있어요. 소득·자산·자동차가액을 비우면 해당 조건은 ‘확인 필요’로 표시돼요.</p>
                  </div>
                  <ProfileField id="studentStatus" label="대학생·취업준비생" help="대학생은 입학·복학 예정 포함, 취업준비생은 졸업·중퇴 후 2년 이내 미취업인 경우예요. 청년 매입임대 자격 확인에 사용해요.">
                    <select id="studentStatus" className={styles.control} aria-describedby="studentStatus-help" value={profile.studentStatus} onChange={(e) => setProfile({ ...profile, studentStatus: e.target.value as StudentStatus })}>
                      <option value="NONE">해당 없음</option>
                      <option value="UNIVERSITY">대학생</option>
                      <option value="JOB_SEEKER">취업준비생</option>
                    </select>
                  </ProfileField>
                  <ProfileField id="monthlyIncome" label="월소득" unit="만 원" help="세전 월평균 소득을 입력해 주세요.">
                    <div className={styles.unitControl}>
                      <input id="monthlyIncome" className={styles.control} type="number" min={0} aria-describedby="monthlyIncome-help" value={profile.monthlyIncome ?? ''} onChange={(e) => setProfile({ ...profile, monthlyIncome: numOrNull(e.target.value) })} />
                      <span aria-hidden="true">만 원</span>
                    </div>
                  </ProfileField>
                  <ProfileField id="totalAssets" label="총자산" unit="만 원" help="부동산 + 자동차 + 금융자산 − 부채 기준이에요.">
                    <div className={styles.unitControl}>
                      <input id="totalAssets" className={styles.control} type="number" min={0} aria-describedby="totalAssets-help" value={profile.totalAssets ?? ''} onChange={(e) => setProfile({ ...profile, totalAssets: numOrNull(e.target.value) })} />
                      <span aria-hidden="true">만 원</span>
                    </div>
                  </ProfileField>
                  <ProfileField id="carValue" label="자동차가액" unit="만 원" help="자동차가 없으면 0을 입력해 주세요.">
                    <div className={styles.unitControl}>
                      <input id="carValue" className={styles.control} type="number" min={0} aria-describedby="carValue-help" value={profile.carValue ?? ''} onChange={(e) => setProfile({ ...profile, carValue: numOrNull(e.target.value) })} />
                      <span aria-hidden="true">만 원</span>
                    </div>
                  </ProfileField>
                </section>
              </fieldset>

              {err(saveProfile) && <div role="alert"><FormError e={err(saveProfile)!} /></div>}
              <div className={styles.actions}>
                <p className={styles.requiredStatus} role="status">
                  {missingProfile.length > 0 ? `필수 입력: ${missingProfile.join(', ')}` : '필수 정보를 모두 입력했어요.'}
                </p>
                <button className={styles.primaryButton} type="submit" disabled={saveProfile.isPending}>
                  {saveProfile.isPending ? '저장 중…' : '기본 정보 저장하고 다음'}
                </button>
              </div>
            </>
          )}
        </form>
      )}

      {step === 2 && (
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault();
            savePref.mutate();
          }}
        >
          <h1>희망 주거조건</h1>
          <p className="muted">추천 점수를 매기는 기준이에요. 금액은 만 원, 면적은 ㎡.</p>
          <RequiredNote />

          <RegionEditor regions={pref.regions} onChange={(regions) => setPref({ ...pref, regions })} />

          <p className="field-group">
            예산 <Req /> <small className="hint">보증금 상한·월 임대료 상한·월 예산 중 하나 이상. 없으면 비용 점수를 매길 수 없어요.</small>
          </p>
          <div className="grid2">
            <label>
              보증금 상한 (만 원)
              <input type="number" min={0} value={pref.depositMax ?? ''} onChange={(e) => setPref({ ...pref, depositMax: numOrNull(e.target.value) })} />
            </label>
            <label>
              월 임대료 상한 (만 원)
              <input type="number" min={0} value={pref.rentMax ?? ''} onChange={(e) => setPref({ ...pref, rentMax: numOrNull(e.target.value) })} />
            </label>
            <label>
              전용면적 최소
              <input type="number" min={0} step="0.1" value={pref.areaMin ?? ''} onChange={(e) => setPref({ ...pref, areaMin: numOrNull(e.target.value) })} />
            </label>
            <label>
              전용면적 최대
              <input type="number" min={0} step="0.1" value={pref.areaMax ?? ''} onChange={(e) => setPref({ ...pref, areaMax: numOrNull(e.target.value) })} />
            </label>
          </div>

          <div className="grid2">
            <label>
              희망 최소 방 수
              <input type="number" min={1} max={10} value={pref.roomsMin ?? ''} onChange={(e) => setPref({ ...pref, roomsMin: numOrNull(e.target.value) })} />
              <small className="hint">비우면 방 수는 점수에 넣지 않아요.</small>
            </label>
          </div>

          <label>
            월 예산 (만 원, 보증금을 월세로 환산한 총 월 비용)
            <input type="number" min={0} value={pref.monthlyBudget ?? ''} onChange={(e) => setPref({ ...pref, monthlyBudget: numOrNull(e.target.value) })} />
            <small className="hint">비우면 보증금·월세 상한으로 계산해요.</small>
          </label>

          <fieldset className="field">
            <legend>주택 유형</legend>
            <div className="chips">
              {(Object.keys(HOUSING_LABEL) as HousingType[]).map((t) => (
                <button
                  type="button"
                  key={t}
                  className={`chip ${pref.housingTypes.includes(t) ? 'chip--on' : ''}`}
                  onClick={() => setPref({ ...pref, housingTypes: toggleIn(pref.housingTypes, t) })}
                >
                  {HOUSING_LABEL[t]}
                </button>
              ))}
            </div>
          </fieldset>

          <fieldset className="field">
            <legend>공공임대 유형 (필터에만 쓰여요. 비우면 전체)</legend>
            <div className="chips">
              {(Object.keys(RENTAL_LABEL) as RentalType[]).map((t) => (
                <button
                  type="button"
                  key={t}
                  className={`chip ${pref.rentalTypes.includes(t) ? 'chip--on' : ''}`}
                  onClick={() => setPref({ ...pref, rentalTypes: toggleIn(pref.rentalTypes, t) })}
                >
                  {RENTAL_LABEL[t]}
                </button>
              ))}
            </div>
          </fieldset>

          {err(savePref) && <FormError e={err(savePref)!} />}
          <MissingRequired items={missingPref} />
          <div className="form__actions">
            <button className="btn btn--ghost" type="button" onClick={() => setStep(1)}>
              ← 기본 정보
            </button>
            <button className="btn" type="submit" disabled={savePref.isPending || missingPref.length > 0}>
              저장하고 다음 →
            </button>
          </div>
        </form>
      )}

      {step === 3 && (
        <AhpQuestions
          onDone={() => {
            qc.invalidateQueries({ queryKey: ['recommendations'] });
            qc.invalidateQueries({ queryKey: ['rec-profiles'] });
            nav('/recommendations');
          }}
          onSkip={() => nav('/recommendations')}
        />
      )}
    </div>
  );
}

/** 관심 지역 최대 3곳, 고른 순서가 순위. 선택지는 수집된 공고가 있는 시군구(필터 옵션 API)에서 가져온다 */
function RegionEditor({ regions, onChange }: { regions: Region[]; onChange: (r: Region[]) => void }) {
  const options = useQuery({ queryKey: ['filter-options'], queryFn: houseApi.filterOptions, staleTime: 5 * 60_000 });
  const [province, setProvince] = useState<string>('');
  const all = options.data?.regions ?? [];
  const provinces = [...new Set(all.map((r) => r.province))];
  const current = province || provinces[0] || '';

  const toggle = (g: { code: string; name: string }) => {
    const exists = regions.find((r) => r.code === g.code);
    let next: Region[];
    if (exists) next = regions.filter((r) => r.code !== g.code);
    else if (regions.length >= 3) return;
    else next = [...regions, { ...g, rank: 0 }];
    onChange(next.map((r, i) => ({ ...r, rank: i + 1 })));
  };
  return (
    <fieldset className="field">
      <legend>
        관심 지역 (최대 3곳, 고른 순서가 우선순위) <Req />
      </legend>
      <div className="chips">
        {regions.map((r) => (
          <button type="button" key={r.code} className="chip chip--on" onClick={() => toggle(r)}>
            {r.rank} {r.name} ×
          </button>
        ))}
        {regions.length === 0 && <small className="hint">아래에서 시도를 고른 뒤 시군구를 누르세요.</small>}
      </div>
      <div className="chips chips--dense">
        {provinces.map((p) => (
          <button type="button" key={p} className={`chip ${p === current ? 'chip--on' : ''}`} onClick={() => setProvince(p)}>
            {p}
          </button>
        ))}
      </div>
      <div className="chips chips--dense">
        {all
          .filter((r) => r.province === current && !regions.some((x) => x.code === r.key))
          .map((r) => (
            <button
              type="button"
              key={r.key}
              className="chip"
              disabled={regions.length >= 3}
              onClick={() => toggle({ code: r.key, name: r.district ?? r.key })}
            >
              {r.district ?? r.key} <span className="chip__count">{r.count}</span>
            </button>
          ))}
      </div>
      <small className="hint">지금 모집 중인 공고가 있는 지역만 보여요. 숫자는 주택 수예요.</small>
    </fieldset>
  );
}

/** 필수·선택 모두 같은 라벨 열과 컨트롤 열을 사용한다. */
function ProfileField({ id, label, required = false, unit, help, error, children }: {
  id: string;
  label: string;
  required?: boolean;
  unit?: string;
  help?: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className={styles.fieldRow}>
      <label className={styles.fieldLabel} htmlFor={id}>
        <span>{label}{unit && <span className={styles.srOnly}> ({unit})</span>}</span><span className={styles.requirement}>{required ? '필수' : '선택'}</span>
      </label>
      <div className={styles.fieldBody}>
        {children}
        {error && <p className={styles.fieldError} id={`${id}-error`} role="alert">{error}</p>}
        {help && <p className={styles.fieldHelp} id={`${id}-help`}>{help}</p>}
      </div>
    </div>
  );
}

function ProfileRadio({ name, label, help, error, value, options, onChange }: {
  name: string;
  label: string;
  help: string;
  error?: string;
  value: boolean | null;
  options: { value: boolean; label: string }[];
  onChange: (value: boolean) => void;
}) {
  return (
    <div className={styles.fieldRow}>
      <div className={styles.fieldLabel} id={`${name}-label`}>
        <span>{label}</span><span className={styles.requirement}>필수</span>
      </div>
      <div className={styles.fieldBody}>
        <div className={styles.radioGroup} role="group" aria-labelledby={`${name}-label`} aria-invalid={error ? true : undefined} aria-describedby={`${name}-help${error ? ` ${name}-error` : ''}`}>
          {options.map((option) => (
            <label className={styles.radioOption} key={String(option.value)}>
              <input type="radio" name={name} required aria-invalid={error ? true : undefined} aria-describedby={error ? `${name}-error` : undefined} checked={value === option.value} value={String(option.value)} onChange={() => onChange(option.value)} />
              <span>{option.label}</span>
            </label>
          ))}
        </div>
        {error && <p className={styles.fieldError} id={`${name}-error`} role="alert">{error}</p>}
        <p className={styles.fieldHelp} id={`${name}-help`}>{help}</p>
      </div>
    </div>
  );
}

/** 필수 항목 표시 */
function Req() {
  return (
    <span className="req" aria-label="필수" title="필수">
      *
    </span>
  );
}

function RequiredNote() {
  return (
    <p className="required-note">
      <Req /> 표시만 채우면 다음으로 넘어갈 수 있어요. 나머지는 비워 둬도 되고, 나중에 고칠 수 있어요.
    </p>
  );
}

/** 저장 버튼이 왜 꺼져 있는지 알려 준다 */
function MissingRequired({ items }: { items: string[] }) {
  if (items.length === 0) return null;
  return (
    <p className="missing-required" role="status">
      아직 입력하지 않은 필수 항목: {items.join(', ')}
    </p>
  );
}

function FormError({ e }: { e: ApiError }) {
  return (
    <div className="error">
      {e.message}
      {e.fields && (
        <ul>
          {Object.entries(e.fields).map(([k, v]) => (
            <li key={k}>
              {k}: {v}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function numOrNull(s: string): number | null {
  return s === '' ? null : Number(s);
}

function toggleIn<T>(list: T[], v: T): T[] {
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v];
}
