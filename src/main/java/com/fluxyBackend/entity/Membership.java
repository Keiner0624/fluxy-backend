package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "company_id"}))
public class Membership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "company_id", nullable = false)
    private Long companyId;
    private String role;
    private String status;
    private Instant joinedAt;

    public Membership(Long userId, Long companyId) {
        this.userId = userId;
        this.companyId = companyId;
        this.role = "OWNER";
        this.status = "ACTIVE";
        this.joinedAt = Instant.now();
    }
}
