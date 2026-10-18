import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, mkdirSync, mkdtempSync, writeFileSync, copyFileSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const ops = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspace = resolve(ops, '../../..');
const migration = resolve(workspace, '..');
const source = process.env.PROMPT_GALLERY_SOURCE || resolve(migration, 'reference/GPT-Image2-Skill');
const ragJar = process.env.PROMPT_GALLERY_RAG_JAR || resolve(migration, 'publish-backend/agent-rag-service/target/agent-rag-service-0.0.1-SNAPSHOT.jar');
const javaHome = process.env.PROMPT_GALLERY_JAVA_HOME || 'D:/jdk21';
const frontend = process.env.PROMPT_LIBRARY_FRONTEND || resolve(workspace, 'frontend');
const output = resolve(frontend, 'src/data/prompt-gallery.json');
const licenseDirectory = resolve(frontend, 'public/prompt-library/third-party');
const commit = '9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f';
const catalog = existsSync(output) ? JSON.parse(readFileSync(output, 'utf8')) : { source: {}, categories: [], prompts: [] };
const sha256 = text => createHash('sha256').update(text, 'utf8').digest('hex');
const indexPath = 'skills/gpt-image/references/gallery.md';
const index = readFileSync(resolve(source, indexPath), 'utf8');
const categoryFiles = [...new Set([...index.matchAll(/\]\((gallery-[a-z0-9-]+\.md)\)/g)].map(match => match[1]))];
const rawBodies = categoryFiles.flatMap(file => {
  const markdown = readFileSync(resolve(source, 'skills/gpt-image/references', file), 'utf8').replaceAll('\r\n', '\n');
  return [...markdown.matchAll(/^```(?:text|plaintext|prompt|json|markdown|md)?\s*\n([\s\S]*?)^```\s*$/gm)]
    .map(match => ({ category: file.slice(8, -3), content: match[1].trim() }));
});

function runExport(destination, sourceRepository = source, check = false) {
  const args = ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', resolve(ops, 'export-catalog.ps1'),
    '-SourceRepository', sourceRepository, '-OutputCatalog', destination,
    '-LicenseDirectory', join(dirname(destination), 'third-party'), '-RagJar', ragJar, '-JavaHome', javaHome];
  if (check) args.push('-Check');
  return spawnSync('powershell.exe', args, { encoding: 'utf8', timeout: 60000 });
}

function fixtureRoot() {
  const parent = resolve(ops, 'build/test-fixtures');
  mkdirSync(parent, { recursive: true });
  return mkdtempSync(join(parent, 'export-'));
}

test('full pinned gallery contains 164 prompts in all 31 categories', () => {
  assert.equal(catalog.prompts.length, 164, 'Catalog must contain the complete gallery, not a seed subset');
  assert.equal(catalog.categories.length, 31);
  assert.equal(new Set(catalog.prompts.map(prompt => prompt.title)).size, 163);
  assert.equal(catalog.prompts.filter(prompt => prompt.variant === 2).length, 1);
});

test('public catalog has the exact shared contract and translated category search terms', () => {
  assert.deepEqual(Object.keys(catalog).sort(), ['categories', 'prompts', 'source']);
  assert.deepEqual(catalog.source, {
    repository: 'https://github.com/wuyoscar/GPT-Image2-Skill', commit, license: 'MIT', attribution: 'Copyright (c) 2026 Wuyoscar',
  });
  for (const category of catalog.categories) {
    assert.deepEqual(Object.keys(category).sort(), ['id', 'keywords', 'label']);
    assert.match(category.label, /[\u3400-\u9fff]/u);
    assert.ok(category.keywords.length >= 3);
    assert.ok(category.keywords.every(keyword => typeof keyword === 'string' && keyword.length > 0));
  }
  const promptKeys = ['id', 'title', 'category', 'content', 'size', 'quality', 'tags', 'sourceFile', 'sourceRepository', 'sourceCommit', 'author', 'attribution', 'sourceAttribution', 'variant', 'contentHash'].sort();
  for (const prompt of catalog.prompts) assert.deepEqual(Object.keys(prompt).sort(), promptKeys);
});

