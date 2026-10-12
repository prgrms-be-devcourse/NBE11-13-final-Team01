import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { profileApi } from '../api/profile';
import { ApiError } from '../api/client';
import type { HousingType, Region, RentalType, UserPreference, UserProfile } from '../types';
import { HOUSING_LABEL, RENTAL_LABEL } from '../utils/format';

/** 사전 기입: 1단계 기본 정보, 2단계 희망 주거조건. 저장하면 추천이 다시 계산된다. */
export default function OnboardingPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const [step, setStep] = useState<1 | 2>(1);

  const profileQ = useQuery({ queryKey: ['profile'], queryFn: profileApi.get, retry: false });
  const prefQ = useQuery({ queryKey: ['preference'], queryFn: profileApi.getPreference, retry: false });

  const [profile, setProfile] = useState<UserProfile>({
    birthDate: '',
    job: '',
    married: false,
    householdSize: 1,
    monthlyIncome: null,
    totalAssets: null,
    carValue: null,
    homeless: true,
  });
  const [pref, setPref] = useState<UserPreference>({
    regions: [],
    depositMin: null,
    depositMax: null,
    rentMin: null,
    rentMax: null,
    areaMin: null,
    areaMax: null,
    housingTypes: [],
    rentalTypes: [],
    monthlyBudget: null,
  });

  useEffect(() => {
    if (profileQ.data) setProfile(profileQ.data);
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
      nav('/recommendations');
    },
  });

  const err = (m: { isError: boolean; error: unknown }) => (m.isError ? (m.error as ApiError) : null);

  return (
    <div className="onboarding">
      <ol className="steps">
        <li className={step === 1 ? 'on' : 'done'}>기본 정보</li>
        <li className={step === 2 ? 'on' : ''}>희망 주거조건</li>
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
          <p className="muted">지원조건 판정에 쓰여요. 소득·자산은 비워도 되지만, 비우면 "확인 필요"로 표시돼요.</p>

          <label>
            생년월일
            <input type="date" required value={profile.birthDate} onChange={(e) => setProfile({ ...profile, birthDate: e.target.value })} />
          </label>
          <label>
            직업
            <input value={profile.job ?? ''} onChange={(e) => setProfile({ ...profile, job: e.target.value })} />
          </label>
          <label className="toggle">
            <input type="checkbox" checked={profile.married} onChange={(e) => setProfile({ ...profile, married: e.target.checked })} />
            결혼했어요
          </label>
          <label>
            세대원 수
            <input type="number" min={1} max={10} required value={profile.householdSize} onChange={(e) => setProfile({ ...profile, householdSize: Number(e.target.value) })} />
          </label>
          <label>
            월소득 (만 원)
            <input type="number" min={0} value={profile.monthlyIncome ?? ''} onChange={(e) => setProfile({ ...profile, monthlyIncome: e.target.value === '' ? null : Number(e.target.value) })} />
          </label>
          <label>
            총자산 (만 원)
            <input type="number" min={0} value={profile.totalAssets ?? ''} onChange={(e) => setProfile({ ...profile, totalAssets: e.target.value === '' ? null : Number(e.target.value) })} />
          </label>
          <label>
            자동차가액 (만 원)
            <input type="number" min={0} value={profile.carValue ?? ''} onChange={(e) => setProfile({ ...profile, carValue: e.target.value === '' ? null : Number(e.target.value) })} />
          </label>
          <label className="toggle">
            <input type="checkbox" checked={profile.homeless} onChange={(e) => setProfile({ ...profile, homeless: e.target.checked })} />
            무주택 세대구성원이에요
          </label>

          {err(saveProfile) && <FormError e={err(saveProfile)!} />}
          <div className="form__actions">
            <button className="btn" type="submit" disabled={saveProfile.isPending}>
              다음
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

          <RegionEditor regions={pref.regions} onChange={(regions) => setPref({ ...pref, regions })} />

          <div className="grid2">
            <label>
              보증금 최소
              <input type="number" min={0} value={pref.depositMin ?? ''} onChange={(e) => setPref({ ...pref, depositMin: numOrNull(e.target.value) })} />
            </label>
            <label>
              보증금 최대
              <input type="number" min={0} value={pref.depositMax ?? ''} onChange={(e) => setPref({ ...pref, depositMax: numOrNull(e.target.value) })} />
            </label>
            <label>
              월 임대료 최소
              <input type="number" min={0} value={pref.rentMin ?? ''} onChange={(e) => setPref({ ...pref, rentMin: numOrNull(e.target.value) })} />
            </label>
            <label>
              월 임대료 최대
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
            <legend>공공임대 유형</legend>
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
          <div className="form__actions">
            <button className="btn btn--ghost" type="button" onClick={() => setStep(1)}>
              이전
            </button>
            <button className="btn" type="submit" disabled={savePref.isPending || pref.regions.length === 0}>
              저장하고 추천 보기
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

const SEOUL_GU: { code: string; name: string }[] = [
  { code: '11110', name: '종로구' }, { code: '11140', name: '중구' }, { code: '11170', name: '용산구' },
  { code: '11200', name: '성동구' }, { code: '11215', name: '광진구' }, { code: '11230', name: '동대문구' },
  { code: '11260', name: '중랑구' }, { code: '11290', name: '성북구' }, { code: '11305', name: '강북구' },
  { code: '11320', name: '도봉구' }, { code: '11350', name: '노원구' }, { code: '11380', name: '은평구' },
  { code: '11410', name: '서대문구' }, { code: '11440', name: '마포구' }, { code: '11470', name: '양천구' },
  { code: '11500', name: '강서구' }, { code: '11530', name: '구로구' }, { code: '11545', name: '금천구' },
  { code: '11560', name: '영등포구' }, { code: '11590', name: '동작구' }, { code: '11620', name: '관악구' },
  { code: '11650', name: '서초구' }, { code: '11680', name: '강남구' }, { code: '11710', name: '송파구' },
  { code: '11740', name: '강동구' },
];

/** 관심 지역 최대 3곳, 고른 순서가 순위 */
function RegionEditor({ regions, onChange }: { regions: Region[]; onChange: (r: Region[]) => void }) {
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
      <legend>관심 지역 (최대 3곳, 고른 순서가 우선순위) — 서울</legend>
      <div className="chips">
        {regions.map((r) => (
          <button type="button" key={r.code} className="chip chip--on" onClick={() => toggle(r)}>
            {r.rank} {r.name} ×
          </button>
        ))}
      </div>
      <div className="chips chips--dense">
        {SEOUL_GU.filter((g) => !regions.some((r) => r.code === g.code)).map((g) => (
          <button type="button" key={g.code} className="chip" disabled={regions.length >= 3} onClick={() => toggle(g)}>
            {g.name}
          </button>
        ))}
      </div>
    </fieldset>
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
