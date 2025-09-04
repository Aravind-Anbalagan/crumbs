package com.crumbs.trade.dto;

import lombok.*;

@Data // includes getters, setters, toString, equals, hashCode
@NoArgsConstructor
@AllArgsConstructor
public class JData {
    private String uid;
    private String actid;
    private String exch;
    private String tsym;
    private String qty;
    private String prc;
    private String dscqty;
    private String prd;
    private String trantype;
    private String prctyp;
    private String ret;
    private String ordersource;
    private String stext;
}
