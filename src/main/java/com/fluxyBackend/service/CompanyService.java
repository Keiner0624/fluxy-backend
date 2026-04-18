package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyService {
    private final CompanyRepository companyRespository;

    public Company createCompany(Company company){
        return companyRespository.save(company);
    }
    public List<Company> getAllCompanies(){
        return companyRespository.findAll();
    }

    public Company getById(Long id){
        return companyRespository.findById(id).orElseThrow(() -> new RuntimeException("Empresa no encontrada"));
    }

}
