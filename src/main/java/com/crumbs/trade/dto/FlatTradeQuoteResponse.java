package com.crumbs.trade.dto;

import lombok.Data;

@Data
public class FlatTradeQuoteResponse {

    private String stat;
    private String emsg;

    private String token;
    private String lp;   // ✅ LTP
    private String h;
    private String l;
    private String v;
    private String ltt;
}
