package com.winllc.innoutwork.model;

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
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "group_records")
public class GroupRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String groupDn;
    private String alternateManagers;

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
