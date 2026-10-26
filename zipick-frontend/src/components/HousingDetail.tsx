import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { MapHousingDetail, MapHousingSupply } from '../api/mapHousing';
import type { HouseFilter } from '../types';
import { hasSupplyConditions, matchesSupplyConditions, sortByMatch } from '../utils/supplyMatch';
import { HousingInfraSection, RoadviewPanel } from '../features/map';
import InterestButton from './InterestButton';
import { useInterest } from '../hooks/useInterest';
import { STATUS_LABEL, applySchedule, area } from '../utils/format';

/**
 * 공고 하나의 공개 상세. 지도 우측 패널과 /houses/:houseId 페이지가 **같은 컴포넌트**를 쓴다.
 * 비회원과 로그인 회원에게 보여 주는 기본 정보가 어긋나지 않도록 한 곳에서만 그린다.
 *
 * 점수·추천 근거처럼 회원 전용 내용은 `score` 슬롯으로 받아 제목 아래에 끼운다.
 */
interface Props {
  detail: MapHousingDetail;
  /** 추천 점수·근거 영역. 없으면 그리지 않는다 */
  score?: ReactNode;
  /** 상세 페이지로 가는 링크를 보여 줄지 (지도 패널에서만 필요) */
  showPageLink?: boolean;
  /** 전체 공고 모드에서 적용 중인 필터. 조건을 만족한 공급 유형을 앞에 두고 표시한다 */
  filter?: HouseFilter;
}

function valueOrDash(value?: string | number | null) {
  return value == null || value === '' ? '정보 없음' : String(value);
}

function money(value?: number | null) {
  return value == null ? '정보 없음' : `${value.toLocaleString()}원`;
}

function isImageFile(fileName?: string | null) {
  return Boolean(fileName?.match(/\.(?:png|jpe?g|gif|webp)$/i));
}

export default function HousingDetail({ detail, score, showPageLink, filter }: Props) {
  // 상세 API 는 활성 공급 호실만 돌려준다. 비어 있으면 '수집된 활성 공급 정보가 없다'는 뜻이다.
  const highlight = filter != null && hasSupplyConditions(filter);
  const supplies = highlight ? sortByMatch(detail.supplies, filter!) : detail.supplies;
  const schedule = applySchedule(detail.status, detail.applyStartAt, detail.applyEndAt);
  const photos = detail.media.filter((file) => file.mediaType === 'BUILDING_PHOTO');
  const housingPlans = detail.media.filter((f) => f.mediaType === 'FLOOR_PLAN' || f.mediaType === 'SITE_PLAN');
  const noticePlans = detail.attachments.filter((f) => f.attachmentType === 'FLOOR_PLAN');
  const mediaUrl = (mediaId: number) => `/api/v1/map/housings/${detail.housingId}/media/${mediaId}/content`;
  const attachmentUrl = (id: number) => `/api/v1/map/housings/${detail.housingId}/attachments/${id}/content`;

  return (
    <div className="housing-detail">
      {photos.length > 0 ? (
        <section className="housing-photo-gallery" aria-label="공급주택 사진">
          {photos.map((photo) => (
            <a key={photo.mediaId} href={photo.sourceUrl} target="_blank" rel="noreferrer">
              <img src={mediaUrl(photo.mediaId)} alt={photo.fileName || `${detail.name || '공급주택'} 건물 사진`} />
            </a>
          ))}
        </section>
      ) : (
        <p className="housing-photo-gallery__empty">공급기관이 제공한 건물 사진이 없습니다.</p>
      )}

      {detail.latitude != null && detail.longitude != null ? (
        <RoadviewPanel latitude={detail.latitude} longitude={detail.longitude} />
      ) : (
        <p className="housing-detail__warn">
          이 주택은 아직 정확한 위치가 확인되지 않아 지도에 표시할 수 없어요. 주소와 공고 상세 정보는 확인할 수 있습니다.
        </p>
      )}

      <div className="housing-detail__body">
        <div className="housing-detail__head">
          <span className={`badge badge--${detail.status.toLowerCase()}`}>{STATUS_LABEL[detail.status]}</span>
          <span className={`badge ${schedule.urgent ? 'badge--soon' : ''}`}>{schedule.label}</span>
          <InterestButton noticeId={detail.noticeId} compact />
        </div>

        <h2 className="housing-detail__title">{detail.name || detail.noticeTitle}</h2>
        <p className="housing-detail__address">{detail.address}</p>

        <ApplicationGuideLink noticeId={detail.noticeId} />

        {score}

        <dl className="map-detail__facts map-detail__facts--base">
          <div><dt>공급명</dt><dd>{detail.noticeTitle}</dd></div>
          <div><dt>공급기관</dt><dd>{valueOrDash(detail.agencyName)}</dd></div>
          <div><dt>임대유형</dt><dd>{valueOrDash(detail.rentalType || detail.noticeType)}</dd></div>
          <div><dt>접수 기간</dt><dd>{schedule.period ?? '일정 미정'}</dd></div>
          <div><dt>단지·주택명</dt><dd>{valueOrDash(detail.name)}</dd></div>
          <div><dt>준공</dt><dd>{valueOrDash(detail.buildYear)}</dd></div>
          <div><dt>난방방식</dt><dd>{valueOrDash(detail.heatingMethod)}</dd></div>
          <div><dt>총 세대수</dt><dd>{detail.totalHouseholdCount == null ? '정보 없음' : `${detail.totalHouseholdCount}세대`}</dd></div>
        </dl>

        <HousingInfraSection housingId={detail.housingId} />

        <section>
          <h3>공급 조건</h3>
          {supplies.length === 0 ? (
            <div className="housing-detail__warn">
              <p>공급 조건을 확인하지 못했어요. 자세한 내용은 공고문에서 확인해 주세요.</p>
              {detail.detailUrl && (
                <a className="btn btn--small" href={detail.detailUrl} target="_blank" rel="noreferrer">
                  공고문에서 확인하기 ↗
                </a>
              )}
            </div>
          ) : (
            <>
              {highlight && <p className="muted small">검색 조건을 만족한 공급 유형을 먼저 보여 드려요.</p>}
              {supplies.map((supply) => (
                <SupplyCard
                  key={supply.housingSupplyId}
                  supply={supply}
                  matched={highlight && matchesSupplyConditions(supply, filter!)}
                />
              ))}
            </>
          )}
        </section>

        {(housingPlans.length > 0 || noticePlans.length > 0) && (
          <section>
            <h3>평면도·도면</h3>
            <div className="floor-plan-gallery">
              {housingPlans.map((file) => (
                <a key={`media-${file.mediaId}`} href={file.sourceUrl} target="_blank" rel="noreferrer">
                  <img src={mediaUrl(file.mediaId)} alt={file.fileName || '평면도'} />
                  <span>{file.fileName || '평면도 보기'}</span>
                </a>
              ))}
              {noticePlans.map((file) => (
                <a key={`attachment-${file.attachmentId}`} href={file.sourceUrl} target="_blank" rel="noreferrer">
                  {isImageFile(file.fileName) && <img src={attachmentUrl(file.attachmentId)} alt={file.fileName || '평면도'} />}
                  <span>{file.fileName || '평면도 보기'}</span>
                </a>
              ))}
            </div>
          </section>
        )}

        <div className="housing-detail__links">
          {showPageLink && (
            <Link className="btn btn--ghost btn--small" to={`/houses/${detail.housingId}`}>
              상세 페이지로 보기
            </Link>
          )}
          {detail.detailUrl && (
            <a className="btn btn--small" href={detail.detailUrl} target="_blank" rel="noreferrer">
              공고 원문 보기 ↗
            </a>
          )}
        </div>

        <p className="muted small">최종 자격은 공고문으로 확인하세요. 표시된 정보는 참고용입니다.</p>
      </div>
    </div>
  );
}

