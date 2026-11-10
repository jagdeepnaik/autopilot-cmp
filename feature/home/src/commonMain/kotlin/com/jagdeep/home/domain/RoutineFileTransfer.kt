package com.jagdeep.home.domain

import com.jagdeep.shared.routines.RoutineTransfer
import com.jagdeep.shared.routines.TransferRoutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class RoutineFileTransfer(
    private val repository: RoutineRepository,
    private val files: RoutineFiles,
    private val newId: () -> String,
    private val isRunning: () -> Boolean = { false },
) : RoutineTransfer {
    override val isAvailable get() = files.isAvailable
    override val routines = repository.library.map { library -> library?.routines.orEmpty().map { TransferRoutine(it.id,it.name) } }

    override suspend fun exportRoutines(ids: Set<String>): String? {
        if (!isAvailable || ids.isEmpty()) return null
        val selected = repository.library.filterNotNull().first().routines.filter { it.id in ids }
        if (selected.isEmpty()) return "No routines selected for export."
        val name = if (selected.size == 1) selected.single().name else "AutoPilot routines"
        val fileName = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Routines" } + RoutineFileCodec.FILE_SUFFIX
        return try {
            files.save(fileName,RoutineFileCodec.encode(selected))?.let { "Exported ${count(selected.size)}." }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { (error as? RoutineFileException)?.message ?: "Couldn't save the file. Try another folder." }
    }

    override suspend fun importRoutines(): String? {
        if (!isAvailable) return null
        if (isRunning()) return "Stop the running routine before importing."
        val text = try { files.open() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return (error as? RoutineFileException)?.message ?: "Couldn't read that file." } ?: return null
        if (isRunning()) return "Stop the running routine before importing."
        val imported = RoutineFileCodec.decode(text)
        if (imported == null || imported.routines.isEmpty()) return "That file has no routines AutoPilot can use."
        val added = imported.routines.map { it.withId(newId()) }
        added.forEach { repository.save(it) }
        repository.select(added.first().id)
        val skipped = if (imported.skipped > 0) " ${count(imported.skipped)} couldn't be used and were left out." else ""
        return "Imported ${count(added.size)}.$skipped Spots are positions on the screen they were made on, so check them before running."
    }
    private fun count(n: Int) = if(n==1) "1 routine" else "$n routines"
}
