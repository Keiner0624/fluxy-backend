package com.fluxyBackend.DTOs;

public class RegisterBussinesResponse {
    public String token;
    public CompanyInfo company;
    public UserInfo user;
    public java.util.Map<String, Object> onboarding = java.util.Map.of(
            "completed", false, "status", "NOT_STARTED", "nextStep", "BUSINESS_PROFILE");

    public static class CompanyInfo {
        public Long id;
        public String name;
        public String tradeName;
        public String role;
        public String slug;
        public String storeUrl;
        public String phone;
        public String whatssapp;
    }

    public static class UserInfo {
        public Long id;
        public String fullName;
        public String email;
    }
}
