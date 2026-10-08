package dev.iruki.classtime.ai

import java.io.File

/*
 * 엔진이 바깥과 만나는 세 군데. 실제 구현은 기기·네트워크를 쓰므로, 테스트에서는 가짜로
 * 바꿔 끼워 대기열의 흐름(나누기 → 받아 적기, 한도·실패 처리)만 검증한다.
 */

/** 녹음 파일 읽기. 실제: [AudioChunks]. */
interface AudioSource {
    /** 녹음 전체를 디코딩해 소리 크기와 목소리 주기성을 잰다. */
    fun profile(uri: String, isStopped: () -> Boolean): AudioProfile
    /** [pieces](ms)를 차례로 이어 붙여 [out](m4a)으로 쓴다. */
    fun cut(uri: String, pieces: List<LongRange>, out: File)
}

/** 받아 적기. 실제: [GroqClient]. */
interface SpeechToText {
    fun transcribe(key: String, file: File, prompt: String, language: String = "ko"): List<RawSegment>
}

/** 키 보관. 실제: [SecretStore]. */
interface Secrets {
    fun get(name: String): String?
    fun put(name: String, value: String?)
}
