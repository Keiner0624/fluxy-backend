package com.fluxyBackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "categories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String emoji; // ej: "🍕", "👕", "📱"

    @Column(length = 300)
    private String description;

    /** Posición en la tienda; menor va primero. */
    private Integer sortOrder;

    /** Una categoría inactiva no se muestra en la tienda. Nullable por ddl-auto. */
    private Boolean active;

    @ManyToOne
    @JoinColumn(name = "company_id")
    @JsonIgnore
    private Company company;

    public Boolean getActive() {
        return active == null || active;
    }
}
