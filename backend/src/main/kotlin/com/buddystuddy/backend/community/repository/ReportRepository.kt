package com.buddystuddy.backend.community.repository

import com.buddystuddy.backend.domain.ReportEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ReportRepository : JpaRepository<ReportEntity, Long>
