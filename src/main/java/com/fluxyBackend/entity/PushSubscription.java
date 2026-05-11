package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "push_subscriptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String p256dh;

    @Column(nullable = false)
    private String auth;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
}