package com.crumbs.trade.service;

import java.net.InetAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.springframework.stereotype.Service;

@Service
public class TimeLookup {
	

	public static String getCurTime() throws Exception {
		String TIME_SERVER = "time-a.nist.gov";
		NTPUDPClient timeClient = new NTPUDPClient();
		InetAddress inetAddress = InetAddress.getByName(TIME_SERVER);
		TimeInfo timeInfo = timeClient.getTime(inetAddress);
		long returnTime = timeInfo.getReturnTime();
		Date time = new Date(returnTime);
		System.out.println("Time from " + TIME_SERVER + ": " + time);
		return time.toString();
	}
	
	public boolean getTime() throws ParseException {
		Calendar startCalendar = Calendar.getInstance();
		startCalendar.set(Calendar.HOUR_OF_DAY, 9);
		startCalendar.set(Calendar.MINUTE, 15);
		Date startTime = startCalendar.getTime();

		Calendar endCalendar = Calendar.getInstance();
		endCalendar.set(Calendar.HOUR_OF_DAY, 15);
		endCalendar.set(Calendar.MINUTE, 30);
		Date endTime = endCalendar.getTime();

		if (new Date().after(startTime) && new Date().before(endTime)) {
			//System.out.println("true");
			return true;
		} else {
			//System.out.println("false");
			return false;
		}

	}
	
	public Instant getStartTime() {
		Instant start = Instant.now();
		return start;
	}
	
	public String getEndTime(Instant start) {
		Instant end = Instant.now();
		// Convert to human-readable time
        LocalDateTime startTime = LocalDateTime.ofInstant(start, ZoneId.systemDefault());
        LocalDateTime endTime = LocalDateTime.ofInstant(end, ZoneId.systemDefault());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        System.out.println("Start Time: " + formatter.format(startTime));
        System.out.println("End Time:   " + formatter.format(endTime));
		Duration duration = Duration.between(start, end);

		long seconds = duration.getSeconds();
		long absSeconds = Math.abs(seconds);

		long hours = absSeconds / 3600;
		long minutes = (absSeconds % 3600) / 60;
		long secs = absSeconds % 60;
		String formatted = String.format("%02d:%02d:%02d", hours, minutes, secs);
		return formatted;
	}
}