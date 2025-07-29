package com.crumbs.trade.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.util.List;



@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    public void sendHtmlEmail(String to, String subject, String htmlBody) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();

        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true); // true = isHtml

        mailSender.send(message);
    }
    
    public String buildHtmlTable(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table border='1' cellpadding='5' cellspacing='0' style='border-collapse: collapse;'>");

        for (int i = 0; i < rows.size(); i++) {
            sb.append("<tr>");
            for (String cell : rows.get(i)) {
                if (i == 0) {
                    sb.append("<th style='background-color:#f2f2f2;'>").append(cell).append("</th>");
                } else {
                    sb.append("<td>").append(cell).append("</td>");
                }
            }
            sb.append("</tr>");
        }

        sb.append("</table>");
        return sb.toString();
    }
    
    public String getEmailData(List<String[]> rows) throws MessagingException
    {
    	    String to ="anbalagan.aravind@gmail.com";
    	    String subject = "Stock List";
    	    String htmlBody = "<h3>Stock Details</h3>" + buildHtmlTable(rows);
    	    sendHtmlEmail(to, subject, htmlBody); // call your email sending method here
			return htmlBody;
    }
}