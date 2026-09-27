package com.fluxyBackend.marketing.enums;

/** Qué se promociona. Define a qué apunta targetId y a dónde lleva el enlace. */
public enum CampaignType {
    /** La tienda completa; sin targetId. */
    STORE,
    /** Un producto; targetId es el id del producto. */
    PRODUCT,
    /** Una categoría; targetId es el id de la categoría. */
    CATEGORY,
    /** Un cupón; targetId es el id del cupón (también queda en couponId). */
    COUPON
}
