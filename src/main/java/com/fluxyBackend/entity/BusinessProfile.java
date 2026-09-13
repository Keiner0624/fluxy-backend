package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.LinkedHashSet;
import java.util.Set;

/** Private business details, never serialized by public storefront endpoints. */
@Entity
@Getter
@Setter
public class BusinessProfile {
    public enum BusinessType { PHYSICAL, ONLINE, HYBRID }
    public enum EmployeeRange { SOLO, TWO_TO_5, SIX_TO_20, MORE_THAN_20 }
    public enum MonthlyOrdersRange { ZERO_TO_50, FIFTY_ONE_TO_200, TWO_HUNDRED_ONE_TO_1000, MORE_THAN_1000 }
    public enum SalesChannel { WHATSAPP, INSTAGRAM, FACEBOOK, PHYSICAL_STORE, WEBSITE, MARKETPLACE, OTHER }
    public enum Goal { ORDERS, INVENTORY, SALES, ONLINE_STORE, CUSTOMERS, AUTOMATION }
    public enum Status { NOT_STARTED, IN_PROGRESS, COMPLETED, SKIPPED }
    public enum Step { BUSINESS_PROFILE, SALES_CHANNELS, GOALS, FINISHED }

    @Id
    private Long companyId;
    @Enumerated(EnumType.STRING)
    private BusinessCategory category;
    private String taxId;
    private String legalName;
    @Enumerated(EnumType.STRING)
    private BusinessType businessType;
    @Enumerated(EnumType.STRING)
    private EmployeeRange employeeRange;
    @Enumerated(EnumType.STRING)
    private MonthlyOrdersRange monthlyOrdersRange;
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    private Set<SalesChannel> salesChannels = new LinkedHashSet<>();
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    private Set<Goal> goals = new LinkedHashSet<>();
    private String department;
    private String province;
    private String district;
    private String taxAddress;
    @Column(length = 500)
    private String openingHours;
    private String instagram;
    private String facebook;
    private String website;
    @Column(length = 500)
    private String deliveryMethods;
    @Enumerated(EnumType.STRING)
    private Status onboardingStatus = Status.NOT_STARTED;
    @Enumerated(EnumType.STRING)
    private Step onboardingStep = Step.BUSINESS_PROFILE;
}
