package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
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
	
	@Transactional
	public void saveNiftyResult(Indicator stock) {
		Result result = resultRepo.findByName(stock.getName());
		if (result == null) {
			result = new Result();
			result.setName(stock.getName());
			result.setToken(stock.getToken());
			result.setTradingSymbol(stock.getTradingSymbol());
			result.setExchange(stock.getExchange());
			result.setExecutedltp(stock.getPrevdaycloseprice());
			result.setCurrentltp(stock.getCurrentPrice());
			result.setType(stock.getFirst3FiveMinsCandle()); // common field indicate buy /sell
			if ("UP".equalsIgnoreCase(result.getType())) {
				result.setSl(convertStringToList(stock.getLast3daycandlelow()));

			} else {
				result.setSl(convertStringToList(stock.getLast3daycandlehigh()));
			}
			
		} else {
			result.setResult(stock.getResult());
		}
		resultRepo.save(result);
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
	
	
}
