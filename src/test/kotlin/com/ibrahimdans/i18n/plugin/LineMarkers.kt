package com.ibrahimdans.i18n.plugin

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project

/**
 * The line markers the last highlighting pass left on [document], read from its markup model.
 * `DaemonCodeAnalyzerImpl.getLineMarkers` did this up to 2026.2 and is gone in 2026.3; the markup
 * model holds the same markers on both.
 */
fun lineMarkersOf(document: Document, project: Project): List<LineMarkerInfo<*>> =
    DocumentMarkupModel.forDocument(document, project, true).allHighlighters
        .mapNotNull { (it.gutterIconRenderer as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo }
