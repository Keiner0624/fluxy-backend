package com.fluxyBackend.invoicing;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.IdentityDocumentType;
import com.fluxyBackend.invoicing.enums.TaxAffectation;
import com.fluxyBackend.invoicing.service.AmountInWords;
import com.fluxyBackend.invoicing.service.DocumentCalculator;
import com.fluxyBackend.invoicing.validation.Receiver;
import com.fluxyBackend.invoicing.validation.TaxIdValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas puras de facturación: RUC, importes, montos en letras y datos del receptor. */
class InvoicingRulesTest {

    @Test
    void elRucSeValidaConSuDigitoVerificador() {
        assertThat(TaxIdValidator.isValidRuc("20600000005")).isTrue();
        assertThat(TaxIdValidator.isValidRuc("10456789124")).isTrue();
        assertThat(TaxIdValidator.isValidRuc("20600000004")).isFalse();
        assertThat(TaxIdValidator.isValidRuc("30600000005")).isFalse();
        assertThat(TaxIdValidator.isValidRuc("2060000000")).isFalse();
        assertThat(TaxIdValidator.mask("20600000005")).isEqualTo("20******005");
    }

    @Test
    void elDescuentoSeRepartePorLineasYLosTotalesCuadranAlCentavo() {
        List<DocumentCalculator.Input> lines = List.of(
                new DocumentCalculator.Input(1L, "A1", "Pollo", new BigDecimal("2"), new BigDecimal("118.00")),
                new DocumentCalculator.Input(2L, null, "Gaseosa", new BigDecimal("1"), new BigDecimal("20.00")));
        DocumentCalculator.Result r = DocumentCalculator.calculate(lines, new BigDecimal("128.00"), TaxAffectation.GRAVADO);

        assertThat(r.total()).isEqualByComparingTo("128.00");
        assertThat(r.discount()).isEqualByComparingTo("10.00");
        assertThat(r.subtotal()).isEqualByComparingTo("108.47");
        assertThat(r.tax()).isEqualByComparingTo("19.53");
        assertThat(r.lines().stream().map(DocumentCalculator.Line::total).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("128.00");
        assertThat(r.lines().stream().map(DocumentCalculator.Line::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(r.subtotal());
        assertThat(r.lines().stream().map(DocumentCalculator.Line::tax).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(r.tax());
        // 118 de 138 se lleva 8.55 del descuento; la última línea, el resto.
        assertThat(r.lines().get(0).total()).isEqualByComparingTo("109.45");
        assertThat(r.lines().get(1).total()).isEqualByComparingTo("18.55");

        DocumentCalculator.Result exonerated = DocumentCalculator.calculate(lines, new BigDecimal("138.00"), TaxAffectation.EXONERADO);
        assertThat(exonerated.tax()).isEqualByComparingTo("0.00");
        assertThat(exonerated.subtotal()).isEqualByComparingTo("138.00");
    }

    @Test
    void elMontoEnLetrasSigueLaRepresentacionImpresa() {
        assertThat(AmountInWords.soles(new BigDecimal("124.50"))).isEqualTo("CIENTO VEINTICUATRO CON 50/100 SOLES");
        assertThat(AmountInWords.soles(new BigDecimal("100"))).isEqualTo("CIEN CON 00/100 SOLES");
        assertThat(AmountInWords.soles(new BigDecimal("1000.05"))).isEqualTo("MIL CON 05/100 SOLES");
        assertThat(AmountInWords.soles(new BigDecimal("21031"))).isEqualTo("VEINTIÚN MIL TREINTA Y UNO CON 00/100 SOLES");
        assertThat(AmountInWords.soles(new BigDecimal("1500000"))).isEqualTo("UN MILLÓN QUINIENTOS MIL CON 00/100 SOLES");
    }

    @Test
    void lasReglasDelReceptorDependenDelComprobanteYDelMonto() {
        Receiver generic = Receiver.validate(DocumentType.BOLETA, null, null, null, null, null, new BigDecimal("50"));
        assertThat(generic.documentType()).isEqualTo(IdentityDocumentType.NINGUNO);
        assertThat(generic.name()).isEqualTo(Receiver.GENERIC_NAME);

        assertThatThrownBy(() -> Receiver.validate(DocumentType.BOLETA, null, null, "Ana", null, null, new BigDecimal("700.00")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("S/ 700");
        Receiver withDni = Receiver.validate(DocumentType.BOLETA, "DNI", "4567 8912", "Ana Pérez", null, "ANA@MAIL.COM", new BigDecimal("900"));
        assertThat(withDni.documentNumber()).isEqualTo("45678912");
        assertThat(withDni.email()).isEqualTo("ana@mail.com");

        assertThatThrownBy(() -> Receiver.validate(DocumentType.FACTURA, "DNI", "45678912", "Ana", null, null, BigDecimal.TEN))
                .isInstanceOf(BusinessException.class).hasMessageContaining("RUC");
        assertThatThrownBy(() -> Receiver.validate(DocumentType.FACTURA, "RUC", "20600000004", "Empresa", null, null, BigDecimal.TEN))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no es válido");
        Receiver company = Receiver.validate(DocumentType.FACTURA, "6", "20600000005", "Empresa sac", "Av. Lima 1", null, BigDecimal.TEN);
        assertThat(company.name()).isEqualTo("EMPRESA SAC");
        assertThat(company.documentType()).isEqualTo(IdentityDocumentType.RUC);
    }
}
