package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.PSARMcx;
import com.crumbs.trade.entity.PSARNifty;
import com.crumbs.trade.entity.PricesHeikinAshiMcx;
import com.crumbs.trade.entity.PricesHeikinAshiNifty;
import com.crumbs.trade.entity.Result;
import com.crumbs.trade.entity.ResultMcx;
import com.crumbs.trade.entity.ResultNifty;
import com.crumbs.trade.repo.IndicatorRepo;
import com.crumbs.trade.repo.ResultMcxRepo;
import com.crumbs.trade.repo.ResultNiftyRepo;
import com.crumbs.trade.repo.ResultRepo;

import jakarta.transaction.Transactional;



@Service
public class ResultService {

	@Autowired
	ResultNiftyRepo resultNiftyRepo;
	
	@Autowired
	ResultMcxRepo resultMcxRepo;
	
	@Autowired
	ResultRepo resultRepo;
	
	@Autowired
	SRService srService;
	
	@Autowired
	IndicatorRepo indicatorRepo;
	
	@Transactional
	public void saveNiftyResult(Indicator stock) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		sdf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
		String dateTimeIST = sdf.format(new Date());
		String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
		Result result = new Result();
		Optional<Result> optionalResult = resultRepo.findTopByNameAndMonth(stock.getName(), currentMonth);
		if (optionalResult.isPresent()) {
			result = optionalResult.get();
			result.setResult(stock.getResult());
			resultRepo.save(result);
		} else {
			// Make New Entry
			result = new Result();
			result.setName(stock.getName());
			result.setEntryTime(dateTimeIST);
			result.setToken(stock.getToken());
			result.setTradingSymbol(stock.getTradingSymbol());
			result.setExchange(stock.getExchange());
			result.setExecutedltp(stock.getPrevdaycloseprice());
			result.setCurrentltp(stock.getCurrentPrice());
			result.setType(stock.getIntraday()); // common field indicate buy /sell
			result.setHasOption(Objects.nonNull(stock.getOptions()) ? "Y" : "N");
			/*if ("UP".equalsIgnoreCase(result.getType()) && "DAILY".equalsIgnoreCase(stock.getTradetype())) {
				result.setSl(convertStringToList(stock.getLast3daycandlelow()));

			} else if ("DOWN".equalsIgnoreCase(result.getType()) && "DAILY".equalsIgnoreCase(stock.getTradetype())) {
				result.setSl(convertStringToList(stock.getLast3daycandlehigh()));
			}*/
			result.setSl(stock.getSl());
			resultRepo.save(result);
		}

	}
	
	public BigDecimal convertStringToList(String input) {
		// Input string

		List<Integer> numberList1 = Arrays.stream(input.replaceAll("\\[|\\]", "").split(",")).map(String::trim)
				.map(Integer::parseInt).collect(Collectors.toList());

		return new BigDecimal(numberList1.get(0));
	}
	
	public boolean checkExitTime(String exchange) {
		// Define the target time (3:20 PM)
		LocalTime targetTime = LocalTime.of(15, 20);

		// Get the current time
		LocalTime currentTime = LocalTime.now();

		// Compare the current time with the target time
		if (currentTime.isAfter(targetTime)) {
			System.out.println("The current time is after 3:20 PM.");
			return true;
		} else {
			System.out.println("The current time is before or exactly 3:20 PM.");
			return false;
			
		}
	}
	@Transactional
	public void savePsarHeikinAchiStrategyNifty(PricesHeikinAshiNifty pricesHeikinAshiNifty, PSARNifty pSARNifty, BigDecimal currentPrice)
	{
		
		String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
		ResultNifty result = resultNiftyRepo.findByActiveAndName("Y","NIFTY");
		
		
		
		//Write entry
		if(result==null && !checkExitTime(null))
		{
			
			writeEntryNifty(result, pricesHeikinAshiNifty, currentDate,currentPrice);
			
		}
		else
		{
			//Write exit
			String activeType = result.getType();
			if(!activeType.equalsIgnoreCase(pricesHeikinAshiNifty.getType()) || checkExitTime(null))
			{
				result.setActive("N");
				result.setExitTime(currentDate);
				result.setExitPrice(currentPrice);
				if ("BUY".equalsIgnoreCase(result.getType())) {
					result.setPoints(result.getExitPrice().subtract(result.getEntryPrice()).intValue());
				} else {
					result.setPoints(result.getEntryPrice().subtract(result.getExitPrice()).intValue());
				}
				result.setLotSize(75);
			
				resultNiftyRepo.save(result);
				//sendEmail.sendmail("HEIHINACHI & PSAR " + " : " +" BUY", "BUY",0);
				
				if(!checkExitTime(null))
				{
					// Make an entry
					//Avoid new entry as soon as close the previous order
					//writeEntryNifty(result, pricesHeikinAshiNifty, currentDate,currentPrice);
				}
				
			}
			
		}
		
	}

	public void writeEntryNifty(ResultNifty result, PricesHeikinAshiNifty pricesHeikinAshiNifty, String currentDate, BigDecimal currentPrice) {
		result = new ResultNifty();
		result.setName(pricesHeikinAshiNifty.getName());
		result.setEntryPrice(currentPrice);
		result.setType(pricesHeikinAshiNifty.getType());
		result.setTimestamp(pricesHeikinAshiNifty.getTimestamp());
		result.setActive("Y");
		result.setEntryTime(currentDate);
		resultNiftyRepo.save(result);
		// sendEmail.sendmail("HEIHINACHI & PSAR " + " : " +" BUY", "BUY",0);
	}
	@Transactional
	public void savePsarHeikinAchiStrategyMcx(PricesHeikinAshiMcx pricesHeikinAshiMcx, PSARMcx pSARMcx, BigDecimal currentPrice)
	{
		String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
		ResultMcx result = resultMcxRepo.findByActiveAndName("Y","MCX");
		
		//Write entry
		if(result==null)
		{
			
			writeEntryMcx(result,pricesHeikinAshiMcx,currentDate,currentPrice);
			
		}
		else
		{
			// Write exit
			String activeType = result.getType();
			if (!activeType.equalsIgnoreCase(pricesHeikinAshiMcx.getType())) {
				result.setActive("N");
				result.setExitTime(currentDate);
				result.setExitPrice(currentPrice);
				if ("BUY".equalsIgnoreCase(result.getType())) {
					result.setPoints(result.getExitPrice().subtract(result.getEntryPrice()).intValue());
				} else {
					result.setPoints(result.getEntryPrice().subtract(result.getExitPrice()).intValue());
				}
				result.setLotSize(100);
				resultMcxRepo.save(result);
				// sendEmail.sendmail("HEIHINACHI & PSAR " + " : " +" BUY", "BUY",0);

				// Make an entry
				writeEntryMcx(result, pricesHeikinAshiMcx, currentDate,currentPrice);
			}
			
		}		
	}
	
	public void writeEntryMcx(ResultMcx result, PricesHeikinAshiMcx pricesHeikinAshiMcx, String currentDate, BigDecimal currentPrice) {
		result = new ResultMcx();
		result.setName(pricesHeikinAshiMcx.getName());
		result.setEntryPrice(currentPrice);
		result.setType(pricesHeikinAshiMcx.getType());
		result.setTimestamp(pricesHeikinAshiMcx.getTimestamp());
		result.setActive("Y");
		result.setEntryTime(currentDate);
		resultMcxRepo.save(result);
		// sendEmail.sendmail("HEIHINACHI & PSAR " + " : " +" BUY", "BUY",0);
	}
	
	public List<Result> getAllResults() {
	    List<Result> resultList = resultRepo.findAll();
	    List<Result> updatedResults = new ArrayList<>();

	    for (Result result : resultList) {
	        BigDecimal executedLtp = result.getExecutedltp();
	        BigDecimal currentPrice = srService.getCurrentPriceForIndex(result.getName(), result.getTradingSymbol());

	        if (currentPrice == null || executedLtp == null || executedLtp.compareTo(BigDecimal.ZERO) == 0) {
	            continue; // skip invalid data
	        }

	        result.setCurrentltp(currentPrice);

	        // Calculate return %
	        BigDecimal returnPercent = BigDecimal.ZERO;
	        if ("UP".equalsIgnoreCase(result.getType())) {
	            returnPercent = currentPrice.subtract(executedLtp)
	                    .divide(executedLtp, 4, RoundingMode.HALF_UP)
	                    .multiply(BigDecimal.valueOf(100));
	        } else if ("DOWN".equalsIgnoreCase(result.getType())) {
	            returnPercent = executedLtp.subtract(currentPrice)
	                    .divide(executedLtp, 4, RoundingMode.HALF_UP)
	                    .multiply(BigDecimal.valueOf(100));
	        }

	        result.setComment("Return: " + returnPercent.setScale(2, RoundingMode.HALF_UP) + "%");

	     // Check Stop Loss (trend reversal based)
	   
				Indicator indicator = indicatorRepo.findByname(result.getName());
				
				if (indicator != null) {
				    boolean slHit = false;
				    String entryType = result.getType();      // e.g., BUY or SELL
				    String currentTrend = indicator.getSl();  // e.g., BUY, SELL, or SIDEWAYS

				    if ("UP".equalsIgnoreCase(entryType)) {
				        // SL hits when current trend turns SELL
				        slHit = "DOWN".equalsIgnoreCase(currentTrend);
				    } else if ("DOWN".equalsIgnoreCase(entryType)) {
				        // SL hits when current trend turns BUY
				        slHit = "UP".equalsIgnoreCase(currentTrend);
				    }
				    result.setSl(currentTrend);
				    result.setStatus(slHit ? "SL HIT" : "ACTIVE");
				} else {
				    result.setStatus("ERROR");
				}
	          
	        

	        updatedResults.add(result);
	    }

	    if (!updatedResults.isEmpty()) {
	        resultRepo.saveAll(updatedResults); // batch update
	    }

	    return updatedResults;
	}



	public boolean updateActive(Long id, String active) {
        int updatedRows = resultRepo.updateActiveById(id, active);
        return updatedRows > 0;
    }
	
}
