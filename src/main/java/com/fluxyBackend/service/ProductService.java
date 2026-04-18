package com.fluxyBackend.service;

import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository prodcutRepository;
    private final UserRepository userRepository;
    private User getUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow(() -> new RuntimeException("User not found"));
    }
    public Prodcut createProduct(Prodcut product, String email) {
        User user = getUserByEmail(email);
        product.setCompany(user.getCompany());
        product.setOwner(user);
        return prodcutRepository.save(product);
    }
    public List<Prodcut> getAll(String email) {
        User user = getUserByEmail(email);
        return prodcutRepository.findByCompany(user.getCompany());
    }
    public Prodcut update(Long id, Prodcut update, String email) {
        User user = getUserByEmail(email);
        Prodcut prodcut = prodcutRepository.findByIdAndCompany(id,user.getCompany())
                .orElseThrow(() -> new RuntimeException("Prodcuts not found"));
        prodcut.setName(update.getName());
        prodcut.setPrice(update.getPrice());
        prodcut.setStock(update.getStock());
        return prodcutRepository.save(prodcut);
    }
    public void delete(Long id, String email) {
        User user = getUserByEmail(email);
        Prodcut prodcut = prodcutRepository.findByIdAndCompany(id, user.getCompany()).
                orElseThrow(() -> new RuntimeException("Prodcuts not found"));
        prodcutRepository.delete(prodcut);
    }

    public int conuntProducts(String email){
        User user = getUserByEmail(email);
        return prodcutRepository.findByCompany(user.getCompany()).size();
    }
}