test('every exported body retains its complete original fence including long JSON and newlines', () => {
  assert.equal(rawBodies.length, 164, 'Independent source-fence enumeration must cover the full corpus');
  assert.equal(catalog.prompts.length, rawBodies.length);
  for (const raw of rawBodies) {
    const actual = catalog.prompts.find(prompt => prompt.category === raw.category && prompt.content === raw.content);
    assert.ok(actual, `Missing or modified full prompt in ${raw.category}: ${raw.content.slice(0, 80)}`);
  }
  const jsonBodies = rawBodies.filter(raw => raw.content.startsWith('{'));
  assert.ok(jsonBodies.length > 0, 'Source includes JSON prompts');
  const salad = catalog.prompts.find(prompt => prompt.title.startsWith('No. 58 ·'));
  assert.equal(salad.content.length, 1409);
  assert.equal(salad.content.split('\n').length, 23);
  assert.equal(JSON.parse(salad.content).global_settings.resolution, '8K ultra high definition');
  const commentedJson = catalog.prompts.find(prompt => prompt.title.startsWith('No. 57 ·'));
  assert.ok(commentedJson.content.startsWith('/* PRODUCT_RENDER_CONFIG: Chocolate Wafer Hazelnut Edition\n'));
  const longest = catalog.prompts.find(prompt => prompt.title.startsWith('No. 67 ·'));
  assert.equal(longest.content.length, 2785);
  assert.equal(longest.content.split('\n').length, 41);
  assert.ok(catalog.prompts.some(prompt => prompt.content.includes('\n\n')));
});

test('stable IDs are unique and content hashes and source provenance are correct for every prompt', () => {
  assert.equal(new Set(catalog.prompts.map(prompt => prompt.id)).size, 164);
  const validCategories = new Set(catalog.categories.map(category => category.id));
  for (const prompt of catalog.prompts) {
    assert.match(prompt.id, /^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    const bytes = createHash('md5').update(`${prompt.category}\n${prompt.content}`, 'utf8').digest();
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = bytes.toString('hex');
    const expectedId = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    assert.equal(prompt.id, expectedId);
    assert.equal(prompt.contentHash, sha256(prompt.content));
    assert.equal(prompt.sourceCommit, commit);
    assert.equal(prompt.sourceRepository, catalog.source.repository);
    assert.equal(prompt.sourceFile, `skills/gpt-image/references/gallery-${prompt.category}.md`);
    assert.equal(prompt.attribution, catalog.source.attribution);
    assert.ok(validCategories.has(prompt.category));
  }
});

test('actual author, source, size and variant metadata survive and undeclared metadata stays empty', () => {
  const manga = catalog.prompts.find(prompt => prompt.title.startsWith('No. 4 ·'));
  assert.ok(manga, 'Manga relationship prompt must exist');
  assert.equal(manga.author, '@cht0001');
  assert.equal(manga.size, '1024x1536');
  assert.equal(manga.quality, '');
  assert.deepEqual(manga.tags, []);
  assert.ok(manga.sourceAttribution.includes('https://x.com/cht0001'));
  const cookbook = catalog.prompts.find(prompt => prompt.title.startsWith('No. 97 ·'));
  assert.equal(cookbook.author, 'OpenAI');
  assert.ok(cookbook.sourceAttribution.includes('openai/openai-cookbook'));
  const first = catalog.prompts.find(prompt => prompt.title.startsWith('No. 1 ·'));
  assert.equal(first.author, '');
  assert.equal(first.sourceAttribution, '');
  assert.ok(catalog.prompts.some(prompt => prompt.quality === 'high'));
  assert.ok(catalog.prompts.every(prompt => Array.isArray(prompt.tags) && prompt.tags.length === 0), 'No source tags are declared; editorial Chinese search aliases belong only to categories');
});

test('complete upstream MIT license and source notice are distributed without upstream images', () => {
  const original = readFileSync(resolve(source, 'LICENSE'));
  assert.deepEqual(readFileSync(resolve(licenseDirectory, 'GPT-Image2-Skill-LICENSE.txt')), original);
  const notice = readFileSync(resolve(licenseDirectory, 'NOTICE.txt'), 'utf8');
  assert.ok(notice.includes(commit));
  assert.ok(notice.includes('https://github.com/wuyoscar/GPT-Image2-Skill'));
  assert.ok(notice.includes('164'));
});

test('offline exporter is deterministic and check mode verifies the committed artifact', () => {
  const fixture = fixtureRoot();
  const destination = join(fixture, 'catalog.json');
  const first = runExport(destination);
  assert.equal(first.status, 0, first.stdout + first.stderr);
  assert.equal(first.stdout.includes('Started '), false, 'Must not start a Spring application');
  assert.deepEqual(readFileSync(destination), readFileSync(output));
  const second = runExport(destination, source, true);
  assert.equal(second.status, 0, second.stdout + second.stderr);
  assert.match(second.stdout, /164.*31|31.*164/);
});

test('export refuses a checkout whose HEAD differs from the pinned source commit', () => {
  const fixture = fixtureRoot();
  const badSource = join(fixture, 'wrong-source');
  mkdirSync(join(badSource, '.git'), { recursive: true });
  writeFileSync(join(badSource, '.git/HEAD'), '0'.repeat(40) + '\n');
  const result = runExport(join(fixture, 'catalog.json'), badSource);
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /commit|checkout|pinned/i);
  assert.equal(existsSync(join(fixture, 'catalog.json')), false);
});

