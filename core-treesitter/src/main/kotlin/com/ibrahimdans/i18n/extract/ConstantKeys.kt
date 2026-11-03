package com.ibrahimdans.i18n.extract

import com.ibrahimdans.i18n.extract.i18next.SourceKey
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import com.ibrahimdans.i18n.extract.i18next.findKeys as findWrittenKeys

/**
 * Keys a call names through a constant, on a tree-sitter TSX tree: `t(SAVE_KEY)`, `t(KEYS.save)`, `t(Keys.Save)`.
 * What the plugin's `ConstantKeyResolver` reads off the PSI, read here without one.
 *
 * One hop, and one only: the first argument of a call is a name resolving to a `const` (or a `let` or `var`
 * never reassigned, and not exported) whose initializer is a string — a literal, a template without `${…}`,
 * or a conditional whose every branch is one —, to a property of an object literal initializing a `const`
 * (nested or not, `as const` or not), or to a string member of an `enum`. The name may be imported from
 * another file of the project by a relative path, `import { KEYS } from './keys'`, or by one of the project's
 * path aliases, `import { KEYS } from '@/keys'` ([PathAliases]), when that file exports the constant itself
 * ([Imports]). A parameter, a variable reassigned, a value a function computes or a re-export: nothing, as in
 * the plugin.
 *
 * The extractors are not taught constants: the file is read a second time with each such argument written
 * as the literal it holds — a conditional as `(0 ? 'a' : 'b')`, which they read branch by branch — and
 * the keys found there are reported at the argument. So every framework's calls follow constants alike,
 * with the namespace and prefix of the call: its hook, its `{ ns }` option.
 */
object ConstantKeys {

    /**
     * Every key under [root], the parse of [text], read with [extractors]: those written there, then those
     * passed through a constant, reported at the argument naming it; in source order.
     */
    fun findKeys(text: String, root: CodeNode, extractors: List<CodeKeyExtractor>, imports: Imports = Imports.NONE): List<SourceKey> {
        val written = findWrittenKeys(root, extractors)
        val substitutions = substitutionsOf(root, imports)
        if (substitutions.isEmpty()) return written
        val rewritten = Rewritten(text, substitutions)
        val tree = TreeSitterCodeNode.parseTsx(rewritten.text, root.file)
        val through = findWrittenKeys(tree, extractors).mapNotNull { (node, key) -> rewritten.argumentAt(node.span)?.let { SourceKey(it, key) } }
        return (written + through).sortedBy { it.node.span.start }
    }

    /** Where the constants another file of the project exports are found: what an `import` names. */
    fun interface Imports {

        /** The declaration [importer] imports as [name] from [source], or null when it is no constant this module reads. */
        fun declaration(importer: String?, source: String, name: String): Declaration?

        companion object {
            /** No file but the one read: an imported name holds nothing. */
            val NONE = Imports { _, _, _ -> null }
        }
    }

    /**
     * The path aliases of a project's `tsconfig.json` or `jsconfig.json`: its `compilerOptions.paths`, each target read
     * from [baseUrl], project-relative (`"."` when the file names none). The pattern `@/` followed by a wildcard, mapped
     * to `./src/` followed by one, makes `@/i18n/keys` stand for `src/i18n/keys`. As TypeScript picks them, an exact
     * pattern wins over a wildcard one, the longest prefix wins among these, and the targets of the pattern are tried
     * in order; a pattern or target with more than one wildcard is left out.
     */
    class PathAliases(baseUrl: String, paths: Map<String, List<String>>) {

        private val base = normalized(baseUrl)

        private val patterns = paths.entries
            .filter { (pattern, _) -> pattern.count { it == '*' } <= 1 }
            .map { (pattern, targets) -> Pattern(pattern, targets.filter { target -> target.count { it == '*' } <= 1 }) }
            .sortedWith(compareBy<Pattern> { it.isWildcard }.thenByDescending { it.prefix.length })

