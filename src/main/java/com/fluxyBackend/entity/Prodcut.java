package com.fluxyBackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Prodcut {

    /** Stock mínimo cuando el vendedor no definió uno. */
    public static final int DEFAULT_MIN_STOCK = 5;

    public enum Status {
        /** Visible en la tienda. */
        ACTIVE,
        /** Oculto de la tienda sin borrarlo: conserva su historial de ventas. */
        HIDDEN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @NotBlank
    @Size(max = 200)
    private String name;
    @PositiveOrZero
    private double price;
    @PositiveOrZero
    private int stock;
    private String imageUrl;

    @Column(columnDefinition = "TEXT")
    private String images;

    // Columnas del panel 2.0. Son nullables a propósito: ddl-auto las agrega
    // sobre una tabla con datos y NOT NULL fallaría en las filas existentes.
    // Los getters devuelven el valor por defecto.
    @Size(max = 64)
    @Column(length = 64)
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status status;

    @PositiveOrZero
    private Integer minStock;

    /** Costo unitario, para valorizar el inventario. Nunca sale en la tienda pública. */
    @PositiveOrZero
    private Double cost;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @JsonIgnore
    private User owner;

    // No se serializa: iba incrustada en cada producto de la tienda publica,
    // exponiendo el correo y los datos de facturacion del comerciante.
    @ManyToOne
    @JoinColumn(name = "company_id")
    @JsonIgnore
    private Company company;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id", nullable = true)
    private Category category;

    public Status getStatus() {
        return status == null ? Status.ACTIVE : status;
    }

    public int getMinStock() {
        return minStock == null ? DEFAULT_MIN_STOCK : minStock;
    }

    @JsonIgnore
    public boolean isOutOfStock() {
        return stock <= 0;
    }

    @JsonIgnore
    public boolean isLowStock() {
        return stock > 0 && stock <= getMinStock();
    }

    @PrePersist
    void onCreate() {
        if (status == null) status = Status.ACTIVE;
        if (minStock == null) minStock = DEFAULT_MIN_STOCK;
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
