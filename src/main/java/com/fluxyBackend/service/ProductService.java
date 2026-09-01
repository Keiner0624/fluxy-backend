package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.exception.ProductLimitException;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.CategoryRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository prodcutRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    private User getUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private static final Map<Company.Plan, Integer> PLAN_LIMITS = Map.of(
            Company.Plan.FREE, 10,
            Company.Plan.PRO, 100,
            Company.Plan.BUSINESS, 999999
    );

    @Transactional
    public Prodcut createProduct(Prodcut product, String email) {
        User user = getUserByEmail(email);
        product.setCompany(user.getCompany());
        product.setOwner(user);
        assignCategory(product, product.getCategory(), user.getCompany());

        Company company = user.getCompany();
        Company.Plan plan = (company.getPlan() != null) ? company.getPlan() : Company.Plan.FREE;
        int limit = PLAN_LIMITS.get(plan);
        int current = prodcutRepository.countByCompany(company);

        if (current >= limit) {
            throw new ProductLimitException(limit, current, plan.name());
        }
        return prodcutRepository.save(product);
    }

    public List<Prodcut> getAll(String email) {
        User user = getUserByEmail(email);
        return prodcutRepository.findByCompany(user.getCompany());
    }

    @Transactional
    public Prodcut update(Long id, Prodcut update, String email) {
        User user = getUserByEmail(email);
        Prodcut prodcut = prodcutRepository.findByIdAndCompany(id, user.getCompany())
                .orElseThrow(() -> new RuntimeException("Prodcuts not found"));

        prodcut.setName(update.getName());
        prodcut.setPrice(update.getPrice());
        prodcut.setStock(update.getStock());

        // ✅ Fix: guardar description, imageUrl e images
        if (update.getDescription() != null)
            prodcut.setDescription(update.getDescription());
        if (update.getImageUrl() != null)
            prodcut.setImageUrl(update.getImageUrl());
        if (update.getImages() != null)
            prodcut.setImages(update.getImages());
        if (update.getCategory() != null)
            assignCategory(prodcut, update.getCategory(), user.getCompany());

        return prodcutRepository.save(prodcut);
    }

    public void delete(Long id, String email) {
        User user = getUserByEmail(email);
        Prodcut prodcut = prodcutRepository.findByIdAndCompany(id, user.getCompany())
                .orElseThrow(() -> new RuntimeException("Prodcuts not found"));
        prodcutRepository.delete(prodcut);
    }

    public int conuntProducts(String email) {
        User user = getUserByEmail(email);
        return prodcutRepository.countByCompany(user.getCompany());
    }

    private void assignCategory(Prodcut product, com.fluxyBackend.entity.Category requested,
                                Company company) {
        if (requested == null || requested.getId() == null) {
            product.setCategory(null);
            return;
        }
        product.setCategory(categoryRepository.findByIdAndCompany(requested.getId(), company)
                .orElseThrow(() -> new RuntimeException("Categoría no encontrada")));
    }
}
