package com.winllc.innoutwork.model;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_records")
public class UserRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String dn;
    private String notes;

    @Column(length = 2000)
    private String favoriteGroups;
    private String organization;
    private String employeeType;
    private String location;
    private String branch;
    private String dutySubOrganization;
    private String phoneNumber;
    private String email;
    @Column(columnDefinition = "text")
    @Enumerated(EnumType.STRING)
    private UserRoleEnum userRole;
    private String alternateManagers;

    private LocalTime averageLoginTime;
    private LocalTime chosenLoginTime;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PermissionRecord> permissions = new ArrayList<>();

    public UserRecord(LdapUser user){
        this.dn = user.getDn();
        this.email = user.getEmail();
        this.phoneNumber = user.getPhoneNumber();
        this.organization = user.getOrganization();
        this.employeeType = user.getEmployeeType();
        this.location = user.getLocation();
        this.branch = user.getBranch();
        this.userRole = UserRoleEnum.USER;
    }

    public void addGroup(String group) {
        Set<String> groupSet = new HashSet<>(getFavoriteGroupsList());
        groupSet.add(group);
        this.favoriteGroups = String.join(";", groupSet);
    }

    public void removeGroup(String group) {
        List<String> groupList = new ArrayList<>(getFavoriteGroupsList());
        groupList.remove(group);
        this.favoriteGroups = String.join(";", groupList);
    }

    public List<String> getFavoriteGroupsList(){
        if(favoriteGroups != null && !favoriteGroups.isEmpty()){
            String[] split = favoriteGroups.split(";");
            return Stream.of(split)
                    .collect(Collectors.toList());
        }else{
            return new ArrayList<>();
        }
    }

    public boolean containsGroupDn(String groupDn){
        return getFavoriteGroupsList().contains(groupDn);
    }

    public void addAltManager(String group) {
        Set<String> groupSet = new HashSet<>(getAltManagerList());
        groupSet.add(group);
        this.alternateManagers = String.join(";", groupSet);
    }

    public void removeAltManager(String group) {
        List<String> groupList = new ArrayList<>(getAltManagerList());
        groupList.remove(group);
        this.alternateManagers = String.join(";", groupList);
    }

    public List<String> getAltManagerList(){
        if(alternateManagers != null && !alternateManagers.isEmpty()){
            String[] split = alternateManagers.split(";");
            return Stream.of(split)
                    .collect(Collectors.toList());
        }else{
            return new ArrayList<>();
        }
    }
}
