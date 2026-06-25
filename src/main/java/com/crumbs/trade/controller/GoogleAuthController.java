package com.crumbs.trade.controller;

import com.crumbs.trade.entity.User;
import com.crumbs.trade.repo.UserRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    // Initialize the Log4j2 Logger for this controller
    private static final Logger logger = LogManager.getLogger(GoogleAuthController.class);

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/google")
    public ResponseEntity<?> handleGoogleLogin(@RequestBody Map<String, String> payload) {
        logger.info("=== [START] GOOGLE LOGIN ATTEMPT ===");
        
        String accessToken = payload.get("token");
        
        // Log the presence of the token
        logger.info("STEP 1: Token received from React? {}", 
            (accessToken != null && !accessToken.isEmpty() ? "YES" : "NO (Token is missing!)"));

        try {
            RestTemplate restTemplate = new RestTemplate();
            String googleUrl = "https://www.googleapis.com/oauth2/v3/userinfo?access_token=" + accessToken;
            
            logger.info("STEP 2: Calling Google API to verify token...");
            
            // Google responds with the user's profile data
            Map<String, Object> googleProfile = restTemplate.getForObject(googleUrl, Map.class);
            
            logger.info("STEP 3: Google Response Received! Profile Data: {}", googleProfile);

            if (googleProfile == null) {
                throw new Exception("Google API returned null response");
            }

            String email = (String) googleProfile.get("email");
            String name = (String) googleProfile.get("name");
            
            logger.info("STEP 4: Extracted Email: [{}], Name: [{}]", email, name);
            
            if (email == null) {
                logger.warn("🚨 ERROR: Google did not return an email address.");
                throw new Exception("No email found in Google profile");
            }

            // Database coupling: Find user, if not, save new
            logger.info("STEP 5: Attempting to query PostgreSQL database for email: {}", email);
            Optional<User> existingUser = userRepository.findByEmail(email);
            
            logger.info("STEP 6: Database query result. User exists? {}", 
                (existingUser.isPresent() ? "YES" : "NO"));
            
            if (existingUser.isEmpty()) {
                logger.info("STEP 7: Creating new user in database...");
                User newUser = new User(email, name, "GOOGLE");
                userRepository.save(newUser);
                logger.info("🎉 Successfully stored NEW Google user in DB: {}", email);
            } else {
                logger.info("👋 Welcome back existing Google user: {}", email);
            }

            Map<String, String> response = new HashMap<>();
            response.put("message", "Welcome " + name + "! Backend verification successful.");
            response.put("email", email);
            
            logger.info("=== [END] GOOGLE LOGIN SUCCESS ===");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            // Detailed error logging
            logger.error("🚨 === [CRITICAL ERROR DURING GOOGLE LOGIN] === 🚨");
            logger.error("Exception Type: {}", e.getClass().getName());
            logger.error("Error Message: {}", e.getMessage());
            logger.error("Full Stack Trace: ", e);
            
            return ResponseEntity.status(401).body("Invalid Google Token or Server Error");
        }
    }
}