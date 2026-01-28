package com.winllc.innoutwork.model;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@AllArgsConstructor
@Entity
@Table(name = "notification_records")
@NoArgsConstructor
public class NotificationRecord implements Comparable<NotificationRecord> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String forUserDn;
    private String aboutUserDn;
    private ZonedDateTime notificationDate;
    @Column(nullable = false, columnDefinition = "text")
    @Enumerated(EnumType.STRING)
    private NotificationTypeEnum type;
    @Column(nullable = true, columnDefinition = "text")
    @Enumerated(EnumType.STRING)
    private UserStatusEnum statusResponse;
    private ZonedDateTime statusResponseDate;

    @Override
    public int compareTo(NotificationRecord o) {
        return o.notificationDate.compareTo(this.notificationDate);
    }

    public String getSummary() {
        LdapDn userDn = new LdapDn(forUserDn);

        String notiDate = DateTimeConstants.DATE_FORMATTER.format(notificationDate);

        return String.format("%s: %s on %s",
                userDn.getCn(), type, notiDate);
    }
}
