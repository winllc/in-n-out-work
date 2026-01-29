package com.winllc.innoutwork.util;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

public class ValueValidatorUtil {
    public static boolean isValidEmail(String email) {
        try {
            new InternetAddress(email).validate();
            return true;
        } catch (AddressException e) {
            return false;
        }
    }

}
