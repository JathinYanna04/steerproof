package com.steerclub.steerproof.asset;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "asset_claims")
@IdClass(AssetClaimId.class)
public class AssetClaimEntity {

    @Id
    @Column(name = "asset_id")
    private String assetId;

    @Id
    @Column(name = "claim_id")
    private String claimId;

    @Id
    @Column(nullable = false)
    private String location;

    public AssetClaimEntity() {}

    public AssetClaimEntity(String assetId, String claimId, String location) {
        this.assetId = assetId;
        this.claimId = claimId;
        this.location = location;
    }

    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}

class AssetClaimId implements Serializable {
    private String assetId;
    private String claimId;
    private String location;

    public AssetClaimId() {}

    public AssetClaimId(String assetId, String claimId, String location) {
        this.assetId = assetId;
        this.claimId = claimId;
        this.location = location;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssetClaimId that = (AssetClaimId) o;
        return Objects.equals(assetId, that.assetId) &&
               Objects.equals(claimId, that.claimId) &&
               Objects.equals(location, that.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assetId, claimId, location);
    }
}
