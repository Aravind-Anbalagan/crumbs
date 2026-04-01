package com.crumbs.trade.controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.broker.Samco;

@RestController
@RequestMapping("/admin/samco")
public class SamcoSetupController {

    @Autowired Samco samco;

    // One-time: Step 1
    @PostMapping("/request-otp")
    public String requestOtp() {
        samco.generateOtp();
        return "OTP sent to your registered mobile/email";
    }

    // One-time: Step 2
    @PostMapping("/request-secret-key")
    public String requestSecretKey(@RequestParam String otp) {
        samco.requestSecretKey(otp);
        return "Secret key sent to your registered email. Check inbox, then call /save-secret-key";
    }

    // One-time: Step 3
    @PostMapping("/save-secret-key")
    public String saveSecretKey(@RequestParam String key) {
        samco.saveSecretKey(key);
        return "Secret key saved to DB. Daily auth is now fully automatic.";
    }

    // One-time: Step 4
    @PostMapping("/register-ip")
    public String registerIp(@RequestParam String primaryIp,
                             @RequestParam String secondaryIp) {
        samco.registerStaticIp(primaryIp, secondaryIp);
        return "Static IPs registered successfully";
    }

    // Only if IP changes (max once per week)
    @PostMapping("/update-ip")
    public String updateIp(@RequestParam String primaryIp,
                           @RequestParam String secondaryIp) {
        samco.updateStaticIp(primaryIp, secondaryIp);
        return "Static IPs updated successfully";
    }
}