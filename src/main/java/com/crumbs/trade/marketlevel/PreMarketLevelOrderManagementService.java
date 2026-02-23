package com.crumbs.trade.marketlevel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.PreMarketAnalysis;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.PreMarketAnalysisRepo;
import com.crumbs.trade.service.AngelWebSocketService;
import com.crumbs.trade.service.TelegramService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PreMarketLevelOrderManagementService {

	private static final Logger log = LoggerFactory.getLogger(PreMarketLevelOrderManagementService.class);

	// ============================================================
	// ================= STRATEGY CONSTANTS =======================
	// ============================================================

	private static final String STRATEGY_NAME = "Market_Level";
	private static final ExchangeType EXCHANGE = ExchangeType.NSE_FO;
	private static final boolean LIVE_TRADING = false;

	private static final BigDecimal STOP_LOSS_POINTS = BigDecimal.valueOf(10);
	private static final BigDecimal TRAIL_START_POINTS = BigDecimal.valueOf(20);
	private static final BigDecimal TRAIL_STEP = BigDecimal.valueOf(5);
	private static final BigDecimal BUFFER_PERCENT = BigDecimal.valueOf(0.002);

	private static final int COOLDOWN_MINUTES = 3;
	private static final int QUANTITY = 50;

	// ============================================================
	// ================= DATE FORMAT ===============================
	// ============================================================

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");

	// ============================================================
	// ================= TELEGRAM TEMPLATES =======================
	// ============================================================

	private static final String ENTRY_TEMPLATE = """
			🟢 ENTRY
			Signal : %s
			Entry  : %.2f
			SL     : %.2f
			""";

	private static final String EXIT_PROFIT_TEMPLATE = """
			🎯 EXIT (TRAIL TARGET)
			Exit   : %.2f
			PnL    : %.2f
			""";

	private static final String EXIT_LOSS_TEMPLATE = """
			❌ EXIT (SL HIT)
			Exit   : %.2f
			PnL    : %.2f
			""";

	private static final String TELEGRAM_HEADER = """
			📊 %s | %s

			%s
			🕒 %s
			""";

	// ============================================================
	// ================= DEPENDENCIES =============================
	// ============================================================

	private final AngelWebSocketService webSocketService;
	private final AngelOne angelOne;
	private final PreMarketAnalysisRepo preMarketRepo;
	private final OrderRepository ordersRepo;
	private final TelegramService telegramService;

	// ============================================================
	// ================= RUNTIME STATE ============================
	// ============================================================

	private TradeState state = TradeState.IDLE;
	private TradeDirection direction;
	private BigDecimal entryPrice;
	private BigDecimal currentSL;
	private String slOrderId;
	private LocalDateTime cooldownUntil;

	private final Set<String> subscribedTokens = new HashSet<>();

	// ============================================================
	// ================= MAIN LOOP ================================
	// ============================================================

	public void runCycle(String instrumentName) {

		Optional<PreMarketAnalysis> optional = preMarketRepo.findByNameAndTradingDate(instrumentName, LocalDate.now());

		if (optional.isEmpty())
			return;

		PreMarketAnalysis data = optional.get();

		ensureSubscribed(data.getCeToken());
		ensureSubscribed(data.getPeToken());

		BigDecimal ce = getLivePrice(data.getCeToken());
		BigDecimal pe = getLivePrice(data.getPeToken());

		if (ce == null || pe == null)
			return;

		switch (state) {

		case IDLE -> checkEntry(data, ce, pe);

		case ACTIVE -> manageTrade(data, ce, pe);

		case COOLDOWN -> {
			if (LocalDateTime.now().isAfter(cooldownUntil)) {
				state = TradeState.IDLE;
			}
		}
		}
	}

	// ============================================================
	// ================= ENTRY LOGIC ==============================
	// ============================================================

	private void checkEntry(PreMarketAnalysis data, BigDecimal ce, BigDecimal pe) {

		// Prevent duplicate active trade for this strategy
		if (ordersRepo.findTopByNameAndActiveOrderByIdDesc(STRATEGY_NAME, 1).isPresent())
			return;

		BigDecimal mid = data.getMidPoint();

		BigDecimal upper = mid.multiply(BigDecimal.ONE.add(BUFFER_PERCENT));
		BigDecimal lower = mid.multiply(BigDecimal.ONE.subtract(BUFFER_PERCENT));

		TradeDirection signal = null;

		if (ce.compareTo(upper) > 0 && pe.compareTo(lower) < 0)
			signal = TradeDirection.BUY_CE;

		if (pe.compareTo(upper) > 0 && ce.compareTo(lower) < 0)
			signal = TradeDirection.BUY_PE;

		if (signal == null)
			return;

		direction = signal;
		entryPrice = (direction == TradeDirection.BUY_CE) ? ce : pe;

		BigDecimal slPrice = entryPrice.subtract(STOP_LOSS_POINTS);

		try {

			String token = getToken(data);

			if (LIVE_TRADING) {
				/*
				 * angelOne.placeMarketOrder(EXCHANGE.name(), token, "BUY"); slOrderId =
				 * angelOne.placeSLMarketOrder( EXCHANGE.name(), token, slPrice, "SELL");
				 */
			} else {
				slOrderId = "TEST-" + System.currentTimeMillis();
			}

			currentSL = slPrice;
			state = TradeState.ACTIVE;

			saveEntryOrder(data, token);

			send(data.getName(), String.format(ENTRY_TEMPLATE, direction, entryPrice, currentSL));

		} catch (Exception e) {
			log.error("Entry failed", e);
		}
	}

	private void saveEntryOrder(PreMarketAnalysis data, String token) {

		Orders order = new Orders();

		order.setOrderid(slOrderId);
		order.setCreatedOn(LocalDateTime.now().toString());
		order.setName(STRATEGY_NAME);
		order.setSymbol(data.getName());
		order.setToken(token);
		order.setAskPrice(entryPrice.intValue());
		order.setSl(currentSL.intValue());
		order.setActive(1);
		order.setBreakeven(0);
		order.setSignal(direction.name());
		order.setExchange(EXCHANGE.name());
		order.setQuantity(QUANTITY);
		order.setType("ENTRY");

		ordersRepo.save(order);
	}

	// ============================================================
	// ================= TRADE MANAGEMENT =========================
	// ============================================================

	private void manageTrade(PreMarketAnalysis data, BigDecimal ce, BigDecimal pe) {

		BigDecimal currentPrice = (direction == TradeDirection.BUY_CE) ? ce : pe;

		BigDecimal profit = currentPrice.subtract(entryPrice);

		// ===== SL HIT =====
		if (currentPrice.compareTo(currentSL) <= 0) {

			closeOrder(currentPrice);

			boolean profitExit = currentSL.compareTo(entryPrice) >= 0;

			String message = profitExit ? String.format(EXIT_PROFIT_TEMPLATE, currentPrice, profit)
					: String.format(EXIT_LOSS_TEMPLATE, currentPrice, profit);

			send(data.getName(), message);

			state = TradeState.COOLDOWN;
			cooldownUntil = LocalDateTime.now().plusMinutes(COOLDOWN_MINUTES);
			return;
		}

		// ===== START TRAILING AFTER +20 =====
		if (profit.compareTo(TRAIL_START_POINTS) >= 0) {

			BigDecimal newSL;

			if (currentSL.compareTo(entryPrice) < 0) {
				newSL = entryPrice; // move to break-even first
			} else {
				newSL = currentPrice.subtract(TRAIL_STEP);
			}

			if (newSL.compareTo(currentSL) > 0) {

				if (LIVE_TRADING)
					// angelOne.modifyOrder(slOrderId, newSL);

					currentSL = newSL;
				updateSLInDb(newSL);
			}
		}
	}

	private void updateSLInDb(BigDecimal newSL) {

		Optional<Orders> optional = ordersRepo.findTopByNameAndActiveOrderByIdDesc(STRATEGY_NAME, 1);

		if (optional.isPresent()) {
			Orders order = optional.get();
			order.setSl(newSL.intValue());
			ordersRepo.save(order);
		}
	}

	private void closeOrder(BigDecimal exitPrice) {

		Optional<Orders> optional = ordersRepo.findTopByNameAndActiveOrderByIdDesc(STRATEGY_NAME, 1);

		if (optional.isPresent()) {

			Orders order = optional.get();

			int exit = exitPrice.intValue();
			int pnl = (exit - order.getAskPrice()) * order.getQuantity();

			order.setExitPrice(exit);
			order.setPl(pnl);
			order.setActive(0);
			order.setType("EXIT");

			ordersRepo.save(order);
		}
	}

	// ============================================================
	// ================= HELPERS ==================================
	// ============================================================

	private String getToken(PreMarketAnalysis data) {
		return (direction == TradeDirection.BUY_CE) ? data.getCeToken() : data.getPeToken();
	}

	private BigDecimal getLivePrice(String token) {
		BigDecimal price = webSocketService.getLatestLTP(EXCHANGE, token);

		return (price == null) ? null : price.setScale(2, RoundingMode.HALF_UP);
	}

	private void ensureSubscribed(String token) {
		if (!subscribedTokens.contains(token)) {
			webSocketService.subscribe(EXCHANGE, token);
			subscribedTokens.add(token);
		}
	}

	private void send(String instrument, String body) {

		try {

			String formattedTime = LocalDateTime.now().format(DATE_FORMAT);

			String message = String.format(TELEGRAM_HEADER, STRATEGY_NAME, instrument, body, formattedTime);

			telegramService.sendMessage(message);

		} catch (Exception e) {
			log.error("Telegram failed", e);
		}
	}
}