package com.steerclub.steerproof.claim;

import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.Entailment;
import com.steerclub.steerproof.common.FindingClass;
import com.steerclub.steerproof.common.PopulationFit;
import com.steerclub.steerproof.common.Risk;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "claims")
public class ClaimEntity {

    @Id
    private String id;

    @Column(name = "paper_id", nullable = false)
    private String paperId;

    @Column(name = "passage_id")
    private String passageId;

    @Column(nullable = false, columnDefinition = "text")
    private String quote;

    @Column(name = "scientific_claim", nullable = false, columnDefinition = "text")
    private String scientificClaim;

    @Column(name = "consumer_draft", columnDefinition = "text")
    private String consumerDraft;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String numbers = "[]";

    @Column(name = "study_design")
    private String studyDesign;

    private String population;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_class", nullable = false)
    private FindingClass findingClass;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String limitations = "[]";

    @Column(name = "quote_score")
    private Integer quoteScore;

    @Column(name = "numbers_ok")
    private Boolean numbersOk;

    @Enumerated(EnumType.STRING)
    private Entailment entailment;

    @Enumerated(EnumType.STRING)
    private Risk risk;

    @Column(name = "risk_reason")
    private String riskReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "population_fit")
    private PopulationFit populationFit;

    @Column(name = "population_reason")
    private String populationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "doctor_status", nullable = false)
    private DoctorStatus doctorStatus = DoctorStatus.PENDING;

    @Column(name = "edited_copy", columnDefinition = "text")
    private String editedCopy;

    @Column(name = "doctor_name")
    private String doctorName;

    @Column(name = "doctor_note")
    private String doctorNote;

    @Column(name = "review_seconds", nullable = false)
    private Integer reviewSeconds = 0;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "is_seeded_bad", nullable = false)
    private Boolean isSeededBad = false;

    public ClaimEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPaperId() { return paperId; }
    public void setPaperId(String paperId) { this.paperId = paperId; }
    public String getPassageId() { return passageId; }
    public void setPassageId(String passageId) { this.passageId = passageId; }
    public String getQuote() { return quote; }
    public void setQuote(String quote) { this.quote = quote; }
    public String getScientificClaim() { return scientificClaim; }
    public void setScientificClaim(String scientificClaim) { this.scientificClaim = scientificClaim; }
    public String getConsumerDraft() { return consumerDraft; }
    public void setConsumerDraft(String consumerDraft) { this.consumerDraft = consumerDraft; }
    public String getNumbers() { return numbers; }
    public void setNumbers(String numbers) { this.numbers = numbers; }
    public String getStudyDesign() { return studyDesign; }
    public void setStudyDesign(String studyDesign) { this.studyDesign = studyDesign; }
    public String getPopulation() { return population; }
    public void setPopulation(String population) { this.population = population; }
    public FindingClass getFindingClass() { return findingClass; }
    public void setFindingClass(FindingClass findingClass) { this.findingClass = findingClass; }
    public String getLimitations() { return limitations; }
    public void setLimitations(String limitations) { this.limitations = limitations; }
    public Integer getQuoteScore() { return quoteScore; }
    public void setQuoteScore(Integer quoteScore) { this.quoteScore = quoteScore; }
    public Boolean getNumbersOk() { return numbersOk; }
    public void setNumbersOk(Boolean numbersOk) { this.numbersOk = numbersOk; }
    public Entailment getEntailment() { return entailment; }
    public void setEntailment(Entailment entailment) { this.entailment = entailment; }
    public Risk getRisk() { return risk; }
    public void setRisk(Risk risk) { this.risk = risk; }
    public String getRiskReason() { return riskReason; }
    public void setRiskReason(String riskReason) { this.riskReason = riskReason; }
    public PopulationFit getPopulationFit() { return populationFit; }
    public void setPopulationFit(PopulationFit populationFit) { this.populationFit = populationFit; }
    public String getPopulationReason() { return populationReason; }
    public void setPopulationReason(String populationReason) { this.populationReason = populationReason; }
    public DoctorStatus getDoctorStatus() { return doctorStatus; }
    public void setDoctorStatus(DoctorStatus doctorStatus) { this.doctorStatus = doctorStatus; }
    public String getEditedCopy() { return editedCopy; }
    public void setEditedCopy(String editedCopy) { this.editedCopy = editedCopy; }
    public String getDoctorName() { return doctorName; }
    public void setDoctorName(String doctorName) { this.doctorName = doctorName; }
    public String getDoctorNote() { return doctorNote; }
    public void setDoctorNote(String doctorNote) { this.doctorNote = doctorNote; }
    public Integer getReviewSeconds() { return reviewSeconds; }
    public void setReviewSeconds(Integer reviewSeconds) { this.reviewSeconds = reviewSeconds; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Boolean getIsSeededBad() { return isSeededBad; }
    public void setIsSeededBad(Boolean isSeededBad) { this.isSeededBad = isSeededBad; }
}
