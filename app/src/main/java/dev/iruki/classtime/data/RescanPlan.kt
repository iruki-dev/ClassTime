package dev.iruki.classtime.data

import dev.iruki.classtime.audio.RecordingScanner

/**
 * 폴더 검사 결과를 목록(DB)에 어떻게 반영할지 정한다. 파일·DB 를 건드리지 않는 순수 계산이다.
 *
 * - **추가**: 폴더에는 있는데 목록에 없는 파일. 같은 파일인지는 uri, 그다음 ‘위치 + 파일명’으로 본다
 *   (재설치 뒤에는 같은 파일도 uri 가 달라질 수 있다).
 * - **삭제 후보**: 목록에는 있는데 폴더에서 못 찾은 것. 곧바로 지우지 않는다 — 권한이 없어
 *   안 보였을 수도 있으므로, 호출하는 쪽이 파일이 정말 없는지 하나씩 확인한 뒤에 지운다.
 * - 녹음 중인 행은 어느 쪽에도 넣지 않는다.
 */
data class RescanPlan(
    val toAdd: List<Recording>,
    val missingCandidates: List<Recording>,
) {
    companion object {
        /** 이 비트레이트(bps) 이하면 이미 압축된 파일로 본다. 원본은 96kbps 로 녹음한다. */
        private const val COMPRESSED_MAX_BPS = 48_000

        fun of(
            existing: List<Recording>,
            found: List<RecordingScanner.Found>,
            courseIdOf: (String) -> Long?,
        ): RescanPlan {
            val knownUris = existing.map { it.uri }.toSet()
            val knownPaths = existing.map { key(it.relativePath, it.fileName) }.toSet()
            val add = found
                .filter { it.uri !in knownUris && key(it.relativePath, it.fileName) !in knownPaths }
                .map { f ->
                    Recording(
                        courseId = courseIdOf(f.subject),
                        subject = f.subject,
                        fileName = f.fileName,
                        uri = f.uri,
                        relativePath = f.relativePath,
                        startedAt = f.startedAt,
                        durationMs = f.durationMs,
                        sizeBytes = f.sizeBytes,
                        auto = false,
                        ongoing = false,
                        compressed = looksCompressed(f.sizeBytes, f.durationMs),
                    )
                }
            val foundUris = found.map { it.uri }.toSet()
            val foundPaths = found.map { key(it.relativePath, it.fileName) }.toSet()
            val missing = existing.filter {
                !it.ongoing && it.uri !in foundUris && key(it.relativePath, it.fileName) !in foundPaths
            }
            return RescanPlan(add, missing)
        }

        fun looksCompressed(sizeBytes: Long, durationMs: Long): Boolean {
            if (durationMs <= 0 || sizeBytes <= 0) return false
            return sizeBytes * 8 * 1000 / durationMs <= COMPRESSED_MAX_BPS
        }

        private fun key(relativePath: String, fileName: String) = relativePath.trimEnd('/') + "/" + fileName
    }
}
