package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.UserService;
import org.springframework.stereotype.Component;

@Component
public class RefreshUserRecordsCron {

    private UserRecordRepository userRecordRepository;
    private UserService userService;

    public void run(){

        //userService.createUserIfDoesNotExist();

    }
}
