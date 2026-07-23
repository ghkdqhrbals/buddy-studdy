package com.buddystudy.backend.common.adapter.inbound.web

import com.buddystudy.backend.auth.application.permission.PermissionChecker
import com.buddystudy.backend.auth.application.permission.PermissionEvaluationContext
import com.buddystudy.backend.auth.application.permission.RequirePermission
import kotlinx.coroutines.reactor.mono
import org.aopalliance.intercept.MethodInvocation
import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.authorization.ReactiveAuthorizationManager
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils
import reactor.core.publisher.Mono
import java.time.Instant

@Component
class RequirePermissionAuthorizationManager(
    private val permissionChecker: PermissionChecker,
) : ReactiveAuthorizationManager<MethodInvocation> {
    override fun authorize(
        authentication: Mono<Authentication>,
        invocation: MethodInvocation,
    ): Mono<AuthorizationResult> {
        val requiredPermissions = requiredPermissions(invocation)
        if (requiredPermissions.isEmpty()) {
            return Mono.just<AuthorizationResult>(AuthorizationDecision(true))
        }

        return authentication
            .flatMap { current ->
                mono<AuthorizationResult> {
                    val principal = current.optionalPrincipal()
                    val requestDetails = current.details as? ReactiveRequestDetails
                    permissionChecker.check(
                        principal = principal,
                        requiredPermissions = requiredPermissions,
                        context = principal?.let {
                            PermissionEvaluationContext(
                                now = Instant.now(),
                                appVersion = requestDetails?.appVersion,
                                sessionId = it.sessionId,
                                status = it.status,
                                anonymous = it.anonymous,
                            )
                        },
                    )
                    AuthorizationDecision(true)
                }
            }
            .switchIfEmpty(
                mono<AuthorizationResult> {
                    permissionChecker.check(null, requiredPermissions)
                    AuthorizationDecision(true)
                },
            )
    }

    private fun requiredPermissions(invocation: MethodInvocation): List<String> {
        val targetClass = invocation.`this`
            ?.let(ClassUtils::getUserClass)
            ?: invocation.method.declaringClass
        val method = AopUtils.getMostSpecificMethod(invocation.method, targetClass)
        val classPermissions = AnnotatedElementUtils
            .findMergedAnnotation(targetClass, RequirePermission::class.java)
            ?.value
            ?.toList()
            .orEmpty()
        val methodPermissions = AnnotatedElementUtils
            .findMergedAnnotation(method, RequirePermission::class.java)
            ?.value
            ?.toList()
            .orEmpty()

        return (classPermissions + methodPermissions)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }
}
