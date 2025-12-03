package com.winllc.innoutwork.model;

import com.winllc.innoutwork.constant.UserStatusEnum;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @Column(columnDefinition = "text")
    @Enumerated(EnumType.STRING)
    private UserStatusEnum status;
    @Column(length = 2000)
    private String favoriteGroups;
    private String organization;
    private String employeeType;


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
        if(favoriteGroups != null){
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
}
