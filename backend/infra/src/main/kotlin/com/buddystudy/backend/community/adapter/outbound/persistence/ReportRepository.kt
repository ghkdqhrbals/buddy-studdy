package com.buddystudy.backend.community.adapter.outbound.persistence

import com.buddystudy.backend.community.application.port.outbound.ReportPort
import com.buddystudy.community.domain.entity.ReportEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ReportRepository : JpaRepository<ReportEntity, Long>, ReportPort
