package com.iflytek.astron.console.hub.service.ashare;

/** Market data could not be fetched or parsed (the Eastmoney public endpoints are unofficial). */
public class AShareDataException extends RuntimeException {

    public AShareDataException(String message) {
        super(message);
    }
}
