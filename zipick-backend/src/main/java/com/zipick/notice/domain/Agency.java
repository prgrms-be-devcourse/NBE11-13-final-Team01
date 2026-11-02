package com.zipick.notice.domain;

import com.zipick.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "agency")
public class Agency extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agency_id")
    private Long id;

    @Column(name = "agency_code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "agency_name", nullable = false, length = 100)
    private String name;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    protected Agency() {
    }

    public Agency(String code, String name, String sourceUrl) {
        this.code = code;
        this.name = name;
        this.sourceUrl = sourceUrl;
    }

    public void update(String name, String sourceUrl) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            this.sourceUrl = sourceUrl.trim();
        }
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }
}
