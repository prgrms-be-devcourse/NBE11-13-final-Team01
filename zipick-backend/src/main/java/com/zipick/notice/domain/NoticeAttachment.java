package com.zipick.notice.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "notice_attachment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_attachment_source_key",
                columnNames = {"notice_id", "source_key"}
        )
)
public class NoticeAttachment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_attachment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

    @Column(name = "attachment_type", nullable = false, length = 50)
    private String attachmentType;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "source_url", nullable = false, length = 2000)
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    protected NoticeAttachment() {
    }

    public NoticeAttachment(Notice notice, String sourceKey, String attachmentType, String sourceUrl) {
        this.notice = notice;
        this.sourceKey = sourceKey;
        this.attachmentType = attachmentType;
        this.sourceUrl = sourceUrl;
    }

    public void synchronize(String attachmentType, String fileName, String sourceUrl, String rawPayload) {
        this.attachmentType = attachmentType;
        this.fileName = fileName;
        this.sourceUrl = sourceUrl;
        this.rawPayload = rawPayload == null ? this.rawPayload : rawPayload;
    }

    public Long getId() {
        return id;
    }

    public Notice getNotice() {
        return notice;
    }

    public String getAttachmentType() {
        return attachmentType;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }
}
