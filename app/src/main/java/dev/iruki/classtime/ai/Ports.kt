package dev.iruki.classtime.ai

import java.io.File

/*
 * 엔진이 바깥과 만나는 네 군데. 실제 구현은 기기·네트워크를 쓰므로, 테스트에서는 가짜로
 * 바꿔 끼워 대기열의 흐름(나누기 → 받아 적기 → 교정, 한도·실패 처리)만 검증한다.
 */

/** 녹음 파일 읽기. 실제: [AudioChunks]. */
interface AudioSource {
    fun envelope(uri: String, isStopped: () -> Boolean): FloatArray
    fun cut(uri: String, range: LongRange, out: File)
}

/** 받아 적기. 실제: [GroqClient]. */
interface SpeechToText {
    fun transcribe(key: String, file: File, prompt: String, language: String = "ko"): List<RawSegment>
}

/** 교정. 실제: [NvidiaClient]. */
interface ChatModel {
    fun chat(key: String, model: String, system: String, user: String, maxTokens: Int = 16_384): String
}

/** 키 보관. 실제: [SecretStore]. */
interface Secrets {
    fun get(name: String): String?
    fun put(name: String, value: String?)
}
