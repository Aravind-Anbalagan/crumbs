package com.crumbs.trade.controller;

import java.util.List;
import com.crumbs.trade.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.VixRepo;

@RestController
@RequestMapping("/heikinpsar")
public class HeikinPsarController {

    @Autowired
    private HeikinPsarExecutionService executionService;

    @Autowired
    private VixRepo vixRepo;

    // ---------------- HTTP ENDPOINTS ONLY ----------------

    @GetMapping("/getCandleList")
    public List<Vix> getCandleData() {
        return vixRepo.findByName("CRUDEOIL");
    }

    // Optional: manual trigger (useful for testing)
    @GetMapping("/run/nifty")
    public String runNifty() throws Exception {
        executionService.commonExecutionNifty();
        return "NIFTY executed";
    }

    @GetMapping("/run/silverm")
    public String runSilverm() throws Exception {
        executionService.commonExecutionMcx();
        return "SILVERM executed";
    }
}
