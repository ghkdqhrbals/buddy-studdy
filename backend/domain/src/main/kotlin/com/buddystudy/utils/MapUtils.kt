package com.buddystudy.utils

import kotlin.reflect.full.memberProperties

/**
 * Object 를 value == null 또는 빈 문자열을 '제외' 한 Map으로 변환하는 함수
 */
fun <T : Any> T.toMapWithoutNull(): Map<String, Any> {
    val result = mutableMapOf<String, Any>()

    // Kotlin 객체의 프로퍼티 탐색
    this::class.memberProperties.forEach { property ->
        try {
            if (property.visibility == kotlin.reflect.KVisibility.PUBLIC) { // Public 프로퍼티만 접근
                val value = property.getter.call(this) // 안전하게 getter 호출
                if (value != null && value != "") {
                    result[property.name] = if (value::class.isData) {
                        value.toMapWithoutNull() // 중첩된 데이터 클래스 재귀 처리
                    } else {
                        value
                    }
                }
            }
        } catch (e: Exception) {
            println("Warning: Error accessing property '${property.name}': ${e.message}")
        }
    }

    // Java 객체의 필드 탐색 (필요할 경우 리플렉션 사용)
    this.javaClass.declaredFields.forEach { field ->
        try {
            field.isAccessible = true // 접근 권한 활성화
            val value = field.get(this)
            if (value != null && value != "" && !result.containsKey(field.name)) {
                result[field.name] = value
            }
        } catch (e: Exception) {
            println("Warning: Unable to access field '${field.name}': ${e.message}")
        }
    }

    return result
}

fun <T : Any> T.toMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()

    // Kotlin 객체의 프로퍼티 탐색
    this::class.memberProperties.forEach { property ->
        try {
            if (property.visibility == kotlin.reflect.KVisibility.PUBLIC) { // Public 프로퍼티만 접근
                val value = property.getter.call(this) // 안전하게 getter 호출
                result[property.name] = if (value != null && value::class.isData) {
                    value.toMap() // 중첩된 데이터 클래스 재귀 처리
                } else {
                    value
                }
            }
        } catch (e: Exception) {
            println("Warning: Error accessing property '${property.name}': ${e.message}")
        }
    }

    // Java 객체의 필드 탐색 (필요할 경우 리플렉션 사용)
    this.javaClass.declaredFields.forEach { field ->
        try {
            field.isAccessible = true // 접근 권한 활성화
            val value = field.get(this)
            if (!result.containsKey(field.name)) {
                result[field.name] = value
            }
        } catch (e: Exception) {
            println("Warning: Unable to access field '${field.name}': ${e.message}")
        }
    }

    return result
}

@Suppress("NOTHING_TO_INLINE")
inline fun <reified T : Any> T.toStringMapWithoutNull(): Map<String, String> =
    toMap().toStringMapWithoutNull()

@Suppress("NOTHING_TO_INLINE")
inline fun Map<String, Any?>.toStringMapWithoutNull(): Map<String, String> =
    entries
        .asSequence()
        .filter { (_, value) -> value != null && value.toString().isNotBlank() }
        .associate { (key, value) -> key to value.toString() }
