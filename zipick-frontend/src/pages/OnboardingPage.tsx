import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { houseApi } from '../api/house';
import { ApiError } from '../api/client';
import type { HousingType, Region, RentalType, StudentStatus, UserPreference, UserProfile } from '../types';
import { HOUSING_LABEL, RENTAL_LABEL } from '../utils/format';
import AhpQuestions from '../components/AhpQuestions';
import styles from '../features/onboarding/OnboardingPage.module.css';
import { shortProvince } from '../features/map/regionCluster';

/** 사전 기입: 1단계 기본 정보, 2단계 희망 주거조건, 3단계 우선순위 질문(AHP). 저장하면 추천이 다시 계산된다. */
export default function OnboardingPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [ahpSaving, setAhpSaving] = useState(false);
  const ahpSaveInFlight = useRef(false);

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
  const [prefInitialized, setPrefInitialized] = useState(false);
  const [prefSaveAttempted, setPrefSaveAttempted] = useState(false);
  const prefSaveInFlight = useRef(false);
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
    // 최초 조회만 복원하여 단계 왕복·재조회가 편집 중인 희망 조건을 덮어쓰지 않게 한다.
    if (prefInitialized) return;
    if (prefQ.data) {
      setPref(prefQ.data);
      setPrefInitialized(true);
    } else if (prefQ.error instanceof ApiError && prefQ.error.code === 'PREFERENCE_NOT_FOUND') {
      setPrefInitialized(true);
    }
  }, [prefQ.data, prefQ.error, prefInitialized]);

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
    onSettled: () => { prefSaveInFlight.current = false; },
  });

  const err = (m: { isError: boolean; error: unknown }) => (m.isError ? (m.error as ApiError) : null);

  // 필수 항목. 서버 검증(UserProfileRequest·UserPreferenceRequest)과 추천 순위 필수 항목(cost·region)에 맞춘다.
  // 여기 없는 항목은 비워도 다음으로 넘어갈 수 있다.
  const missingProfile = [
    !profile.birthDate && '생년월일',
    !(profile.householdSize >= 1 && profile.householdSize <= 10) && '세대원 수',
    !marriedPicked && '혼인 여부',
    !homelessPicked && '무주택 여부',
  ].filter(Boolean) as string[];
  // 기존 필수 판정을 그대로 사용하고, 저장을 시도한 뒤에만 필드별 오류를 보여 준다.
  const profileValidation = {
    birthDate: missingProfile.includes('생년월일') ? '생년월일을 입력해 주세요.' : undefined,
    householdSize: missingProfile.includes('세대원 수')
      ? profile.householdSize === 0 ? '세대원 수를 입력해 주세요.' : '세대원 수는 1~10명으로 입력해 주세요.'
      : undefined,
    married: missingProfile.includes('혼인 여부') ? '혼인 여부를 선택해 주세요.' : undefined,
    homeless: missingProfile.includes('무주택 여부') ? '무주택 여부를 선택해 주세요.' : undefined,
  };
  const profileErrors: Partial<typeof profileValidation> = profileSaveAttempted ? profileValidation : {};
  // 예산이 하나도 없으면 비용 점수를 못 매겨 모든 집이 '정보 부족' 묶음으로 빠진다 (ScoreCalculator.scoreCost)
  const hasBudget = [pref.depositMax, pref.rentMax, pref.monthlyBudget].some((v) => v != null && v > 0);
  const missingPref = [pref.regions.length === 0 && '관심 지역', !hasBudget && '예산(보증금·월 임대료 상한, 월 예산 중 하나)'].filter(
    Boolean,
  ) as string[];
  const invalidArea = pref.areaMin != null && pref.areaMax != null && pref.areaMax < pref.areaMin;

  return (
    <div className={`${styles.page} ${step === 1 ? styles.profileStage : step === 2 ? styles.preferenceStage : styles.priorityStage}`}>
      <header className={styles.intro} aria-labelledby="onboarding-page-title">
        <p className={styles.introEyebrow}>나에게 맞는 집을 찾는 첫걸음</p>
        <h1 className={styles.introTitle} id="onboarding-page-title">내 조건 입력</h1>
        <p className={styles.introDescription}>필수 조건만 알려 주세요. 나머지는 나중에 더해도 괜찮아요.</p>
      </header>

      <ol className={styles.stepper} aria-label="온보딩 진행 단계">
        <li className={`${styles.step} ${step === 1 ? styles.currentStep : profileQ.data ? styles.completedStep : ''}`} aria-current={step === 1 ? 'step' : undefined}>
          <button type="button" className={styles.stepButton} onClick={() => { if (!ahpSaveInFlight.current) setStep(1); }} disabled={saveProfile.isPending || ahpSaving}>
            <span className={styles.stepNumber} aria-hidden="true">1</span>
            <span>기본 정보</span>
            {step !== 1 && profileQ.data && <span className={styles.srOnly}>저장 완료</span>}
          </button>
        </li>
        <li className={`${styles.step} ${step === 2 ? styles.currentStep : prefQ.data ? styles.completedStep : ''}`} aria-current={step === 2 ? 'step' : undefined}>
          <button type="button" className={styles.stepButton} onClick={() => { if (!ahpSaveInFlight.current) setStep(2); }} disabled={saveProfile.isPending || ahpSaving}>
            <span className={styles.stepNumber} aria-hidden="true">2</span>
            <span>희망 주거 조건</span>
            {step !== 2 && prefQ.data && <span className={styles.srOnly}>저장 완료</span>}
          </button>
        </li>
        <li className={`${styles.step} ${step === 3 ? styles.currentStep : ''}`} aria-current={step === 3 ? 'step' : undefined}>
          <button type="button" className={styles.stepButton} onClick={() => { if (!ahpSaveInFlight.current) setStep(3); }} disabled={!prefQ.data || saveProfile.isPending || ahpSaving}>
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
            <p>공고 지원 조건을 확인하기 위한 정보예요. 필수 정보만 입력해도 다음으로 진행할 수 있어요.</p>
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
                    <h3 id="required-profile-title">필수 정보 <span className={`${styles.requirement} ${styles.requiredRequirement}`}>필수</span></h3>
                    <p>지원 조건 확인에 꼭 필요한 정보예요.</p>
                  </div>
                  <div className={styles.profileGrid}>
                    <ProfileField id="birthDate" label="생년월일" required error={profileErrors.birthDate}>
                      <input id="birthDate" className={styles.control} type="date" required aria-invalid={profileErrors.birthDate ? true : undefined} aria-describedby={profileErrors.birthDate ? 'birthDate-error' : undefined} value={profile.birthDate} onChange={(e) => setProfile({ ...profile, birthDate: e.target.value })} />
                    </ProfileField>
                    <ProfileField id="householdSize" label="세대원 수" required unit="명" error={profileErrors.householdSize} help="본인 포함 1~10명. 소득·면적 기준을 확인하는 데 사용해요.">
                      <div className={styles.unitControl}>
                        <input id="householdSize" className={styles.control} type="number" min={1} max={10} required aria-invalid={profileErrors.householdSize ? true : undefined} aria-describedby={`householdSize-help${profileErrors.householdSize ? ' householdSize-error' : ''}`} value={profile.householdSize || ''} onChange={(e) => setProfile({ ...profile, householdSize: Number(e.target.value) })} />
                        <span aria-hidden="true">명</span>
                      </div>
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
                  </div>
                </section>

                <section className={`${styles.section} ${styles.optionalSection}`} aria-labelledby="optional-profile-title">
                  <div className={styles.sectionHeading}>
                    <h3 id="optional-profile-title">선택 정보 <span className={styles.requirement}>선택</span></h3>
                    <p>비워도 진행할 수 있어요. 소득·자산·자동차가액을 비우면 해당 조건은 ‘확인 필요’로 표시돼요.</p>
                  </div>
                  <ProfileField id="studentStatus" label="대학생·취업준비생 여부" help="대학생은 입학·복학 예정 포함, 취업준비생은 졸업·중퇴 후 2년 이내 미취업인 경우예요. 청년 매입임대 자격 확인에 사용해요.">
                    <select id="studentStatus" className={styles.control} aria-describedby="studentStatus-help" value={profile.studentStatus} onChange={(e) => setProfile({ ...profile, studentStatus: e.target.value as StudentStatus })}>
                      <option value="NONE">해당 없음</option>
                      <option value="UNIVERSITY">대학생</option>
                      <option value="JOB_SEEKER">취업준비생</option>
                    </select>
                  </ProfileField>
                  <div className={styles.profileGrid}>
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
                  </div>
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
          className={styles.preferencePanel}
          noValidate
          aria-labelledby="preference-title"
          aria-busy={savePref.isPending || (!prefInitialized && prefQ.isFetching)}
          onSubmit={(e) => {
            e.preventDefault();
            if (!prefInitialized || prefSaveInFlight.current) return;
            setPrefSaveAttempted(true);
            if (missingPref.length > 0 || invalidArea) {
              const selector = pref.regions.length === 0 ? '#district-search' : !hasBudget ? '#depositMax' : '#areaMax';
              e.currentTarget.querySelector<HTMLInputElement>(selector)?.focus();
              return;
            }
            if (e.currentTarget.reportValidity()) {
              // 렌더가 갱신되기 전 같은 순간에 발생한 중복 제출도 막는다.
              prefSaveInFlight.current = true;
              savePref.mutate();
            }
          }}
        >
          <h2 className={styles.preferenceTitle} id="preference-title">희망 주거 조건</h2>
          <p className={styles.preferenceDescription}>원하는 지역과 예산을 알려주시면, 나에게 맞는 주택을 찾는 데 활용해요.</p>
          {!prefInitialized ? (
            prefQ.isError && !prefQ.isFetching ? (
              <div className={styles.loadState} role="alert">
                <p>희망 조건을 불러오지 못했어요. 다시 시도해 주세요.</p>
                <button className={styles.secondaryButton} type="button" onClick={() => void prefQ.refetch()}>다시 시도</button>
              </div>
            ) : <p className={styles.loadState} role="status">희망 조건을 불러오는 중이에요…</p>
          ) : (
            <>
              <fieldset className={`${styles.preferenceFields} ${styles.fieldFrame}`} disabled={savePref.isPending}>
                <legend className={styles.srOnly}>희망 주거 조건 입력</legend>
                <RegionEditor regions={pref.regions} onChange={(regions) => setPref({ ...pref, regions })} error={prefSaveAttempted && pref.regions.length === 0} />

                <section className={`${styles.preferenceSection} ${styles.preferenceDivider}`} aria-labelledby="budget-title">
                  <PreferenceHeading id="budget-title" title="예산" required description={<>보증금 상한, 월 임대료 상한, 월 예산 중 하나 이상 입력해 주세요.<br /><span id="budget-help">비워 둔 항목은 상한을 적용하지 않아요. 숫자는 만원 단위로 입력해 주세요.</span></>} />
                  <div className={styles.preferenceGrid}>
                    <PreferenceInput id="depositMax" label="보증금 상한" unit="만원" placeholder="예: 1000" value={pref.depositMax} invalid={prefSaveAttempted && !hasBudget} describedBy={`budget-help${prefSaveAttempted && !hasBudget ? ' budget-error' : ''}`} onChange={(depositMax) => setPref({ ...pref, depositMax })} />
                    <PreferenceInput id="rentMax" label="월 임대료 상한" unit="만원" placeholder="예: 40" value={pref.rentMax} invalid={prefSaveAttempted && !hasBudget} describedBy={`budget-help${prefSaveAttempted && !hasBudget ? ' budget-error' : ''}`} onChange={(rentMax) => setPref({ ...pref, rentMax })} />
                    <div className={styles.preferenceWide}>
                      <PreferenceInput id="monthlyBudget" label="월 예산" unit="만원" placeholder="예: 80" value={pref.monthlyBudget} help="보증금을 월세로 환산한 총 월 비용이에요." invalid={prefSaveAttempted && !hasBudget} describedBy={prefSaveAttempted && !hasBudget ? 'budget-error' : undefined} onChange={(monthlyBudget) => setPref({ ...pref, monthlyBudget })} />
                    </div>
                  </div>
                  {prefSaveAttempted && !hasBudget && <p className={styles.preferenceError} id="budget-error" role="alert">보증금 상한, 월 임대료 상한, 월 예산 중 하나를 0보다 크게 입력해 주세요.</p>}
                </section>

                <section className={`${styles.preferenceSection} ${styles.preferenceDivider}`} aria-labelledby="home-title">
                  <PreferenceHeading id="home-title" title="면적 · 방 수" description="원하는 집 크기를 알고 있다면 입력해 주세요." />
                  <div className={styles.preferenceGrid}>
                    <PreferenceInput id="areaMin" label="전용면적 최소" unit="㎡" placeholder="최소 면적" step={0.1} value={pref.areaMin} invalid={prefSaveAttempted && invalidArea} describedBy={prefSaveAttempted && invalidArea ? 'area-error' : undefined} onChange={(areaMin) => setPref({ ...pref, areaMin })} />
                    <PreferenceInput id="areaMax" label="전용면적 최대" unit="㎡" placeholder="최대 면적" step={0.1} value={pref.areaMax} invalid={prefSaveAttempted && invalidArea} describedBy={prefSaveAttempted && invalidArea ? 'area-error' : undefined} onChange={(areaMax) => setPref({ ...pref, areaMax })} />
                    <PreferenceInput id="roomsMin" label="희망 최소 방 수" unit="개" placeholder="예: 2" min={1} max={10} value={pref.roomsMin} help="비우면 방 수는 점수에 넣지 않아요." onChange={(roomsMin) => setPref({ ...pref, roomsMin })} />
                  </div>
                  {prefSaveAttempted && invalidArea && <p className={styles.preferenceError} id="area-error" role="alert">전용면적 최대는 최소 이상이어야 해요.</p>}
                </section>

                <section className={`${styles.preferenceSection} ${styles.preferenceDivider}`} aria-labelledby="types-title">
                  <PreferenceHeading id="types-title" title="선호하는 주택" description="여러 개 선택할 수 있어요. 선택하지 않으면 전체 유형을 살펴봐요." />
                  <fieldset className={styles.preferenceTypes}>
                    <legend>주택 유형</legend>
                    <div className={styles.preferenceOptions}>
                      {(Object.keys(HOUSING_LABEL) as HousingType[]).map((t) => (
                        <button type="button" key={t} className={styles.preferenceChip} aria-pressed={pref.housingTypes.includes(t)} onClick={() => setPref({ ...pref, housingTypes: toggleIn(pref.housingTypes, t) })}>{HOUSING_LABEL[t]}</button>
                      ))}
                    </div>
                  </fieldset>
                  <fieldset className={styles.preferenceTypes}>
                    <legend>공공임대 유형 <small>필터에만 쓰여요. 선택하지 않으면 전체</small></legend>
                    <div className={styles.preferenceOptions}>
                      {(Object.keys(RENTAL_LABEL) as RentalType[]).map((t) => (
                        <button type="button" key={t} className={styles.preferenceChip} aria-pressed={pref.rentalTypes.includes(t)} onClick={() => setPref({ ...pref, rentalTypes: toggleIn(pref.rentalTypes, t) })}>{RENTAL_LABEL[t]}</button>
                      ))}
                    </div>
                  </fieldset>
                </section>
              </fieldset>
              {err(savePref) && <div role="alert"><FormError e={err(savePref)!} /></div>}
              <div className={styles.preferenceActions}>
                <button className={styles.preferenceBack} type="button" onClick={() => setStep(1)}>← 기본 정보</button>
                <button className={styles.preferenceNext} type="submit" disabled={savePref.isPending}>{savePref.isPending ? '저장 중…' : '저장하고 다음 →'}</button>
              </div>
            </>
          )}
        </form>
      )}

      {step === 3 && (
        <AhpQuestions
          onBack={() => setStep(2)}
          onSavingChange={(saving) => {
            ahpSaveInFlight.current = saving;
            setAhpSaving(saving);
          }}
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
function RegionEditor({ regions, onChange, error }: { regions: Region[]; onChange: (r: Region[]) => void; error: boolean }) {
  const options = useQuery({ queryKey: ['filter-options'], queryFn: houseApi.filterOptions, staleTime: 5 * 60_000 });
  const [province, setProvince] = useState<string>('');
  const [search, setSearch] = useState('');
  const all = options.data?.regions ?? [];
  const provinces = [...new Set(all.map((r) => r.province))];
  const ordered = [...regions].sort((a, b) => a.rank - b.rank);
  const savedProvince = all.find((r) => r.key === ordered[0]?.code)?.province;
  const current = provinces.includes(province) ? province : savedProvince || provinces[0] || '';
  const districts = all.filter((r) => r.province === current && (r.district ?? r.key).includes(search.trim()));

  const toggle = (g: { code: string; name: string }) => {
    const exists = regions.find((r) => r.code === g.code);
    let next: Region[];
    if (exists) next = ordered.filter((r) => r.code !== g.code);
    else if (regions.length >= 3) return;
    else next = [...ordered, { ...g, rank: 0 }];
    onChange(next.map((r, i) => ({ ...r, rank: i + 1 })));
  };
  return (
    <section className={styles.preferenceSection} aria-labelledby="region-title">
      <PreferenceHeading id="region-title" title="관심 지역" required description="최대 3곳까지 선택할 수 있어요. 선택한 순서대로 우선순위가 정해져요." counter={<span className={styles.regionCount} role="status"><strong>{regions.length}</strong> / 3곳</span>} />
      <div className={styles.selectedItems} role="group" aria-label="선택한 관심 지역">
        {ordered.map((r) => {
          const option = all.find((option) => option.key === r.code);
          const label = option ? `${shortProvince(option.province)} · ${r.name}` : r.code;
          return (
            <div className={styles.selectedChip} key={r.code}>
              <span className={styles.regionRank}>{r.rank}순위</span><span title={r.code}>{label}</span>
              <button type="button" className={styles.regionRemove} aria-label={`${r.rank}순위 ${r.code} 제거`} onClick={() => toggle(r)}>×</button>
            </div>
          );
        })}
        {regions.length === 0 && <p className={styles.regionEmpty}>아직 선택한 지역이 없어요. 아래에서 지역을 선택해 주세요.</p>}
      </div>
      <div className={styles.regionPicker}>
        <div className={styles.regionToolbar}><strong>지역 선택하기</strong><span>시·도를 고른 뒤 시·군·구를 선택해 주세요</span></div>
        {options.isPending ? <p className={styles.regionEmpty} role="status">지역 목록을 불러오는 중이에요…</p> : options.isError ? (
          <div className={styles.regionEmpty} role="alert">지역 목록을 불러오지 못했어요. <button type="button" className={styles.regionRetry} onClick={() => void options.refetch()}>다시 시도</button></div>
        ) : (
          <div className={styles.regionBrowser}>
            <div className={styles.provincePanel}>
              <div className={styles.provinceHeading}>시·도</div>
              <div className={styles.provinceList} role="group" aria-label="시·도 선택">
                {provinces.map((p) => (
                  <button type="button" key={p} className={styles.provinceItem} aria-pressed={p === current} onClick={() => { setProvince(p); setSearch(''); }}>
                    {p}<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="m9 18 6-6-6-6" /></svg>
                  </button>
                ))}
              </div>
            </div>
            <div className={styles.districtPanel}>
              <div className={styles.districtHeading}><strong>{current || '선택 가능한 지역이 없어요'}</strong><small>시·군·구 선택</small></div>
              <label className={styles.districtSearch} htmlFor="district-search">
                <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="11" cy="11" r="7" /><path d="m16.5 16.5 4 4" /></svg>
                <span className={styles.srOnly}>시·군·구 검색</span>
                <input id="district-search" type="search" placeholder="시·군·구 검색" autoComplete="off" value={search} aria-invalid={error || undefined} aria-describedby={error ? 'region-error' : undefined} onChange={(e) => setSearch(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') e.preventDefault(); }} />
              </label>
              <div className={styles.districtGrid} role="group" aria-label={`${current} 시·군·구 선택`}>
                {districts.map((r) => {
                  const picked = regions.some((x) => x.code === r.key);
                  return (
                    <button type="button" key={r.key} className={styles.districtItem} aria-pressed={picked} disabled={!picked && regions.length >= 3} onClick={() => toggle({ code: r.key, name: r.district ?? r.key })}>
                      <span className={styles.districtName}>{r.district ?? r.key}</span><span className={styles.districtCount}>{r.count}</span>
                      {picked && <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="m3 12 6 6L21 6" /></svg>}
                    </button>
                  );
                })}
                {districts.length === 0 && <p className={styles.regionEmpty} role="status">{search.trim() ? '검색 결과가 없어요.' : '현재 선택할 수 있는 지역이 없어요.'}</p>}
              </div>
            </div>
          </div>
        )}
      </div>
      <p className={styles.regionFooter}>지금 모집 중인 공고가 있는 지역만 보여요. 숫자는 주택 수예요.</p>
      {error && <p className={styles.preferenceError} id="region-error" role="alert">관심 지역을 1곳 이상 선택해 주세요.</p>}
    </section>
  );
}

function PreferenceHeading({ id, title, required = false, description, counter }: { id: string; title: string; required?: boolean; description: ReactNode; counter?: ReactNode }) {
  return <div className={styles.preferenceSectionHead}><div><div className={styles.preferenceTitleRow}><h3 id={id}>{title}</h3><span className={`${styles.requirement} ${required ? styles.requiredRequirement : ''}`}>{required ? '필수' : '선택'}</span></div><p>{description}</p></div>{counter}</div>;
}

function PreferenceInput({ id, label, unit, placeholder, min = 0, max, step, value, help, invalid, describedBy, onChange }: {
  id: string; label: string; unit: string; placeholder: string; min?: number; max?: number; step?: number; value?: number | null; help?: string; invalid?: boolean; describedBy?: string; onChange: (value: number | null) => void;
}) {
  return (
    <div className={styles.preferenceField}>
      <label htmlFor={id}>{label}<span className={styles.srOnly}> ({unit})</span></label>
      <div className={styles.preferenceInputWrap}>
        <input className={styles.preferenceControl} id={id} type="number" inputMode={step ? 'decimal' : 'numeric'} min={min} max={max} step={step} placeholder={placeholder} value={value ?? ''} aria-invalid={invalid || undefined} aria-describedby={[describedBy, help ? `${id}-help` : undefined].filter(Boolean).join(' ') || undefined} onChange={(e) => onChange(numOrNull(e.target.value))} />
        <span className={styles.preferenceUnit} aria-hidden="true">{unit}</span>
      </div>
      {help && <p className={styles.preferenceHint} id={`${id}-help`}>{help}</p>}
    </div>
  );
}

/** 두 단계 모두 위쪽 라벨과 아래쪽 컨트롤의 같은 위계를 사용한다. */
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
        <span>{label}{unit && <span className={styles.srOnly}> ({unit})</span>}</span><span className={`${styles.requirement} ${required ? styles.requiredRequirement : ''}`}>{required ? '필수' : '선택'}</span>
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
    <div className={`${styles.fieldRow} ${styles.radioField}`}>
      <div className={styles.fieldLabel} id={`${name}-label`}>
        <span>{label}</span><span className={`${styles.requirement} ${styles.requiredRequirement}`}>필수</span>
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
