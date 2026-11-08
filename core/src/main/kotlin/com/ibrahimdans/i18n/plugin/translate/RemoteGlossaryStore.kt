package com.ibrahimdans.i18n.plugin.translate

/**
 * Where an engine keeps its glossary on the engine's side: its [glossaryId], empty before the first
 * one, and what each language pair holds there — `en>fr` to a digest of the entries sent.
 */
interface RemoteGlossaryStore {
    var glossaryId: String
    var glossaryDictionaries: MutableMap<String, String>
}

/** A glossary remembered for the life of one provider only — the *Test* of an engine, a test. */
class InMemoryGlossaryStore(
    override var glossaryId: String = "",
    override var glossaryDictionaries: MutableMap<String, String> = linkedMapOf()
) : RemoteGlossaryStore
