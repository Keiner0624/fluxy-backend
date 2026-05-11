package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    // ── Datos personales del vendedor ──────────────────────────────────────
    private String    firstName;
    private String    lastName;
    private LocalDate birthDate;
    // ───────────────────────────────────────────────────────────────────────

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @OneToMany(mappedBy = "owner")
    private List<Prodcut> prodcuts;

    @ManyToOne
    @JoinColumn(name = "company_id")
    private Company company;
}