// Assembles the npm packages of a version: `i18n-support-plus`, whose optional dependencies are the
// platform packages at that same version, and `i18n-support-plus-<platform>` for each binary built.
//
// usage: node npm/assemble.mjs <version> <binaries> <out> [--all]
//   <binaries> holds a folder `i18n-check-<platform>` per platform built, the binary in it, as
//   actions/download-artifact lays the CI's artifacts out. --all fails on a platform with no binary:
//   a release publishes every platform the launcher points to, or none.
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const [version, binaries, out, flag] = process.argv.slice(2);
if (!version || !binaries || !out) {
  console.error('usage: node npm/assemble.mjs <version> <binaries> <out> [--all]');
  process.exit(2);
}
const all = flag === '--all';

const npm = dirname(fileURLToPath(import.meta.url));
const main = join(npm, 'i18n-support-plus');
const license = join(npm, '..', 'LICENSE');
const platforms = JSON.parse(readFileSync(join(main, 'platforms.json'), 'utf8'));
const manifest = JSON.parse(readFileSync(join(main, 'package.json'), 'utf8'));

rmSync(out, { recursive: true, force: true });

for (const [key, platform] of Object.entries(platforms)) {
  const name = `${manifest.name}-${key}`;
  const binary = join(binaries, `i18n-check-${key}`, platform.binary);
  if (!existsSync(binary)) {
    if (all) throw new Error(`no binary for ${key}: ${binary}`);
    continue;
  }
  const dir = join(out, name);
  mkdirSync(join(dir, 'bin'), { recursive: true });
  copyFileSync(binary, join(dir, 'bin', platform.binary));
  // An artifact download loses the mode; npm packs the one the file has.
  chmodSync(join(dir, 'bin', platform.binary), 0o755);
  copyFileSync(license, join(dir, 'LICENSE'));
  writeFileSync(join(dir, 'package.json'), JSON.stringify({
    name,
    version,
    description: `The ${key} binary of i18n-support-plus, installed by it: depend on i18n-support-plus instead`,
    homepage: manifest.homepage,
    repository: { ...manifest.repository, directory: 'npm' },
    license: manifest.license,
    os: [platform.os],
    cpu: [platform.cpu],
    ...(platform.libc && { libc: [platform.libc] }),
    files: ['bin'],
  }, null, 2) + '\n');
  writeFileSync(join(dir, 'README.md'),
    `# ${name}\n\nThe ${key} binary of [i18n-support-plus](https://www.npmjs.com/package/i18n-support-plus), ` +
    'which installs it as an optional dependency. Use `i18n-support-plus` rather than this package.\n');
}

const dir = join(out, manifest.name);
mkdirSync(join(dir, 'bin'), { recursive: true });
copyFileSync(join(main, 'bin', 'i18n-support-plus.js'), join(dir, 'bin', 'i18n-support-plus.js'));
chmodSync(join(dir, 'bin', 'i18n-support-plus.js'), 0o755);
for (const file of ['platforms.json', 'README.md']) copyFileSync(join(main, file), join(dir, file));
copyFileSync(license, join(dir, 'LICENSE'));
const optionalDependencies = Object.fromEntries(Object.keys(platforms).map((key) => [`${manifest.name}-${key}`, version]));
writeFileSync(join(dir, 'package.json'), JSON.stringify({ ...manifest, version, optionalDependencies }, null, 2) + '\n');
