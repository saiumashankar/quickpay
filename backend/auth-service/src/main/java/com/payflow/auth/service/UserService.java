package com.payflow.auth.service;

import com.payflow.auth.dto.RegisterRequest;
import com.payflow.auth.dto.UserResponse;
import com.payflow.auth.entity.Role;
import com.payflow.auth.entity.User;
import com.payflow.auth.exception.ApiException;
import com.payflow.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final HandleService handleService;

    @Transactional
    public User register(RegisterRequest request) {
        String email = request.getEmail().trim();
        // Normalised here rather than trusting the input: the handle is a
        // payment address, so "Sai" and "sai" must not become two identities
        // that can both hold a balance.
        String username = handleService.normalizeOrThrow(request.getUsername());

        if (userRepository.existsByEmail(email)) {
            throw new ApiException("Email already registered");
        }

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ApiException("Handle already taken");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .enabled(true)
                .build();

        return userRepository.save(user);
    }

    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Transactional
    public User setNotificationEnabled(User user, Boolean emailEnabled) {
        user.setNotificationPreferencesEnabled(emailEnabled != null && emailEnabled);
        return userRepository.save(user);
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getOwnerId(),
                user.getDisplayUsername(),
                user.getEmail(),
                user.getRole().name(),
                user.isEnabled() ? "ACTIVE" : "INACTIVE",
                user.getCreatedAt()
        );
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}