        /** The project-relative paths `import … from '[source]'` stands for, before extensions and `index` files; empty when no alias matches. */
        fun pathsOf(source: String): List<String> {
            if (base == null) return emptyList()
            val pattern = patterns.firstOrNull { it.matches(source) } ?: return emptyList()
            val star = pattern.starOf(source)
            return pattern.targets.mapNotNull { target -> normalized("$base/" + target.replace("*", star)) }
        }

        private class Pattern(pattern: String, val targets: List<String>) {
            val isWildcard = '*' in pattern
            val prefix = pattern.substringBefore('*')
            private val suffix = if (isWildcard) pattern.substringAfter('*') else ""

            fun matches(source: String): Boolean =
                if (isWildcard) source.length >= prefix.length + suffix.length && source.startsWith(prefix) && source.endsWith(suffix)
                else source == prefix

            /** What the `*` of this pattern stands for in [source]. */
            fun starOf(source: String): String = if (isWildcard) source.substring(prefix.length, source.length - suffix.length) else ""
        }

        companion object {
            /** No alias: only relative imports name a file. */
            val NONE = PathAliases(".", emptyMap())
        }
    }

    /**
     * The files of a project, project-relative path → code, read as [Imports]: a relative import names one
     * of them, and so does one the path aliases of the importing file map (`@/keys`, [aliasesOf] — in a monorepo
     * each package has its own); its extension and an `index` file are looked up as a bundler would. Each file is
     * parsed once, the first time an import names it.
     */
    class ProjectFiles(private val files: Map<String, String>, private val aliasesOf: (importer: String) -> PathAliases) : Imports {

        /** The same [aliases] for every file of the project. */
        constructor(files: Map<String, String>, aliases: PathAliases = PathAliases.NONE) : this(files, { aliases })

        private val parsed = mutableMapOf<String, CodeNode?>()

        @Synchronized
        override fun declaration(importer: String?, source: String, name: String): Declaration? {
            if (importer == null) return null
            val bases =
                if (source.startsWith(".")) listOfNotNull(normalized(importer.substringBeforeLast('/', "") + "/" + source))
                else aliasesOf(importer).pathsOf(source)
            val path = bases.asSequence().flatMap { candidatesOf(it) }.firstOrNull { it in files } ?: return null
            val root = parsed.getOrPut(path) { TreeSitterCodeNode.parseTsx(files.getValue(path), path) } ?: return null
            return exportedDeclaration(root, name)
        }

        private companion object {
            val EXTENSIONS = listOf("ts", "tsx", "js", "jsx", "mjs", "cjs")

            /** The files an import of [base] may name: as written, with an extension, an `index` file; `.js` standing for `.ts` too. */
            fun candidatesOf(base: String): List<String> {
                val withoutJs = base.removeSuffix(".js").takeIf { it != base }
                return listOf(base) +
                    EXTENSIONS.map { "$base.$it" } +
                    withoutJs?.let { stem -> listOf("ts", "tsx").map { "$stem.$it" } }.orEmpty() +
                    EXTENSIONS.map { "$base/index.$it" }
            }
        }
    }

    /** [path] without its `.` and `..` segments, null when it climbs above the project. */
    private fun normalized(path: String): String? {
        val segments = ArrayDeque<String>()
        for (segment in path.split('/')) when (segment) {
            "", "." -> {}
            ".." -> segments.removeLastOrNull() ?: return null
            else -> segments.addLast(segment)
        }
        return segments.joinToString("/")
    }

    /** What a name resolves to: a variable, or an `enum`. */
    sealed class Declaration {

        /** The string literals held at [path] under it, `[]` for the name itself; null for anything else. */
        abstract fun literalsAt(path: List<String>): List<CodeNode>?

        /**
         * A variable declared with [value]: [isConst] for a `const`, [keepsValue] when no code may assign it
         * another value — a `const`, or a `let` or `var` neither exported nor assigned in its scope.
         */
        class Variable(private val value: CodeNode?, private val isConst: Boolean, private val keepsValue: Boolean) : Declaration() {
            override fun literalsAt(path: List<String>): List<CodeNode>? {
                if (path.isEmpty()) return if (keepsValue) literalsIn(value) else null
                // The properties of an object are constants only under a `const`.
                if (!isConst) return null
                var current = value?.let(::unwrapped) ?: return null
                for (name in path) {
                    if (current.type != OBJECT) return null
                    current = propertyValue(current, name)?.let(::unwrapped) ?: return null
                }
                return singleLiteral(current)
            }
        }

