package com.buddystuddy.backend.auth.application.permission

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequirePermission(vararg val value: String)