/**
 * AI 신청 준비 가이드로 가는 보조 진입점.
 *
 * 결과 자체는 여기에 그리지 않는다. 지도 우측 패널 안에 또 긴 내용을 접어 넣으면
 * 패널 안에서 다시 스크롤하게 되고, 서류를 준비하며 열어 두기도 어렵다.
 * 주 진입점은 관심 공고·일정 화면이고 여기서는 기능이 있다는 것만 알린다.
 */
function ApplicationGuideLink({ noticeId }: { noticeId: number }) {
  const { enabled, isSaved } = useInterest();

  if (!enabled) return null;   // 비회원에게는 ☆ 저장 안내(InterestButton)만 보여 준다

  if (!isSaved(noticeId)) {
    return (
      <p className="guide-cta guide-cta--hint muted small">
        관심 공고로 저장하면 AI 신청 준비 가이드를 이용할 수 있어요.
      </p>
    );
  }
  return (
    <p className="guide-cta">
      <Link className="btn btn--small" to={`/interest-notices/${noticeId}/guide`}>
        AI 신청 준비 가이드 보기
      </Link>
    </p>
  );
}

function SupplyCard({ supply, matched }: { supply: MapHousingSupply; matched?: boolean }) {
  const unit = [supply.buildingDong, supply.unitNumber].filter(Boolean).join(' ');
  return (
    <article className={`map-detail__supply ${matched ? 'map-detail__supply--matched' : ''}`}>
      <strong>
        {supply.housingType || supply.supplyType || unit || '공급 조건'}
        {matched && <span className="badge badge--matched">검색 조건 일치</span>}
      </strong>
      <dl className="map-detail__facts">
        <div><dt>보증금</dt><dd>{money(supply.rentDeposit)}</dd></div>
        <div><dt>월 임대료</dt><dd>{money(supply.monthlyRent)}</dd></div>
        <div><dt>전용면적</dt><dd>{area(supply.exclusiveArea)}</dd></div>
        {unit && <div><dt>동·호</dt><dd>{unit}</dd></div>}
        {supply.floor != null && <div><dt>층</dt><dd>{supply.floor}층</dd></div>}
        {supply.roomCount != null && <div><dt>방</dt><dd>{supply.roomCount}개</dd></div>}
      </dl>
    </article>
  );
}
