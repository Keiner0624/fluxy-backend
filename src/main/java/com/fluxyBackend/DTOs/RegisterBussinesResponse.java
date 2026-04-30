package com.fluxyBackend.DTOs;

public class RegisterBussinesResponse {
    public String token;
    public CompanyInfo company;
    public UserInfo user;

    public static class CompanyInfo {
        public Long id;
        public String name;
        public String slug;
        public String storeUrl;
        public String phone;
        public String whatssapp;
    }

    public static class UserInfo {
        public String fullName;
        public String email;
    }
}
