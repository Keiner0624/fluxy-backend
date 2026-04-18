package com.fluxyBackend.security;

import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        System.out.println("Loading UserDetails for: " + email);
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("El email no existe"));
        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPassword()).authorities(user.getRole().name()).build();
    }
}
