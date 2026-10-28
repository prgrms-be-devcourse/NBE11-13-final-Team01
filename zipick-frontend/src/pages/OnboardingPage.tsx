import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { houseApi } from '../api/house';
import { ApiError } from '../api/client';
import type { HousingType, Region, RentalType, StudentStatus, UserPreference, UserProfile } from '../types';
import { HOUSING_LABEL, RENTAL_LABEL } from '../utils/format';
import AhpQuestions from '../components/AhpQuestions';

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
    if (profileQ.data) {
      setProfile(profileQ.data);
      setMarriedPicked(true);
      setHomelessPicked(true);
    }
  }, [profileQ.data]);
  useEffect(() => {
    if (prefQ.data) setPref(prefQ.data);
  }, [prefQ.data]);

  const saveProfile = useMutation({
    mutationFn: () => profileApi.save(profile),
    onSuccess: () => {
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
  // 예산이 하나도 없으면 비용 점수를 못 매겨 모든 집이 '정보 부족' 묶음으로 빠진다 (ScoreCalculator.scoreCost)
  const hasBudget = [pref.depositMax, pref.rentMax, pref.monthlyBudget].some((v) => v != null && v > 0);
  const missingPref = [pref.regions.length === 0 && '관심 지역', !hasBudget && '예산(보증금·월 임대료 상한, 월 예산 중 하나)'].filter(
    Boolean,
  ) as string[];

  return (
    <div className="onboarding">
      <ol className="steps">
        <li className={step === 1 ? 'on' : profileQ.data ? 'done' : ''}>
          <button type="button" className="steps__btn" onClick={() => setStep(1)}>1. 기본 정보</button>
        </li>
        <li className={step === 2 ? 'on' : prefQ.data ? 'done' : ''}>
          <button type="button" className="steps__btn" onClick={() => setStep(2)}>2. 희망 주거조건</button>
        </li>
        <li className={step === 3 ? 'on' : ''}>
          <button type="button" className="steps__btn" onClick={() => setStep(3)} disabled={!prefQ.data}>3. 우선순위</button>
        </li>
      </ol>

      {step === 1 && (
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault();
            saveProfile.mutate();
          }}
        >
          <h1>기본 정보</h1>
          <p className="muted">
            공고 지원조건(연령·혼인·무주택·소득·자산·자동차)을 판정하는 데 쓰여요. 소득·자산·자동차는 비워도 되지만, 비우면 그
            조건은 "확인 필요"로 표시돼요.
          </p>
          <RequiredNote />

          <label>
            <span>
              생년월일 <Req />
            </span>
            <input type="date" required value={profile.birthDate} onChange={(e) => setProfile({ ...profile, birthDate: e.target.value })} />
          </label>

          <YesNo
            required
            label="혼인 중이에요"
            hint="청년 계층(행복주택·청년 매입임대)은 미혼만, 신혼부부 계층은 기혼만 신청할 수 있어요."
            value={marriedPicked ? profile.married : null}
            onChange={(v) => {
              setProfile({ ...profile, married: v });
              setMarriedPicked(true);
            }}
          />
          <YesNo
            required
            label="무주택 세대구성원이에요"
            hint="거의 모든 공공임대의 필수 조건이에요."
            value={homelessPicked ? profile.homeless : null}
            onChange={(v) => {
              setProfile({ ...profile, homeless: v });
              setHomelessPicked(true);
            }}
          />

          <label>
            <span>
              세대원 수 (본인 포함) <Req />
            </span>
            <input type="number" min={1} max={10} required value={profile.householdSize} onChange={(e) => setProfile({ ...profile, householdSize: Number(e.target.value) })} />
            <small className="hint">소득 기준은 가구원 수마다 달라요. 면적도 가구원 수별 최저주거기준과 비교해요.</small>
          </label>

          <label>
            대학생·취업준비생
            <select value={profile.studentStatus} onChange={(e) => setProfile({ ...profile, studentStatus: e.target.value as StudentStatus })}>
              <option value="NONE">해당 없음</option>
              <option value="UNIVERSITY">대학생 (입학·복학 예정 포함)</option>
              <option value="JOB_SEEKER">취업준비생 (졸업·중퇴 후 2년 이내 미취업)</option>
            </select>
            <small className="hint">청년 매입임대는 만 19~39세가 아니어도 대학생·취업준비생이면 신청할 수 있어요.</small>
          </label>

          <div className="grid2">
            <label>
              월소득 (만 원)
              <input type="number" min={0} value={profile.monthlyIncome ?? ''} onChange={(e) => setProfile({ ...profile, monthlyIncome: e.target.value === '' ? null : Number(e.target.value) })} />
              <small className="hint">세전 월평균. 도시근로자 월평균소득 대비 비율로 판정해요.</small>
            </label>
            <label>
              총자산 (만 원)
              <input type="number" min={0} value={profile.totalAssets ?? ''} onChange={(e) => setProfile({ ...profile, totalAssets: e.target.value === '' ? null : Number(e.target.value) })} />
              <small className="hint">부동산 + 자동차 + 금융자산 − 부채.</small>
            </label>
          </div>
          <label>
            자동차가액 (만 원)
            <input type="number" min={0} value={profile.carValue ?? ''} onChange={(e) => setProfile({ ...profile, carValue: e.target.value === '' ? null : Number(e.target.value) })} />
            <small className="hint">차가 없으면 0.</small>
          </label>

          {err(saveProfile) && <FormError e={err(saveProfile)!} />}
          <MissingRequired items={missingProfile} />
          <div className="form__actions">
            <button className="btn" type="submit" disabled={saveProfile.isPending || missingProfile.length > 0}>
              저장하고 다음 →
            </button>
          </div>
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

/** 예/아니오를 명시적으로 고르게 하는 입력. 고르기 전에는 둘 다 꺼져 있다 */
function YesNo({
  label,
  hint,
  required,
  value,
  onChange,
}: {
  label: string;
  hint?: string;
  required?: boolean;
  value: boolean | null;
  onChange: (v: boolean) => void;
}) {
  return (
    <fieldset className="field">
      <legend>
        {label} {required && <Req />}
      </legend>
      <div className="chips">
        <button type="button" className={`chip ${value === true ? 'chip--on' : ''}`} onClick={() => onChange(true)}>예</button>
        <button type="button" className={`chip ${value === false ? 'chip--on' : ''}`} onClick={() => onChange(false)}>아니오</button>
      </div>
      {hint && <small className="hint">{hint}</small>}
    </fieldset>
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
