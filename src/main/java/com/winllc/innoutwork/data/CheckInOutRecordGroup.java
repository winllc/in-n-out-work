package com.winllc.innoutwork.data;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import lombok.Getter;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Getter
public class CheckInOutRecordGroup {
    private final String dn;
    private final String employeeType;
    private final List<CheckInOutRecord> records;

    public CheckInOutRecordGroup(String dn, String employeeType, List<CheckInOutRecord> records) {
        this.dn = dn;
        this.employeeType = employeeType;
        this.records = records.stream().sorted(Comparator.comparing(CheckInOutRecord::getZonedDateTimestamp))
                .toList();
    }

    public Optional<CheckInOutRecord> findEarliest(){
        if(records.isEmpty()){
            return Optional.empty();
        }
        return Optional.of(records.getFirst());
    }

    public Optional<CheckInOutRecord> findLatest(){
        if(records.isEmpty()){
            return Optional.empty();
        }
        return Optional.of(records.getLast());
    }

    public boolean isCheckedIn(){
        if(findEarliest().isPresent() && findLatest().isPresent()){
            return findEarliest().get().getAction() == CheckInOutEnum.CHECK_IN
                    && findLatest().get().getAction() != CheckInOutEnum.CHECK_OUT;
        }else{
            return false;
        }
    }
}
