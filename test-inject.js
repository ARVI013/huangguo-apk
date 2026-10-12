// Validates the injected script: syntax, and the hero-JSON cleaning against the real page data.
const fs = require('fs');
const path = require('path');

const root = 'D:\\OneDrive\\Desktop\\DSH Work\\huangguo-apk';
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

// 1. syntax check of the injected JS (mirrors `node --check`)
const js = read('app/assets/hg-inject.js');
try {
  new Function(js);
  console.log('JS syntax: OK');
} catch (e) {
  console.log('JS syntax: FAIL -> ' + e.message);
  process.exit(1);
}

// 2. ES5-only check: the injected script must not use post-ES5 syntax (old System WebView)
const banned = [
  ['=>', 'arrow function'],
  [/\blet\s/, 'let'],
  [/\bconst\s/, 'const'],
  [/\bclass\s/, 'class'],
  [/`/, 'template literal'],
  [/\?\./, 'optional chaining'],
  [/\.\.\./, 'spread'],
  [/\bglobalThis\b/, 'globalThis'],
  [/\breplaceAll\b/, 'String.replaceAll'],
  [/\bisConnected\b/, 'Node.isConnected'],
  [/\bArray\.prototype\.at\b|\]\.at\(/, 'Array.at'],
];
for (const [pat, name] of banned) {
  const re = (pat instanceof RegExp) ? pat : new RegExp(pat.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  if (re.test(js)) console.log('ES5 check: found ' + name);
}
console.log('ES5 check: done');

// 3. run cleanHero against the real data-hero-slides JSON from the captured page
const html = read('probe/home.html');
const m = html.match(/<script type="application\/json" data-hero-slides>([\s\S]*?)<\/script>/);
if (!m) {
  console.log('hero JSON: NOT FOUND in probe/home.html');
  process.exit(1);
}
const arr = JSON.parse(m[1]);
const ads = arr.filter((x) => x && x.isAd === true);
const keep = arr.filter((x) => !(x && x.isAd === true));
console.log('hero slides total=' + arr.length + ' ads=' + ads.length + ' kept=' + keep.length);
for (const a of ads) console.log('  dropped ad: ' + JSON.stringify({ title: a.title, adId: a.adId, href: a.href, linkUrl: a.linkUrl }));
for (const k of keep.slice(0, 3)) console.log('  kept: ' + JSON.stringify({ title: k.title, adId: k.adId, href: k.href }));
if (keep.length === 0) {
  console.log('hero JSON: WARNING cleaning removes every slide');
}

// 4. confirm the ad DOM targets really exist in the captured page (so the selectors are not guesses)
const probes = [
  ['hg-ssp-slot', /class="[^"]*hg-ssp-slot/],
  ['aria-label="推广"', /aria-label="推广"/],
  ['data-ssp-slot-key', /data-ssp-slot-key=/],
  ['data-hero-adbadge', /data-hero-adbadge/],
  ['hg-fab__btn--download', /hg-fab__btn--download/],
  ['a[data-menu-id="1"]', /<a[^>]*data-menu-id="1"/],
  ['hg-footer__addrs', /hg-footer__addrs/],
  ['data-hero-cover', /data-hero-cover/],
  ['hg-announce__item', /hg-announce__item/],
];
for (const [name, re] of probes) {
  const n = (html.match(new RegExp(re.source, 'g')) || []).length;
  console.log('selector ' + name + ': ' + (n > 0 ? n + ' occurrence(s) in page' : 'NOT PRESENT'));
}

// 5. off-site link hosts present in the page (what removeOffsiteAnchors would touch)
const hosts = new Set();
for (const mm of html.matchAll(/<a[^>]+href="(https?:\/\/[^"\/]+)/g)) {
  try {
    const h = new URL(mm[1]).hostname;
    hosts.add(h);
  } catch (e) {}
}
console.log('external anchor hosts: ' + Array.from(hosts).join(', '));
