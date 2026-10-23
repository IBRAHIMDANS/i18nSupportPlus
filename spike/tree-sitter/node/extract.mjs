// Spike: extract i18next keys from TSX files with web-tree-sitter, the way the plugin's PSI
// extractors produce a RawKey (key elements, namespaces, key prefix).
//
// Covered: t('ns:a.b'); `const { t } = useTranslation('ns' | ['ns1', 'ns2'], { keyPrefix })` then
// t('a'); an explicit t(key, { ns }) option; i18n.t('…'); template literals t(`a.${b}`);
// <Trans i18nKey="…">. Not covered: aliases (`const { t: tr }`), t passed as a prop, keys in
// variables, Vue/Svelte/Angular templates, injected languages.
//
// Usage: node extract.mjs <file.tsx>...   → one JSON line per key found.

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { Language, Parser } from 'web-tree-sitter';

const require = createRequire(import.meta.url);
const HOOK = 'useTranslation';
const FUNCTIONS = new Set(['t', 'i18n.t']);
const FUNCTION_LIKE = new Set([
  'arrow_function', 'function_declaration', 'function_expression', 'method_definition', 'program',
]);

await Parser.init();
const tsx = await Language.load(require.resolve('tree-sitter-typescript/tree-sitter-tsx.wasm'));
const parser = new Parser();
parser.setLanguage(tsx);

/** The text of a string literal without its quotes, null for anything else. */
function stringValue(node) {
  if (node?.type !== 'string') return null;
  return node.namedChildren.filter((c) => c.type === 'string_fragment').map((c) => c.text).join('');
}

/** A string, or the strings of an array literal: `'ns'` → [ns], `['a', 'b']` → [a, b]. */
function strings(node) {
  if (!node) return [];
  if (node.type === 'string') return [stringValue(node)];
  if (node.type === 'array') return node.namedChildren.map(stringValue).filter((s) => s !== null);
  return [];
}

/** The string value of property [name] in an object literal argument. */
function option(objectNode, name) {
  if (objectNode?.type !== 'object') return null;
  for (const pair of objectNode.namedChildren.filter((c) => c.type === 'pair')) {
    const key = pair.childForFieldName('key');
    if (key?.text === name || stringValue(key) === name) return pair.childForFieldName('value');
  }
  return null;
}

/** Key elements of the first argument: a literal, or a template literal split on `${…}`. */
function keyElements(node) {
  if (node?.type === 'string') return [{ text: stringValue(node), type: 'LITERAL' }];
  if (node?.type === 'template_string') {
    return node.namedChildren.map((part) => part.type === 'template_substitution'
      ? { text: part.text.slice(2, -1), type: 'TEMPLATE' }
      : { text: part.text, type: 'LITERAL' });
  }
  return null;
}

/**
 * The `useTranslation(…)` call that binds [name] in the nearest enclosing function, walking out
 * through the enclosing scopes — what TranslationHookInScope does with the PSI.
 */
function hookFor(node, name) {
  for (let scope = node.parent; scope; scope = scope.parent) {
    if (!FUNCTION_LIKE.has(scope.type)) continue;
    for (const declarator of scope.descendantsOfType('variable_declarator')) {
      // Node objects are re-created on each access: compare their ids, not their references.
      if (enclosingFunction(declarator)?.id !== scope.id) continue;
      const value = declarator.childForFieldName('value');
      const target = declarator.childForFieldName('name');
      if (value?.type !== 'call_expression' || value.childForFieldName('function')?.text !== HOOK) continue;
      const bound = target?.type === 'object_pattern'
        && target.namedChildren.some((p) => p.text === name);
      if (bound) return value;
    }
  }
  return null;
}

function enclosingFunction(node) {
  for (let p = node.parent; p; p = p.parent) if (FUNCTION_LIKE.has(p.type)) return p;
  return null;
}

function extract(file) {
  const source = readFileSync(file, 'utf8');
  const tree = parser.parse(source);
  const keys = [];

  for (const call of tree.rootNode.descendantsOfType('call_expression')) {
    const callee = call.childForFieldName('function')?.text;
    if (!FUNCTIONS.has(callee)) continue;
    const args = call.childForFieldName('arguments')?.namedChildren ?? [];
    const elements = keyElements(args[0]);
    if (!elements) continue;

    const hook = callee === 't' ? hookFor(call, 't') : null;
    const hookArgs = hook?.childForFieldName('arguments')?.namedChildren ?? [];
    const optionNs = strings(option(args[1], 'ns'));
    keys.push({
      line: call.startPosition.row + 1,
      key: elements,
      namespaces: optionNs.length ? optionNs : strings(hookArgs[0]),
      prefix: stringValue(option(hookArgs[1], 'keyPrefix')),
    });
  }

  for (const attribute of tree.rootNode.descendantsOfType('jsx_attribute')) {
    const [name, value] = attribute.namedChildren;
    if (name?.text !== 'i18nKey') continue;
    const tag = attribute.parent?.childForFieldName('name')?.text;
    if (tag !== 'Trans') continue;
    keys.push({
      line: attribute.startPosition.row + 1,
      key: keyElements(value) ?? [{ text: value?.text, type: 'EXPRESSION' }],
      namespaces: [],
      prefix: null,
    });
  }

  return { file, errors: tree.rootNode.hasError, keys: keys.sort((a, b) => a.line - b.line) };
}

for (const file of process.argv.slice(2)) {
  const started = performance.now();
  const result = extract(file);
  result.millis = Math.round((performance.now() - started) * 10) / 10;
  console.log(JSON.stringify(result));
}
