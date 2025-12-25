package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "telegram_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TelegramUser {

    @Id
    @Column(name = "chat_id")
    private Long chatId;   // Telegram unique chat ID

    @Column(name = "username")
    private String username;

    @Column(name = "active")
    private Boolean active = true;   // for unsubscribe support

}
