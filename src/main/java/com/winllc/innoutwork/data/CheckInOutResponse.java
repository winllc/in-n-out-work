package com.winllc.innoutwork.data;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;

import java.time.ZonedDateTime;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_TIME_FORMAT;

/**
 * The Windows client's answer to a status event: the record as stored, in the shape it always had.
 * <p>
 * Only the answer to a logon carries {@code sessionId}, at the top level where the client reads it. The client
 * caches it and sends it back with the logoff, which cannot sign in. That makes it a credential, so it is never
 * part of a record's own JSON and appears nowhere else.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckInOutResponse(Long id,
                                 String dn,
                                 @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DATE_TIME_FORMAT)
                                 ZonedDateTime timestamp,
                                 CheckInOutEnum action,
                                 String windowsUserId,
                                 String sessionId) {

    /** The answer to a logon: the record and the session id its logoff will need. */
    public static CheckInOutResponse forCheckIn(CheckInOutRecord record) {
        return new CheckInOutResponse(record.getId(), record.getDn(), record.getTimestamp(), record.getAction(),
                record.getWindowsUserId(), record.getSessionId());
    }

    /** The answer to anything else: the record only. */
    public static CheckInOutResponse of(CheckInOutRecord record) {
        return new CheckInOutResponse(record.getId(), record.getDn(), record.getTimestamp(), record.getAction(),
                record.getWindowsUserId(), null);
    }
}
