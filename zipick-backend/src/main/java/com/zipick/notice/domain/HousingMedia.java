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
        name = "housing_media",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_media_source_key",
                columnNames = {"housing_id", "source_key"}
        )
)
public class HousingMedia extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "housing_media_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "housing_id", nullable = false)
    private Housing housing;

    @Column(name = "source_key", nullable = false, length = 500)
    private String sourceKey;

    @Column(name = "media_type", nullable = false, length = 50)
    private String mediaType;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(name = "source_url", nullable = false, length = 2000)
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    protected HousingMedia() {
    }

    public HousingMedia(Housing housing, String sourceKey, String mediaType, String sourceUrl) {
        this.housing = housing;
        this.sourceKey = sourceKey;
        this.mediaType = mediaType;
        this.sourceUrl = sourceUrl;
    }

    public void synchronize(String mediaType, String fileName, String sourceUrl, String rawPayload) {
        this.mediaType = mediaType;
        this.fileName = fileName;
        this.sourceUrl = sourceUrl;
        this.rawPayload = rawPayload == null ? this.rawPayload : rawPayload;
    }

    public Long getId() { return id; }
    public Housing getHousing() { return housing; }
    public String getMediaType() { return mediaType; }
    public String getFileName() { return fileName; }
    public String getSourceUrl() { return sourceUrl; }
}
