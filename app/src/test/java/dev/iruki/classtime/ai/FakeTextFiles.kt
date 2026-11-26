package dev.iruki.classtime.ai

import android.net.Uri
import dev.iruki.classtime.data.Recording

/** 메모리에만 쓰는 [TextFiles]. (녹음 id, 종류) → 내용. */
class FakeTextFiles : TextFiles {
    val files = mutableMapOf<Pair<Long, TextKind>, String>()

    override fun write(recording: Recording, kind: TextKind, text: String): Uri? {
        files[recording.id to kind] = text
        return Uri.parse("content://t/${recording.id}/$kind")
    }

    override fun find(recording: Recording, kind: TextKind): Uri? =
        if ((recording.id to kind) in files) Uri.parse("content://t/${recording.id}/$kind") else null

    override fun delete(recording: Recording, kind: TextKind) {
        files.remove(recording.id to kind)
    }

    override fun rename(recording: Recording, newFileName: String) = Unit

    operator fun get(id: Long, kind: TextKind = TextKind.TRANSCRIPT) = files[id to kind]
}
