package com.winllc.innoutwork.data.home;

import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;
import com.winllc.innoutwork.model.NotificationRecord;

import java.util.List;

/**
 * A manager's view of their direct reports.
 *
 * @param reportCount       direct reports in the directory
 * @param accountability    accounted for, status mix and agent coverage, for the reports only
 * @param notInYet          unaccounted reports with the time each was expected
 * @param awaitingResponse  absence notifications sent to the manager and not yet answered, newest first
 * @param upcoming          reports' statuses for the coming days, by date then name
 */
public record TeamSummary(int reportCount, AccountabilityMetrics accountability, List<NotInYet> notInYet,
                          List<NotificationRecord> awaitingResponse, List<UpcomingStatus> upcoming) {
}
