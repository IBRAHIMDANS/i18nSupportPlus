#!/usr/bin/env node
// Runs the native i18n-check of this machine, installed by npm as the one optional dependency whose
// `os`, `cpu` and `libc` match it, as esbuild does: no postinstall script, no download, no Java.
'use strict';

const { spawnSync } = require('node:child_process');
const platforms = require('../platforms.json');

// The exit code i18n-check gives when it cannot check, which a missing binary is too.
const CANNOT_CHECK = 2;
const DOCS = 'https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/docs/checks.md';

function fail(message) {
  console.error(`i18n-support-plus: ${message}`);
  console.error(`The zip of each GitHub release runs on Java 21 anywhere, and the Docker image on Linux: ${DOCS}`);
  process.exit(CANNOT_CHECK);
}

// Node reports the glibc it runs on, and none under musl (Alpine).
function isMusl() {
  if (process.platform !== 'linux') return false;
  const report = process.report && process.report.getReport();
  return !(report && report.header && report.header.glibcVersionRuntime);
}

const key = `${process.platform}-${process.arch}${isMusl() ? '-musl' : ''}`;
const platform = platforms[key];
if (!platform) fail(`no native i18n-check for ${key}; there is one for ${Object.keys(platforms).join(', ')}.`);

let binary;
try {
  binary = require.resolve(`i18n-support-plus-${key}/bin/${platform.binary}`);
} catch {
  fail(`the package i18n-support-plus-${key} is not installed: it is an optional dependency, which ` +
    '--no-optional, --omit=optional or a lockfile made on another platform leaves out.');
}

const result = spawnSync(binary, process.argv.slice(2), { stdio: 'inherit' });
if (result.error) fail(`${binary}: ${result.error.message}`);
// A binary killed by a signal: the same signal ends this process, as a shell would report it.
if (result.signal) process.kill(process.pid, result.signal);
process.exitCode = result.status;
