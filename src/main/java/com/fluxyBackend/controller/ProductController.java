package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Productos", description = "Catálogo del vendedor autenticado.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService prodcutService;

    @Operation(summary = "Crear un producto",
            description = "Asocia el producto a la empresa del usuario. Responde 403 cuando se alcanza el límite del plan.")
    @PostMapping
    public Prodcut create(@Valid @RequestBody Prodcut prodcut, Authentication authentication) {
        return prodcutService.createProduct(prodcut, authentication.getName());
    }
    @Operation(summary = "Listar mis productos",
            description = "Devuelve el catálogo de la empresa del usuario.")
    @GetMapping
    public List<Prodcut> getAll(Authentication authentication) {
        return prodcutService.getAll(authentication.getName());
    }

    @Operation(summary = "Actualizar un producto",
            description = "Actualiza un producto perteneciente a la empresa del usuario.")
    @PutMapping("/{id}")
    public Prodcut update(@PathVariable Long id, @Valid @RequestBody Prodcut prodcut, Authentication authentication) {
        return prodcutService.update(id, prodcut, authentication.getName());
    }

    @Operation(summary = "Eliminar un producto",
            description = "Elimina un producto de la empresa del usuario. La respuesta exitosa es 200 sin cuerpo.")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, Authentication authentication) {
        prodcutService.delete(id, authentication.getName());
    }
}
