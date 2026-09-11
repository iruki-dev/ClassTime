package dev.iruki.classtime.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.compose.runtime.Composable
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.ui.home.HomeViewModel
import dev.iruki.classtime.ui.recordings.RecordingsViewModel
import dev.iruki.classtime.ui.term.TermViewModel
import dev.iruki.classtime.ui.timetable.TimetableViewModel

private fun CreationExtras.app(): Application = this[APPLICATION_KEY] as Application
private fun CreationExtras.repo(): ClassTimeRepository = ClassTimeRepository.get(app())

val ClassTimeViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer { TimetableViewModel(app(), repo()) }
    initializer { RecordingsViewModel(app(), repo()) }
    initializer { HomeViewModel(app(), repo()) }
    initializer { TermViewModel(app(), repo()) }
}

@Composable
inline fun <reified VM : androidx.lifecycle.ViewModel> classTimeViewModel(): VM =
    viewModel(factory = ClassTimeViewModelFactory)
