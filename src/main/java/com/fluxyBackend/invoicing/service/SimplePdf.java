package com.fluxyBackend.invoicing.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF de texto mínimo (Helvetica, WinAnsi), sin dependencias: alcanza para la representación
 * impresa de los comprobantes de prueba. Los reales usan el PDF del proveedor.
 */
final class SimplePdf {

    static final float A4_WIDTH = 595.28f;
    static final float A4_HEIGHT = 841.89f;
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder current;

    SimplePdf() {
        newPage();
    }

    void newPage() {
        current = new StringBuilder();
        pages.add(current);
    }

    void text(float x, float y, float size, boolean bold, String text) {
        current.append("BT /").append(bold ? "F2" : "F1").append(' ').append(fmt(size)).append(" Tf 1 0 0 1 ")
                .append(fmt(x)).append(' ').append(fmt(y)).append(" Tm (").append(escape(text)).append(") Tj ET\n");
    }

    void textRight(float right, float y, float size, boolean bold, String text) {
        text(right - width(text, size), y, size, bold, text);
    }

    void color(float r, float g, float b) {
        current.append(fmt(r)).append(' ').append(fmt(g)).append(' ').append(fmt(b)).append(" rg ")
                .append(fmt(r)).append(' ').append(fmt(g)).append(' ').append(fmt(b)).append(" RG\n");
    }

    void line(float x1, float y1, float x2, float y2, float width) {
        current.append(fmt(width)).append(" w ").append(fmt(x1)).append(' ').append(fmt(y1)).append(" m ")
                .append(fmt(x2)).append(' ').append(fmt(y2)).append(" l S\n");
    }

    void rect(float x, float y, float w, float h, boolean fill) {
        current.append(fmt(x)).append(' ').append(fmt(y)).append(' ').append(fmt(w)).append(' ').append(fmt(h))
                .append(fill ? " re f\n" : " re S\n");
    }

    /** Ancho aproximado en Helvetica: exacto para números, promedio para letras. */
    static float width(String text, float size) {
        float units = 0;
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) units += 556;
            else if (c == ' ' || c == '.' || c == ',' || c == '/' || c == ':') units += 278;
            else if (c == '-') units += 333;
            else if (Character.isUpperCase(c)) units += 667;
            else units += 520;
        }
        return units * size / 1000f;
    }

    /** Parte un texto en líneas que entran en maxWidth. */
    static List<String> wrap(String text, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text).split("\\s+")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (width(next, size) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        int pageCount = pages.size();
        int firstPage = 5;
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) kids.append(firstPage + i * 2).append(" 0 R ");

        offsets.add(out.size());
        write(out, "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        offsets.add(out.size());
        write(out, "2 0 obj << /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >> endobj\n");
        offsets.add(out.size());
        write(out, "3 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >> endobj\n");
        offsets.add(out.size());
        write(out, "4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >> endobj\n");
        for (int i = 0; i < pageCount; i++) {
            int pageObj = firstPage + i * 2;
            byte[] content = pages.get(i).toString().getBytes(WIN_ANSI);
            offsets.add(out.size());
            write(out, pageObj + " 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 " + fmt(A4_WIDTH) + " " + fmt(A4_HEIGHT)
                    + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (pageObj + 1) + " 0 R >> endobj\n");
            offsets.add(out.size());
            write(out, (pageObj + 1) + " 0 obj << /Length " + content.length + " >> stream\n");
            out.writeBytes(content);
            write(out, "\nendstream endobj\n");
        }
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) table.append(String.format("%010d 00000 n \n", offset));
        write(out, table.toString());
        write(out, "trailer << /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(WIN_ANSI));
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : (text == null ? "" : text).toCharArray()) {
            if (c == '\\' || c == '(' || c == ')') out.append('\\');
            if (c == '\n' || c == '\r') c = ' ';
            out.append(WIN_ANSI.newEncoder().canEncode(c) ? c : '?');
        }
        return out.toString();
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
