package com.steerclub.steerproof.passage;

import jakarta.persistence.*;

@Entity
@Table(name = "passages")
public class PassageEntity {

    @Id
    private String id;

    @Column(name = "paper_id", nullable = false)
    private String paperId;

    private String section;

    @Column(nullable = false)
    private String kind; // paragraph | table | caption | list | title

    @Column(nullable = false)
    private Integer page;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String bbox; // {l,t,r,b}

    @Column(name = "page_width")
    private Float pageWidth;

    @Column(name = "page_height")
    private Float pageHeight;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(nullable = false)
    private Integer ord;

    public PassageEntity() {}

    public PassageEntity(String id, String paperId, String section, String kind, Integer page, String bbox, Float pageWidth, Float pageHeight, String text, Integer ord) {
        this.id = id;
        this.paperId = paperId;
        this.section = section;
        this.kind = kind;
        this.page = page;
        this.bbox = bbox;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.text = text;
        this.ord = ord;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPaperId() { return paperId; }
    public void setPaperId(String paperId) { this.paperId = paperId; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public String getBbox() { return bbox; }
    public void setBbox(String bbox) { this.bbox = bbox; }
    public Float getPageWidth() { return pageWidth; }
    public void setPageWidth(Float pageWidth) { this.pageWidth = pageWidth; }
    public Float getPageHeight() { return pageHeight; }
    public void setPageHeight(Float pageHeight) { this.pageHeight = pageHeight; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
}
