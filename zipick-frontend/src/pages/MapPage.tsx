import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getMapHousingDetail, getOpenMapHousings } from '../api/mapHousing';
import type { MapHousingSupply } from '../api/mapHousing';
import { HousingInfraSection, MapPanel, RoadviewPanel, toMapPins } from '../features/map';

function valueOrDash(value?: string | number | null) {
  return value == null || value === '' ? '정보 없음' : String(value);
}

function money(value?: number | null) {
  return value == null ? '정보 없음' : `${value.toLocaleString()}원`;
}

function area(value?: number | null) {
  return value == null ? '정보 없음' : `${value}㎡ (${(value / 3.3058).toFixed(1)}평)`;
}

function isImageFile(fileName?: string | null) {
  return Boolean(fileName?.match(/\.(?:png|jpe?g|gif|webp)$/i));
}

function SupplyCard({ supply }: { supply: MapHousingSupply }) {
  const unit = [supply.buildingDong, supply.unitNumber].filter(Boolean).join(' ');
  return (
    <article className="map-detail__supply">
      <strong>{supply.housingType || supply.supplyType || unit || '공급 조건'}</strong>
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

export default function MapPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const listQuery = useQuery({ queryKey: ['map-housings', 'OPEN'], queryFn: getOpenMapHousings });
  const detailQuery = useQuery({
    queryKey: ['map-housing', selectedId],
    queryFn: () => getMapHousingDetail(selectedId!),
    enabled: selectedId != null,
  });
  const pins = useMemo(() => toMapPins(listQuery.data?.housings ?? []), [listQuery.data]);
  const detail = detailQuery.data;
  const buildingPhotos = detail?.media.filter((file) => file.mediaType === 'BUILDING_PHOTO') ?? [];
  const housingPlans = detail?.media.filter((file) => file.mediaType === 'FLOOR_PLAN' || file.mediaType === 'SITE_PLAN') ?? [];
  const noticePlans = detail?.attachments.filter((file) => file.attachmentType === 'FLOOR_PLAN') ?? [];
  const mediaPreviewUrl = (mediaId: number) =>
    `/api/v1/map/housings/${detail!.housingId}/media/${mediaId}/content`;
  const attachmentPreviewUrl = (attachmentId: number) =>
    `/api/v1/map/housings/${detail!.housingId}/attachments/${attachmentId}/content`;

  return (
    <div className="content map-page">
      <div className="content__head">
        <h1>공급주택 지도</h1>
        <p className="muted">현재 모집 중이며 좌표가 확인된 공급주택 {listQuery.data?.total ?? 0}곳</p>
      </div>
      {listQuery.isLoading && <p className="muted">공급주택을 불러오는 중…</p>}
      {listQuery.isError && <p className="warn">공급주택 데이터를 불러오지 못했습니다.</p>}

      <div className="map-workspace">
        <MapPanel
          pins={pins}
          selectedId={selectedId}
          onSelect={setSelectedId}
          className="map-panel"
        />

        <aside className="map-detail" aria-live="polite">
          {selectedId == null && <p className="map-detail__empty">지도에서 집을 선택하면 상세정보와 로드뷰가 표시됩니다.</p>}
          {detailQuery.isLoading && <p className="map-detail__empty">상세정보를 불러오는 중…</p>}
          {detailQuery.isError && <p className="map-detail__empty">상세정보를 불러오지 못했습니다.</p>}
          {detail && (
            <>
              {buildingPhotos.length > 0 ? (
                <section className="housing-photo-gallery" aria-label="공급주택 사진">
                  {buildingPhotos.map((photo) => (
                    <a key={photo.mediaId} href={photo.sourceUrl} target="_blank" rel="noreferrer">
                      <img src={mediaPreviewUrl(photo.mediaId)} alt={photo.fileName || `${detail.name || '공급주택'} 건물 사진`} />
                    </a>
                  ))}
                </section>
              ) : (
                <p className="housing-photo-gallery__empty">LH에서 제공한 건물 사진이 없습니다.</p>
              )}
              <RoadviewPanel latitude={detail.latitude} longitude={detail.longitude} />
              <div className="map-detail__body">
                <span className="badge">모집중</span>
                <h2>{detail.name || detail.noticeTitle}</h2>
                <p className="map-detail__address">{detail.address}</p>

                <dl className="map-detail__facts map-detail__facts--base">
                  <div><dt>공급명</dt><dd>{detail.noticeTitle}</dd></div>
                  <div><dt>공급기관</dt><dd>{valueOrDash(detail.agencyName)}</dd></div>
                  <div><dt>임대유형</dt><dd>{valueOrDash(detail.rentalType)}</dd></div>
                  <div><dt>단지·주택명</dt><dd>{valueOrDash(detail.name)}</dd></div>
                  <div><dt>난방방식</dt><dd>{valueOrDash(detail.heatingMethod)}</dd></div>
                  <div><dt>총 세대수</dt><dd>{detail.totalHouseholdCount == null ? '정보 없음' : `${detail.totalHouseholdCount}세대`}</dd></div>
                </dl>

                <HousingInfraSection housingId={detail.housingId} />

                <h3>공급 조건</h3>
                {detail.supplies.length === 0
                  ? <p className="muted">등록된 공급 조건이 없습니다.</p>
                  : detail.supplies.map((supply) => <SupplyCard key={supply.housingSupplyId} supply={supply} />)}

                {(housingPlans.length > 0 || noticePlans.length > 0) && (
                  <section>
                    <h3>평면도·도면</h3>
                    <div className="floor-plan-gallery">
                      {housingPlans.map((file) => (
                        <a key={`media-${file.mediaId}`} href={file.sourceUrl} target="_blank" rel="noreferrer">
                          <img src={mediaPreviewUrl(file.mediaId)} alt={file.fileName || '평면도'} />
                          <span>{file.fileName || '평면도 보기'}</span>
                        </a>
                      ))}
                      {noticePlans.map((file) => (
                        <a key={`attachment-${file.attachmentId}`} href={file.sourceUrl} target="_blank" rel="noreferrer">
                          {isImageFile(file.fileName) && <img src={attachmentPreviewUrl(file.attachmentId)} alt={file.fileName || '평면도'} />}
                          <span>{file.fileName || '평면도 보기'}</span>
                        </a>
                      ))}
                    </div>
                  </section>
                )}

                {detail.detailUrl && (
                  <a className="btn map-detail__notice-link" href={detail.detailUrl} target="_blank" rel="noreferrer">
                    공고 상세 페이지 열기
                  </a>
                )}
              </div>
            </>
          )}
        </aside>
      </div>
    </div>
  );
}
