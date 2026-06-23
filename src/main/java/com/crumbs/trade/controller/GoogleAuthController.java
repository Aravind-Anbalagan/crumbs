package com.crumbs.trade.controller;

import com.crumbs.trade.entity.User; // Make sure this matches your User entity package path!
import com.crumbs.trade.repo.UserRepository; // Make sure this matches your Repository path!
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class GoogleAuthController {

    // Inject the database repository waiter
    @Autowired
    private UserRepository userRepository;

    @PostMapping("/google")
    public ResponseEntity<?> handleGoogleLogin(@RequestBody Map<String, String> payload) {
        String accessToken = payload.get("token");

        try {
            // 1. Send the token to Google's official user info endpoint
            RestTemplate restTemplate = new RestTemplate();
            String googleUrl = "https://www.googleapis.com/oauth2/v3/userinfo?access_token=" + accessToken;
            
            // 2. Google responds with the user's profile data (as a Map)
            Map<String, Object> googleProfile = restTemplate.getForObject(googleUrl, Map.class);

            // 3. Extract user properties
            String email = (String) googleProfile.get("email");
            String name = (String) googleProfile.get("name");

            // 4. DATABASE COUPLING: Check if user exists, if not, write a new row
            Optional<User> existingUser = userRepository.findByEmail(email);
            
            if (existingUser.isEmpty()) {
                // If the email isn't in our PostgreSQL database, create it!
                User newUser = new User(email, name, "GOOGLE");
                userRepository.save(newUser);
                System.out.println("🎉 Successfully stored NEW Google user in DB: " + email);
            } else {
                System.out.println("👋 Welcome back existing Google user: " + email);
            }

            // 5. Send a success message back to React
            Map<String, String> response = new HashMap<>();
            response.put("message", "Welcome " + name + "! Backend verification successful.");
            response.put("email", email);
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(401).body("Invalid Google Token");
        }
    }
}