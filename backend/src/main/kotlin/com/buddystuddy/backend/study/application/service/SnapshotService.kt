package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.admin.application.port.inbound.AdminUseCase
import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.settings.application.port.inbound.SettingsUseCase
import com.buddystuddy.backend.stats.application.port.inbound.GetStudyStatsInputPort
import com.buddystuddy.backend.study.application.model.BackendSnapshotResponse
import com.buddystuddy.backend.study.application.port.inbound.BrowseRecordsInputPort
import com.buddystuddy.backend.study.application.port.inbound.SnapshotInputPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SnapshotService(
    private val settingsUseCase: SettingsUseCase,
    private val adminUseCase: AdminUseCase,
    private val recordsInputPort: BrowseRecordsInputPort,
    private val statsInputPort: GetStudyStatsInputPort,
) : SnapshotInputPort {
    @Transactional(readOnly = true)
    override fun snapshot(principal: Principal, limit: Int, offset: Int): BackendSnapshotResponse {
        val records = recordsInputPort.records(principal, limit, offset)
        return BackendSnapshotResponse(
            settings = settingsUseCase.settings(principal),
            api = adminUseCase.apiStatus(principal),
            records = records.records,
            stats = statsInputPort.stats(principal, 8, 0),
            totalCount = records.totalCount,
            serverTime = Instant.now(),
        )
    }
}
