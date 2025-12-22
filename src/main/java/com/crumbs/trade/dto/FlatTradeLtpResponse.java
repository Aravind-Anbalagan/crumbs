package com.crumbs.trade.dto;

import lombok.Data;

@Data
public class FlatTradeLtpResponse {
    private String stat;   // Ok / Not_Ok
    private String exch;
    private String tsym;
    private String ltp;
}