        /** An `enum`, whose members are read by name. */
        class Enum(private val body: CodeNode) : Declaration() {
            override fun literalsAt(path: List<String>): List<CodeNode>? {
                val name = path.singleOrNull() ?: return null
                val member = body.children.firstOrNull { it.type == ENUM_ASSIGNMENT && it.children.firstOrNull()?.let(::nameOf) == name } ?: return null
                return singleLiteral(unwrapped(member.children.last()))
            }
        }
    }

    /** The argument [node] replaces in the file, and what it is written as. */
    private class Substitution(val node: CodeNode, val replacement: String)

    /** [text] with each substitution written, and where each one lies in it. */
    private class Rewritten(text: String, substitutions: List<Substitution>) {

        val text: String
        private val written: List<Pair<TextSpan, CodeNode>>

        init {
            val builder = StringBuilder(text.length)
            val spans = mutableListOf<Pair<TextSpan, CodeNode>>()
            var copied = 0
            for (substitution in substitutions.sortedBy { it.node.span.start }) {
                val span = substitution.node.span
                if (span.start < copied) continue
                builder.append(text, copied, span.start)
                val start = builder.length
                builder.append(substitution.replacement)
                spans += TextSpan(start, builder.length) to substitution.node
                copied = span.end
            }
            builder.append(text, copied, text.length)
            this.text = builder.toString()
            written = spans
        }

        /** The argument whose substitution holds [span], null for a span outside them all. */
        fun argumentAt(span: TextSpan): CodeNode? =
            written.firstOrNull { (at, _) -> at.start <= span.start && span.end <= at.end }?.second
    }

