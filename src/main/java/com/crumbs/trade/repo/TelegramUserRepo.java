package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.crumbs.trade.entity.TelegramUser;

import java.util.List;

public interface TelegramUserRepo extends JpaRepository<TelegramUser, Long> {

    List<TelegramUser> findByActiveTrue();
}
