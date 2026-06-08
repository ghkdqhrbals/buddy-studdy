package com.buddystuddy.backend.community.adapter.outbound.persistence

import com.buddystuddy.backend.community.application.port.outbound.ReportPort
import com.buddystuddy.community.domain.entity.ReportEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ReportRepository : JpaRepository<ReportEntity, Long>, ReportPort
