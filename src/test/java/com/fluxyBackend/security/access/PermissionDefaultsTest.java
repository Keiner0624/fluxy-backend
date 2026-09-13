package com.fluxyBackend.security.access;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionDefaultsTest {

    @Test
    void elDuenioTieneTodoAunqueSusPermisosGuardadosDiganOtraCosa() {
        assertThat(AccessService.effectivePermissions(MemberRole.OWNER, "PRODUCT_VIEW"))
                .isEqualTo(EnumSet.allOf(Permission.class));
    }

    @Test
    void facturacionEsSoloDelDuenio() {
        assertThat(AccessService.effectivePermissions(MemberRole.ADMIN, null)).doesNotContain(Permission.BILLING_MANAGE);
        // Ni siquiera eligiéndolo a mano.
        assertThat(AccessService.effectivePermissions(MemberRole.ADMIN, "BILLING_MANAGE,ORDER_VIEW"))
                .containsExactly(Permission.ORDER_VIEW);
    }

    @Test
    void vendedorOperaPedidosPeroNoTocaElCatalogoNiElEquipo() {
        var seller = AccessService.effectivePermissions(MemberRole.SELLER, null);
        assertThat(seller).contains(Permission.ORDER_UPDATE, Permission.ORDER_CANCEL, Permission.CUSTOMER_UPDATE);
        assertThat(seller).doesNotContain(Permission.PRODUCT_DELETE, Permission.TEAM_INVITE,
                Permission.PAYMENT_REFUND, Permission.INVENTORY_ADJUST);
    }

    @Test
    void soloLecturaNoModificaNada() {
        assertThat(AccessService.effectivePermissions(MemberRole.VIEWER, null))
                .allMatch(p -> p.name().endsWith("_VIEW"));
    }

    @Test
    void permisosDesconocidosSeIgnoran() {
        assertThat(Permission.parse("ORDER_VIEW, NO_EXISTE ,,REPORT_VIEW"))
                .containsExactlyInAnyOrder(Permission.ORDER_VIEW, Permission.REPORT_VIEW);
    }
}
