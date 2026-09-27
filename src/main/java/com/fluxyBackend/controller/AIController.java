package com.fluxyBackend.controller;

import com.fluxyBackend.ai.AiContentService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Textos generados con IA (Google Gemini). Plan Business; lo generado es una propuesta que el
 * vendedor revisa y edita antes de guardar.
 */
@Tag(name = "Inteligencia artificial", description = "Descripciones de productos y textos de campañas con IA.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AIController {

    private final AiContentService ai;
    private final AccessService access;

    @Operation(summary = "Generar una descripción de producto",
            description = "Requiere el plan Business (403 PLAN_REQUIRED) y name. price, category y notes (descripción actual a mejorar) son opcionales. "
                    + "Máximo 60 por hora por empresa (429). 503 AI_UNAVAILABLE o 502 AI_FAILED si la IA no responde.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"name\":\"Mochila urbana\",\"price\":\"89.90\",\"category\":\"Accesorios\"}"))))
    @PostMapping("/describe")
    @RequirePermission(value = {Permission.PRODUCT_CREATE, Permission.PRODUCT_UPDATE}, any = true)
    public AiContentService.ProductDescription describe(@RequestBody AiContentService.ProductRequest request) {
        return ai.describeProduct(access.current().company(), request);
    }

    @Operation(summary = "Generar el texto de una campaña",
            description = "Título, mensaje y llamada a la acción para el asistente de Marketing. Mismo plan, tope y errores que /ai/describe.")
    @PostMapping("/campaign-copy")
    @RequirePermission(value = {Permission.MARKETING_CREATE, Permission.MARKETING_EDIT}, any = true)
    public AiContentService.CampaignCopy campaignCopy(@RequestBody AiContentService.CampaignRequest request) {
        return ai.campaignCopy(access.current().company(), request);
    }
}
