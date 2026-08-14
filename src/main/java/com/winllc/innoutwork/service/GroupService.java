package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final LdapService ldapService;
    private final GroupRecordRepository groupRecordRepository;

    public GroupService(LdapService ldapService, GroupRecordRepository groupRecordRepository) {
        this.ldapService = ldapService;
        this.groupRecordRepository = groupRecordRepository;
    }

    public List<String> getManagersForGroup(String groupDn) {
        LdapDn dn = LdapDn.builder().dn(groupDn).build();
                
        List<String> managers = new ArrayList<>();
        
        Optional<LdapGroup> groupOptional = ldapService.lookupGroup(dn);

        if (groupOptional.isPresent()) {
            LdapGroup group = groupOptional.get();
            if(StringUtils.isNotBlank(group.getManager())){
                managers.add(group.getManager());
            }
        }

        Optional<GroupRecord> recordOptional = groupRecordRepository.findByGroupDnIgnoreCase(dn.dn());

        if(recordOptional.isPresent()){
            GroupRecord record = recordOptional.get();
            managers.addAll(record.getAltManagerList());
        }

        return managers;
    }

    public GroupRecord getOrCreateGroupRecord(String groupDn) {
        LdapDn dn = LdapDn.builder().dn(groupDn).build();

        Optional<GroupRecord> recordOptional = groupRecordRepository.findByGroupDnIgnoreCase(dn.dn());

        if(recordOptional.isPresent()){
            return recordOptional.get();
        }else{
            GroupRecord newRecord = new GroupRecord();
            newRecord.setGroupDn(dn.dn());
            return groupRecordRepository.save(newRecord);
        }
    }

}
