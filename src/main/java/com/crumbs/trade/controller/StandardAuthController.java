package com.crumbs.trade.controller;

import com.crumbs.trade.entity.User;
import com.crumbs.trade.repo.UserRepository;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class StandardAuthController {

    @Autowired
    private UserRepository userRepository;

    // 1. STANDARD REGISTRATION (SIGN UP)
    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@RequestBody Map<String, String> payload) {
        String email = payload.get("identifier"); // React sends email/phone inside 'identifier'
        String rawPassword = payload.get("password");
        String name = payload.getOrDefault("name", "Standard User");

        // Verify that the email isn't already registered
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "This email is already registered!"));
        }

        // Hash the password securely so it isn't plain text in PostgreSQL
        String hashedPassword = BCrypt.hashpw(rawPassword, BCrypt.gensalt());

        // Save the new user record
        User newUser = new User(email, name, "STANDARD");
        newUser.setPassword(hashedPassword);
        userRepository.save(newUser);

        System.out.println("🎉 Successfully stored NEW standard user in DB: " + email);
        return ResponseEntity.ok(Map.of("message", "Registration successful! You can now sign in."));
    }

    // 2. STANDARD AUTHENTICATION (SIGN IN)
    @PostMapping("/signin")
    public ResponseEntity<?> loginUser(@RequestBody Map<String, String> payload) {
        String email = payload.get("identifier");
        String rawPassword = payload.get("password");

        Optional<User> userOptional = userRepository.findByEmail(email);

        // Check if user exists
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid email or password."));
        }

        User user = userOptional.get();

        // Safety catch: Block standard login attempts if they signed up through Google
        if (user.getPassword() == null || "GOOGLE".equals(user.getAuthProvider())) {
            return ResponseEntity.status(401).body(Map.of("message", "Account created via Google. Please use Google Sign-In."));
        }

        // Compare the raw password against the hashed database value
        if (BCrypt.checkpw(rawPassword, user.getPassword())) {
            System.out.println("👋 Standard user successfully logged in: " + email);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Welcome back! Backend verification successful.");
            response.put("email", user.getEmail());
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid email or password."));
        }
    }
}