    /** The first arguments of [root]'s calls that name a constant holding strings, with the literal written for each. */
    private fun substitutionsOf(root: CodeNode, imports: Imports): List<Substitution> {
        val scope = FileScope(root, imports)
        val substitutions = mutableListOf<Substitution>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            pending.addAll(0, node.children)
            if (node.type != CALL_EXPRESSION) continue
            val callee = node.children.firstOrNull()?.text ?: continue
            if (callee.substringAfterLast('.') in NAMESPACE_CALLS) continue
            val argument = node.children.lastOrNull { it.type == ARGUMENTS }?.let(::items)?.firstOrNull() ?: continue
            if (argument.type != IDENTIFIER && argument.type != MEMBER_EXPRESSION) continue
            val literals = scope.literalsOf(argument) ?: continue
            expressionOf(literals)?.let { substitutions += Substitution(argument, it) }
        }
        return substitutions
    }

    /** The declarations of one file, the names its calls pass looked up in it, and through [imports]. */
    private class FileScope(private val root: CodeNode, private val imports: Imports) {

        /** The string literals the constant [argument] names holds, null when it names no constant holding strings. */
        fun literalsOf(argument: CodeNode): List<CodeNode>? {
            val (name, path) = referenceOf(argument) ?: return null
            return declarationOf(argument, name)?.literalsAt(path)?.takeIf { it.isNotEmpty() }
        }

        /** The declaration [name] stands for at [use], searched in the scopes around it, nearest first; null when a parameter hides it. */
        private fun declarationOf(use: CodeNode, name: String): Declaration? {
            for (scope in generateSequence(use.parent) { it.parent }) {
                if (scope.type in FUNCTIONS && name in parametersOf(scope)) return null
                if (scope.type == FOR_IN_STATEMENT && scope.children.any { it.type == IDENTIFIER && it.text == name }) return null
                if (scope.type == PROGRAM || scope.type == STATEMENT_BLOCK) declarationIn(scope, name)?.let { return it }
            }
            return null
        }

        /** The declaration of [name] among [scope]'s statements, an import's included. */
        private fun declarationIn(scope: CodeNode, name: String): Declaration? {
            for (statement in scope.children) {
                when (statement.type) {
                    LEXICAL_DECLARATION, VARIABLE_DECLARATION, ENUM_DECLARATION ->
                        declared(statement, name, scope, exported = false)?.let { return it }
                    EXPORT_STATEMENT -> statement.children.firstOrNull { it.type in DECLARATIONS }
                        ?.let { declared(it, name, scope, exported = true) }?.let { return it }
                    IMPORT_STATEMENT -> imported(statement, name)?.let { return it }
                }
            }
            return null
        }

        /** What `import { [name] } from '…'` or `import { x as [name] } from '…'` names, through [imports]. */
        private fun imported(statement: CodeNode, name: String): Declaration? {
            if (statement.children.any { it.text == TYPE }) return null
            val source = statement.children.firstOrNull { it.type == STRING }?.text?.trim('\'', '"') ?: return null
            val specifiers = statement.children.firstOrNull { it.type == IMPORT_CLAUSE }
                ?.children?.firstOrNull { it.type == NAMED_IMPORTS }
                ?.children?.filter { it.type == IMPORT_SPECIFIER }.orEmpty()
            val specifier = specifiers.firstOrNull { localNameOf(it) == name } ?: return null
            val imported = specifier.children.firstOrNull()?.text ?: return null
            return imports.declaration(root.file, source, imported)
        }
    }

    /** The declaration [name] has among the statements of [root], a file another imports from: exported and not re-exported. */
    private fun exportedDeclaration(root: CodeNode, name: String): Declaration? {
        for (statement in root.children.filter { it.type == EXPORT_STATEMENT }) {
            statement.children.firstOrNull { it.type in DECLARATIONS }?.let { declared(it, name, root, exported = true) }?.let { return it }
            // `export { KEYS }`, `export { KEYS as K }`: a name of this file; with a `from`, a re-export.
            if (statement.children.any { it.type == STRING }) continue
            val local = statement.children.firstOrNull { it.type == EXPORT_CLAUSE }
                ?.children?.filter { it.type == EXPORT_SPECIFIER }
                ?.firstOrNull { localNameOf(it) == name }
                ?.children?.firstOrNull()?.text ?: continue
            return root.children.firstNotNullOfOrNull { declared(it, local, root, exported = true) }
        }
        return null
    }

    /** The declaration of [name] [statement] makes in [scope], a `lexical_declaration`, `variable_declaration` or `enum_declaration`. */
    private fun declared(statement: CodeNode, name: String, scope: CodeNode, exported: Boolean): Declaration? = when (statement.type) {
        ENUM_DECLARATION -> statement.takeIf { it.children.any { child -> child.type == IDENTIFIER && child.text == name } }
            ?.children?.firstOrNull { it.type == ENUM_BODY }?.let(Declaration::Enum)
        LEXICAL_DECLARATION, VARIABLE_DECLARATION -> {
            val declarator = statement.children.firstOrNull { it.type == VARIABLE_DECLARATOR && it.children.firstOrNull()?.let { id -> id.type == IDENTIFIER && id.text == name } == true }
            declarator?.let {
                val isConst = statement.children.firstOrNull()?.text == CONST
                val value = it.children.drop(1).lastOrNull()?.takeIf { last -> last.text != "=" && last.type != TYPE_ANNOTATION }
                Declaration.Variable(value, isConst, isConst || (!exported && neverAssigned(scope, name)))
            }
        }
        else -> null
    }

    /** True when no code under [scope] assigns [name]: `name = …`, `name += …`, `name++`. */
    private fun neverAssigned(scope: CodeNode, name: String): Boolean {
        val pending = ArrayDeque(listOf(scope))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val assigned = when (node.type) {
                ASSIGNMENT_EXPRESSION, AUGMENTED_ASSIGNMENT_EXPRESSION -> node.children.firstOrNull()
                UPDATE_EXPRESSION -> node.children.firstOrNull { it.type == IDENTIFIER }
                else -> null
            }
            if (assigned?.type == IDENTIFIER && assigned.text == name) return false
            pending.addAll(node.children)
        }
        return true
    }

    /** `KEYS.actions.save` → `KEYS` and `[actions, save]`; null for anything but names and dots. */
    private fun referenceOf(node: CodeNode): Pair<String, List<String>>? = when (node.type) {
        IDENTIFIER -> node.text to emptyList()
        MEMBER_EXPRESSION -> {
            val parts = node.children.filter { it.text != "." }
            val property = parts.getOrNull(1)?.takeIf { parts.size == 2 && it.type == PROPERTY_IDENTIFIER }
            property?.let { referenceOf(parts[0])?.let { (name, path) -> name to path + it.text } }
        }
        else -> null
    }

    /** The names [function] takes as parameters, a destructured one's included. */
    private fun parametersOf(function: CodeNode): Set<String> {
        // `key => t(key)`: a single parameter written without parentheses.
        function.children.firstOrNull()?.takeIf { it.type == IDENTIFIER }?.let { return setOf(it.text) }
        val parameters = function.children.firstOrNull { it.type == FORMAL_PARAMETERS } ?: return emptySet()
        val names = mutableSetOf<String>()
        val pending = ArrayDeque(parameters.children)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            when (node.type) {
                IDENTIFIER, SHORTHAND_PATTERN -> names += node.text
                // A default value or a type names no parameter: only what comes before them does.
                TYPE_ANNOTATION -> {}
                REQUIRED_PARAMETER, OPTIONAL_PARAMETER, ASSIGNMENT_PATTERN -> node.children.firstOrNull()?.let { pending.addFirst(it) }
                else -> pending.addAll(node.children)
            }
        }
        return names
    }

    /** The string literals [expression] is made of, through parentheses, `as` and conditionals; null when a part is something else. */
    private fun literalsIn(expression: CodeNode?): List<CodeNode>? {
        val node = expression?.let(::unwrapped) ?: return null
        return when (node.type) {
            TERNARY_EXPRESSION -> {
                val branches = node.children.filter { it.text != "?" && it.text != ":" }.drop(1)
                if (branches.size != 2) return null
                val then = literalsIn(branches[0]) ?: return null
                val otherwise = literalsIn(branches[1]) ?: return null
                then + otherwise
            }
            else -> singleLiteral(node)
        }
    }

    /** [node] when it is a quoted string or a template without `${…}`, as one literal; null otherwise. */
    private fun singleLiteral(node: CodeNode): List<CodeNode>? = when {
        node.type == STRING && node.text.firstOrNull().let { it == '\'' || it == '"' } -> listOf(node)
        node.type == TEMPLATE_STRING && node.children.none { it.type == TEMPLATE_SUBSTITUTION } -> listOf(node)
        else -> null
    }

    /** [literals] as an argument expression: the one literal, or a conditional over them all; null when one cannot be written as a quoted string. */
    private fun expressionOf(literals: List<CodeNode>): String? {
        val written = literals.map { quoted(it) ?: return null }
        // `(0 ? 'a' : 0 ? 'b' : 'c')`: no extractor reads a conditional's condition.
        return written.singleOrNull() ?: "(" + written.dropLast(1).foldRight(written.last()) { literal, rest -> "0 ? $literal : $rest" } + ")"
    }

    /** A literal as a quoted string, as written: a template's text between quotes it does not hold. */
    private fun quoted(literal: CodeNode): String? {
        if (literal.type == STRING) return literal.text
        val content = literal.text.removeSurrounding("`")
        if ('\n' in content || '\\' in content) return null
        return when {
            '\'' !in content -> "'$content'"
            '"' !in content -> "\"$content\""
            else -> null
        }
    }

    /** [node] without the parentheses, `as …` and `satisfies …` around it. */
    private fun unwrapped(node: CodeNode): CodeNode = when (node.type) {
        PARENTHESIZED_EXPRESSION -> node.children.firstOrNull { it.text != "(" && it.text != ")" }?.let(::unwrapped) ?: node
        AS_EXPRESSION, SATISFIES_EXPRESSION -> node.children.firstOrNull()?.let(::unwrapped) ?: node
        else -> node
    }

    /** The value of the property [name] of [obj], an object literal; null when it has none. */
    private fun propertyValue(obj: CodeNode, name: String): CodeNode? =
        obj.children.firstOrNull { it.type == PAIR && it.children.firstOrNull()?.let(::nameOf) == name }?.children?.lastOrNull()

    /** A property's or a member's name: an identifier, or a quoted string's text. */
    private fun nameOf(key: CodeNode): String? = when (key.type) {
        PROPERTY_IDENTIFIER, IDENTIFIER -> key.text
        STRING -> key.text.trim('\'', '"')
        else -> null
    }

    /** The name an import or export specifier binds: its alias, or the name itself. */
    private fun localNameOf(specifier: CodeNode): String? = specifier.children.lastOrNull { it.type == IDENTIFIER }?.text

    /** The items of an argument list, without their separators. */
    private fun items(node: CodeNode): List<CodeNode> = node.children.filter { it.text != "(" && it.text != ")" && it.text != "," && it.type != COMMENT }

    /**
     * The calls whose first argument names a namespace, not a key: `useTranslation(NS)`. Left as written, as
     * the plugin reads a hook's namespace from a literal only.
     */
    private val NAMESPACE_CALLS: Set<String> = FrameworkPresets.ALL.flatMap { rules -> rules.hooks.map { it.name } }.toSet() + "withTranslation"

    private const val CALL_EXPRESSION = "call_expression"
    private const val ARGUMENTS = "arguments"
    private const val IDENTIFIER = "identifier"
    private const val MEMBER_EXPRESSION = "member_expression"
    private const val PROPERTY_IDENTIFIER = "property_identifier"
    private const val PROGRAM = "program"
    private const val STATEMENT_BLOCK = "statement_block"
    private const val FOR_IN_STATEMENT = "for_in_statement"
    private const val LEXICAL_DECLARATION = "lexical_declaration"
    private const val VARIABLE_DECLARATION = "variable_declaration"
    private const val VARIABLE_DECLARATOR = "variable_declarator"
    private const val ENUM_DECLARATION = "enum_declaration"
    private const val ENUM_BODY = "enum_body"
    private const val ENUM_ASSIGNMENT = "enum_assignment"
    private const val EXPORT_STATEMENT = "export_statement"
    private const val EXPORT_CLAUSE = "export_clause"
    private const val EXPORT_SPECIFIER = "export_specifier"
    private const val IMPORT_STATEMENT = "import_statement"
    private const val IMPORT_CLAUSE = "import_clause"
    private const val NAMED_IMPORTS = "named_imports"
    private const val IMPORT_SPECIFIER = "import_specifier"
    private const val FORMAL_PARAMETERS = "formal_parameters"
    private const val ASSIGNMENT_PATTERN = "assignment_pattern"
    private const val REQUIRED_PARAMETER = "required_parameter"
    private const val OPTIONAL_PARAMETER = "optional_parameter"
    private const val SHORTHAND_PATTERN = "shorthand_property_identifier_pattern"
    private const val TYPE_ANNOTATION = "type_annotation"
    private const val ASSIGNMENT_EXPRESSION = "assignment_expression"
    private const val AUGMENTED_ASSIGNMENT_EXPRESSION = "augmented_assignment_expression"
    private const val UPDATE_EXPRESSION = "update_expression"
    private const val TERNARY_EXPRESSION = "ternary_expression"
    private const val PARENTHESIZED_EXPRESSION = "parenthesized_expression"
    private const val AS_EXPRESSION = "as_expression"
    private const val SATISFIES_EXPRESSION = "satisfies_expression"
    private const val OBJECT = "object"
    private const val PAIR = "pair"
    private const val STRING = "string"
    private const val TEMPLATE_STRING = "template_string"
    private const val TEMPLATE_SUBSTITUTION = "template_substitution"
    private const val COMMENT = "comment"
    private const val CONST = "const"
    private const val TYPE = "type"

    private val DECLARATIONS = setOf(LEXICAL_DECLARATION, VARIABLE_DECLARATION, ENUM_DECLARATION)
    private val FUNCTIONS = setOf(
        "arrow_function", "function_declaration", "function_expression", "method_definition",
        "generator_function_declaration", "generator_function",
    )
}
