package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.PermissionChecker
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.RequirePermission
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant

@Aspect
@Component
class RequirePermissionAspect(
    private val permissionChecker: PermissionChecker,
) {
    @Around("@within(com.buddystudy.backend.auth.application.permission.RequirePermission) || @annotation(com.buddystudy.backend.auth.application.permission.RequirePermission)")
    fun check(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method
        val targetClass = joinPoint.target?.let { AopUtils.getTargetClass(it) } ?: method.declaringClass
        val required = buildList {
            AnnotatedElementUtils.findMergedAnnotation(targetClass, RequirePermission::class.java)?.let { addAll(it.value) }
            AnnotatedElementUtils.findMergedAnnotation(method, RequirePermission::class.java)?.let { addAll(it.value) }
        }.distinct()

        val principal = SecurityContextHolder.getContext().authentication.optionalPrincipal()
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        permissionChecker.check(
            principal,
            required,
            principal?.let {
                PermissionEvaluationContext(
                    now = Instant.now(),
                    appVersion = request?.getHeader("X-App-Version"),
                    sessionId = it.sessionId,
                    status = it.status,
                    anonymous = it.anonymous,
                )
            },
        )
        return joinPoint.proceed()
    }
}
