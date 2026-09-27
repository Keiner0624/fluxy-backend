package com.fluxyBackend.marketing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * Datos de una campaña. Al crear, type, objective y channel son obligatorios. Al editar se envía
 * el contenido completo: los campos opcionales en null se quitan (fechas, cupón, audiencia).
 * type, targetId y channel solo cambian mientras la campaña es borrador.
 */
@Schema(description = "Campaña de marketing. La empresa sale de la sesión, nunca del cuerpo.")
public record CampaignRequest(
        @Size(max = 120) @Schema(example = "Cappuccino fin de semana") String name,
        @Schema(description = "STORE, PRODUCT, CATEGORY o COUPON", example = "PRODUCT") String type,
        @Schema(description = "VISITS, SELL_PRODUCT, PROMOTION o WIN_BACK", example = "SELL_PRODUCT") String objective,
        @Schema(description = "Producto o categoría promocionados") Long targetId,
        @Schema(description = "Cupón asociado; obligatorio en campañas COUPON") Long couponId,
        @Schema(description = "WHATSAPP, INSTAGRAM, FACEBOOK, TIKTOK, DIRECT o QR", example = "WHATSAPP") String channel,
        @Size(max = 120) String title,
        @Size(max = 1000) String message,
        @Size(max = 60) String callToAction,
        @Size(max = 2048) @Pattern(regexp = "^$|^https://\\S+$", message = "debe ser una URL https") String imageUrl,
        @Schema(description = "ALL, NEW, RECURRING, INACTIVE_30, FREQUENT, HIGH_VALUE, TAG, SOURCE, CATEGORY_BUYERS o PRODUCT_BUYERS")
        String segment,
        Long segmentCategoryId,
        @Schema(description = "Etiqueta (TAG), origen (SOURCE) o id de producto (PRODUCT_BUYERS)") @Size(max = 100) String segmentValue,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt) {
}
