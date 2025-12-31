package com.crumbs.trade.config;



import java.time.LocalTime;
import java.util.List;

public class InstrumentRegistry {

    public static final List<InstrumentConfig> INSTRUMENTS = List.of(
        new InstrumentConfig(
            "NIFTY",
            LocalTime.of(9, 15),
            LocalTime.of(15, 30),
            2
        ),
        new InstrumentConfig(
            "CRUDEOIL",
            LocalTime.of(16, 0),
            LocalTime.of(23, 45),
            3
        )
    );
}
