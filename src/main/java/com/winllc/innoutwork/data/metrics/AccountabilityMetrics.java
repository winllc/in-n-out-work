package com.winllc.innoutwork.data.metrics;

import java.util.List;

/**
 * The accountability section of the metrics page for one day.
 *
 * @param accountedFor how many expected users are known to be in or away, and who is not
 * @param statusMix    every expected user in exactly one category, so the counts add up to the expected total
 * @param agentCoverage whether each user's workstation agent is still reporting
 */
public record AccountabilityMetrics(AccountedFor accountedFor, List<StatusMixEntry> statusMix,
                                    AgentCoverage agentCoverage) {
}
