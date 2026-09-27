package com.fluxyBackend.marketing.dto;

/**
 * accepted: la campaña existe, es de esta tienda y está vigente. recorded: se guardó un evento
 * nuevo (false si ya se había contado). En VIEW, target dice qué abrir y couponCode qué cupón
 * sugerir en el checkout.
 */
public record TrackEventResponse(boolean accepted, boolean recorded, Target target, String couponCode,
                                 int attributionDays) {

    public record Target(String type, Long id) {}

    public static TrackEventResponse rejected(int attributionDays) {
        return new TrackEventResponse(false, false, null, null, attributionDays);
    }
}
