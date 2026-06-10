package com.buddystuddy.backend.common.adapter.inbound.web

import com.buddystuddy.backend.auth.application.permission.PermissionChecker
import com.buddystuddy.backend.auth.application.permission.RequirePermission
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Aspect
@Component
class RequirePermissionAspect(
    private val permissionChecker: PermissionChecker,
) {
    @Around("@within(com.buddystuddy.backend.auth.application.permission.RequirePermission) || @annotation(com.buddystuddy.backend.auth.application.permission.RequirePermission)")
    fun check(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method
        val targetClass = joinPoint.target?.let { AopUtils.getTargetClass(it) } ?: method.declaringClass
        val required = buildList {
            AnnotatedElementUtils.findMergedAnnotation(targetClass, RequirePermission::class.java)?.let { addAll(it.value) }
            AnnotatedElementUtils.findMergedAnnotation(method, RequirePermission::class.java)?.let { addAll(it.value) }
        }.distinct()

        permissionChecker.check(SecurityContextHolder.getContext().authentication.optionalPrincipal(), required)
        return joinPoint.proceed()
    }
}
