package com.fluxyBackend.DTOs;

import com.fluxyBackend.entity.BusinessCategory;
import com.fluxyBackend.entity.BusinessProfile.*;
import jakarta.validation.constraints.*;
import java.util.Set;

public record BusinessProfileRequest(
        @NotNull Action action,
        BusinessCategory category,
        @Pattern(regexp = "^([0-9]{8}|[0-9]{11})?$") String taxId,
        @Size(max = 160) String legalName,
        BusinessType businessType, EmployeeRange employeeRange, MonthlyOrdersRange monthlyOrdersRange,
        @Size(max = 7) Set<@NotNull SalesChannel> salesChannels,
        @Size(max = 6) Set<@NotNull Goal> goals,
        @Size(max = 100) String department, @Size(max = 100) String province,
        @Size(max = 100) String district, @Size(max = 255) String taxAddress,
        @Size(max = 500) String openingHours,
        @Size(max = 255) String instagram, @Size(max = 255) String facebook,
        @Size(max = 255) @Pattern(regexp = "^(https?://[^\\s]+)?$") String website,
        @Size(max = 500) String deliveryMethods) {
    public enum Action { SAVE_PROFILE, SAVE_CHANNELS, COMPLETE, SKIP, SAVE_DETAILS }
}
