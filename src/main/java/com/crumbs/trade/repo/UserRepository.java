package com.crumbs.trade.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crumbs.trade.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
