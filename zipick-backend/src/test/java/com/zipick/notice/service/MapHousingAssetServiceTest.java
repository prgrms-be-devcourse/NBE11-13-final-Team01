package com.zipick.notice.service;

import com.zipick.external.housingdetail.ExternalDocumentClient;
import com.zipick.global.error.BusinessException;
import com.zipick.notice.domain.Housing;
import com.zipick.notice.domain.HousingMedia;
import com.zipick.notice.domain.Notice;
import com.zipick.notice.domain.NoticeAttachment;
import com.zipick.notice.domain.NoticeStatus;
import com.zipick.notice.repository.HousingMediaRepository;
import com.zipick.notice.repository.HousingRepository;
import com.zipick.notice.repository.NoticeAttachmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapHousingAssetServiceTest {

    private final HousingRepository housings = mock(HousingRepository.class);
    private final NoticeAttachmentRepository attachments = mock(NoticeAttachmentRepository.class);
    private final HousingMediaRepository media = mock(HousingMediaRepository.class);
    private final ExternalDocumentClient client = mock(ExternalDocumentClient.class);
    private final MapHousingAssetService service = new MapHousingAssetService(housings, attachments, media, client);

    @Test
    void downloadsAllowedAttachmentAndMediaImages() {
        Notice notice = withId(new Notice("LH", "n", "공고", NoticeStatus.OPEN), 1L);
        Housing housing = withId(new Housing(notice, "h", "단지", "서울 강남구 테헤란로 1"), 2L);
        NoticeAttachment attachment = withId(new NoticeAttachment(
                notice, "a", "FLOOR_PLAN", "https://files.lh.or.kr/plan.png?download=1"
        ), 3L);
        attachment.synchronize("FLOOR_PLAN", "plan\r\n.png", attachment.getSourceUrl(), "{}");
        HousingMedia image = withId(new HousingMedia(
                housing, "m", "BUILDING_PHOTO", "https://lh.or.kr/photo.webp"
        ), 4L);
        image.synchronize("BUILDING_PHOTO", null, image.getSourceUrl(), "{}");
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(housing));
        when(attachments.findById(3L)).thenReturn(Optional.of(attachment));
        when(media.findById(4L)).thenReturn(Optional.of(image));
        when(client.getBytes(URI.create(attachment.getSourceUrl()))).thenReturn(new byte[]{1});
        when(client.getBytes(URI.create(image.getSourceUrl()))).thenReturn(new byte[]{2});

        MapHousingAsset attachmentAsset = service.getAttachment(2L, 3L);
        MapHousingAsset mediaAsset = service.getMedia(2L, 4L);

        assertThat(attachmentAsset.contentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(attachmentAsset.fileName()).isEqualTo("plan.png");
        assertThat(mediaAsset.contentType().toString()).isEqualTo("image/webp");
        assertThat(mediaAsset.fileName()).isEqualTo("housing-image");
    }

    @Test
    void rejectsClosedHousingWrongOwnershipTypeExtensionAndHost() {
        Notice closedNotice = withId(new Notice("LH", "n", "공고", NoticeStatus.CLOSED), 1L);
        Housing closed = withId(new Housing(closedNotice, "h", "단지", "서울 강남구 1"), 2L);
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(closed));
        assertThatThrownBy(() -> service.getMedia(2L, 4L)).isInstanceOf(BusinessException.class);

        Notice openNotice = withId(new Notice("LH", "n", "공고", NoticeStatus.OPEN), 1L);
        Housing open = withId(new Housing(openNotice, "h", "단지", "서울 강남구 1"), 2L);
        when(housings.findByIdAndActiveTrue(2L)).thenReturn(Optional.of(open));
        Notice otherNotice = withId(new Notice("LH", "x", "다른 공고", NoticeStatus.OPEN), 9L);
        NoticeAttachment wrong = new NoticeAttachment(otherNotice, "a", "NOTICE", "https://lh.or.kr/a.pdf");
        when(attachments.findById(3L)).thenReturn(Optional.of(wrong));
        assertThatThrownBy(() -> service.getAttachment(2L, 3L)).isInstanceOf(BusinessException.class);

        NoticeAttachment badHost = new NoticeAttachment(openNotice, "a", "FLOOR_PLAN", "https://evil.test/a.jpg");
        badHost.synchronize("FLOOR_PLAN", "a.jpg", "https://evil.test/a.jpg", "{}");
        when(attachments.findById(3L)).thenReturn(Optional.of(badHost));
        assertThatThrownBy(() -> service.getAttachment(2L, 3L)).isInstanceOf(BusinessException.class);

        HousingMedia pdf = new HousingMedia(open, "m", "FLOOR_PLAN", "https://lh.or.kr/a.pdf");
        pdf.synchronize("FLOOR_PLAN", "a.pdf", "https://lh.or.kr/a.pdf", "{}");
        when(media.findById(4L)).thenReturn(Optional.of(pdf));
        assertThatThrownBy(() -> service.getMedia(2L, 4L)).isInstanceOf(BusinessException.class);
    }

    private <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
