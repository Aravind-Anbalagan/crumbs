package com.crumbs.trade.controller;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.ResultVixRepo;
import com.crumbs.trade.repo.VixRepo;
import com.crumbs.trade.service.ChartService;
import com.crumbs.trade.service.TaskService;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping(value = "/backtest")
public class BackTestController {
	
	private static final Logger logger = LogManager.getLogger(BackTestController.class);
	
	@Autowired
	TaskService taskService;
	
	@Autowired
	ChartService chartService;
	
	@Autowired
	VixRepo vixRepo;
	
	@Autowired
	ResultVixRepo resultVixRepo;

	@GetMapping(value = "/HeikinPsar")
	public String monitorNifty() throws SmartAPIException, Exception {
		String fromDate = "2025-08-13 15:25";
		String toDate = "2025-08-14 15:25";
		vixRepo.deleteAll();
		resultVixRepo.deleteAll();
		//List<String> times = generateTimes(fromDate, toDate);
		String result = null;
		result = chartService.readChartData("FIVE_MINUTE", "NFO", false, "NIFTY", fromDate, toDate);
		if (!"No Data Found".equalsIgnoreCase(result)) {
			for (int i = vixRepo.findAll().size() - 3; i > 0; i--) {
				
				chartService.monitorSignal("NIFTY", "NFO", true, i);
				List<Vix> vixList = vixRepo.findAllByNameContainingOrderByIdDesc("NIFTY");
				Vix vix = vixList.get(i);
				chartService.lookForExecutedOrder("NIFTY","NFO",vix,true);
			}
		}
		return result;
	}
	
    public static List<String> generateTimes(String fromDateStr, String toDateStr) {
        List<String> timeList = new ArrayList<>();

        try {
            // Define the date format
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

            // Parse the input date strings to Date objects
            Date fromDate = sdf.parse(fromDateStr);
            Date toDate = sdf.parse(toDateStr);

            // Initialize the current time as fromDate
            Date currentTime = fromDate;

            // Iterate while current time is before or equal to the toDate
            while (!currentTime.after(toDate)) {
                // Add the current time to the list in string format
                timeList.add(sdf.format(currentTime));

                // Add 5 minutes to the current time
                currentTime = new Date(currentTime.getTime() + TimeUnit.MINUTES.toMillis(5));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return timeList;
    }
}