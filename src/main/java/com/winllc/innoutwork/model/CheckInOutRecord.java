package com.winllc.innoutwork.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_TIME_FORMAT;

@Data
@Builder
@AllArgsConstructor
@Entity
// Every logoff without a sign-in looks its check-in up by session id, under a lock. The schema, with this index,
// comes from Flyway (db/migration/V1__baseline.sql); declared here too, under the same name, for the H2 test slices.
@Table(name = "check_in_out_records",
        indexes = @Index(name = "ix_check_in_out_session_id", columnList = "sessionId"))
public class CheckInOutRecord implements Comparable<CheckInOutRecord> {

    /** {@link #clientAddress} of a check-in from before the binding: its logoff is taken from any address. */
    public static final String ANY_ADDRESS = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true)
    private String dn;

    @Column(nullable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME_FORMAT)
    private ZonedDateTime timestamp;

    /**
     * Stands in for a sign-in on the Windows client's logoff, so it is a credential: it goes back to the
     * client in the check-in response (CheckInOutResponse) and nowhere else, not in any JSON nor the log.
     */
    @JsonIgnore
    @ToString.Exclude
    private String sessionId;

    /**
     * On a check-in: the client address it came from (ClientAddress#of), the only one its session id is accepted
     * from on the logoff that cannot sign in. {@link #ANY_ADDRESS} on check-ins from before the logoff was bound
     * to an address (V5__logoff_client_address.sql); null on every other record. Personal data, so in no JSON
     * but the person's own export, and not in the log.
     */
    @JsonIgnore
    @ToString.Exclude
    @Column(length = 64)
    private String clientAddress;

    @Column(nullable = false, columnDefinition = "text")
    @Enumerated(EnumType.STRING)
    private CheckInOutEnum action;
    private String windowsUserId;
    private String organization;
    private String employeeType;
    private String location;
    private String branch;
    private String dutySubOrganization;
    private Boolean forced = false;

    public CheckInOutRecord() {
        this.sessionId = UUID.randomUUID().toString(); // Generate UUID in constructor
    }

    @PrePersist
    public void generateId() {
        if (this.sessionId == null) {
            this.sessionId = UUID.randomUUID().toString();
        }
    }

    @Override
    public int compareTo(CheckInOutRecord o) {
        return o.timestamp.compareTo(this.timestamp);
    }

    public ZonedDateTime getZonedDateTimestamp(){
        return ZonedDateTime.ofInstant(timestamp.toInstant(), ZoneId.systemDefault());
    }
}
