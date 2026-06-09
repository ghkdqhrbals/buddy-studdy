package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.study.application.model.StudyPageResponse
import com.buddystuddy.backend.study.application.port.inbound.BrowseRecordsUseCase
import com.buddystuddy.backend.study.application.port.inbound.StudySyncUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class StudySyncService(
    private val recordsUseCase: BrowseRecordsUseCase,
) : StudySyncUseCase {
    @Transactional(readOnly = true)
    override fun study(principal: Principal, limit: Int, offset: Int): StudyPageResponse {
        val records = recordsUseCase.records(principal, limit, offset)
        return StudyPageResponse(
            records = records.records,
            totalCount = records.totalCount,
            limit = records.limit,
            offset = records.offset,
            serverTime = Instant.now(),
        )
    }
}
