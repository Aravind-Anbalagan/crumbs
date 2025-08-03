package com.crumbs.trade.broker;


import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.TokenSet;
import com.angelbroking.smartapi.models.User;
import com.warrenstrange.googleauth.GoogleAuthenticator;


@Component
public class AngelOne {
	
public static SmartConnect smartConnect;
public static Optional<User> user;
public static String key = "A5EK2EGRTGRG7DOKSJI6SZG66Q";
public static String clientID = "R705672";
//public static String clientPass = "Athiran#2020";
public static String clientPass = "8889";
public static String apiKey = "7HklvkfL";
public static SmartConnect signIn() throws InterruptedException {

	// Initialize Samart API using clientcode and password.
	Logger logger = LoggerFactory.getLogger(AngelOne.class);
	GoogleAuthenticator gAuth = new GoogleAuthenticator();
	String code = String.valueOf( gAuth.getTotpPassword(key));


	
	try {
		if("127.0.0.1".equals(InetAddress.getLocalHost().getHostAddress().toString()))
		{
			logger.error("Internet down");
		}
		else
		{
			if (user==null)
			{
				
				smartConnect = new SmartConnect(apiKey);
				user = Optional.ofNullable(smartConnect.generateSession(clientID, clientPass, code));
				int count =0;
				while(!user.isPresent())
				{
				  code = String.valueOf( gAuth.getTotpPassword(key));
				  count++;
				  TimeUnit.SECONDS.sleep(1);
				  user = Optional.ofNullable(smartConnect.generateSession(clientID, clientPass, code));
					logger.error(String.valueOf(count));
				  if(count>120)
				  {
					  break;
				  }
				}
			  
				String feedToken = user.get().getFeedToken();
				//System.out.println("feedToken " +feedToken);
				smartConnect.setAccessToken(user.get().getAccessToken());
				smartConnect.setUserId(user.get().getUserId());
				//System.out.println(feedToken);
				//System.out.println("Signed In");
			}
			else
			{
				//System.out.println("User is Already Active");
				//TokenSet tokenSet = smartConnect.renewAccessToken(user.get().getAccessToken(),
				//user.get().getRefreshToken());
				//smartConnect.setAccessToken(tokenSet.getAccessToken());
				
			}
			
			
		}
	} catch (UnknownHostException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}

	
	return smartConnect;
}

}
