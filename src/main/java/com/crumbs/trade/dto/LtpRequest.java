package com.crumbs.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LtpRequest {
    private String exchange;
    private String tradingsymbol;
    private String symboltoken;
}