test('export refuses locally modified files attributed to the pinned source commit', () => {
  const fixture = fixtureRoot();
  const badSource = join(fixture, 'modified-source');
  mkdirSync(join(badSource, '.git'), { recursive: true });
  mkdirSync(join(badSource, 'skills/gpt-image/references'), { recursive: true });
  writeFileSync(join(badSource, '.git/HEAD'), commit + '\n');
  copyFileSync(resolve(source, 'LICENSE'), join(badSource, 'LICENSE'));
  copyFileSync(resolve(source, indexPath), join(badSource, indexPath));
  for (const file of categoryFiles) copyFileSync(resolve(source, 'skills/gpt-image/references', file), join(badSource, 'skills/gpt-image/references', file));
  const changed = join(badSource, 'skills/gpt-image/references', categoryFiles[0]);
  writeFileSync(changed, readFileSync(changed, 'utf8').replace('silver-white-haired', 'truncated'));
  const result = runExport(join(fixture, 'catalog.json'), badSource);
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /source.*hash|modified|fingerprint/i);
  assert.equal(existsSync(join(fixture, 'catalog.json')), false);
});

test('check mode catches duplicate IDs, altered body hashes and forged source commits without rewriting', () => {
  for (const mutation of [
    data => { data.prompts[1].id = data.prompts[0].id; },
    data => { data.prompts[0].content += '\nchanged'; },
    data => { data.prompts[0].sourceCommit = '0'.repeat(40); },
  ]) {
    const fixture = fixtureRoot();
    const destination = join(fixture, 'catalog.json');
    const data = structuredClone(catalog);
    assert.equal(data.prompts.length, 164);
    mutation(data);
    const corrupted = JSON.stringify(data);
    writeFileSync(destination, corrupted);
    const result = runExport(destination, source, true);
    assert.notEqual(result.status, 0);
    assert.match(result.stdout + result.stderr, /differs|check|catalog/i);
    assert.equal(readFileSync(destination, 'utf8'), corrupted);
  }
});
