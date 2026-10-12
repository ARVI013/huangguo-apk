/* 黄果短剧 APK — in-page ad removal + mobile polish.
   Injected at document-start (rewritten HTML) and re-run as a fallback via evaluateJavascript.
   Idempotent: guarded by window.__HG_APP__. */
(function () {
  'use strict';
  if (window.__HG_APP__) return;
  window.__HG_APP__ = true;

  var ALLOW = [
    'huangguoai.com', 'hgai2.com', 'huangguoai.ai', 'huangguoai.pages.dev',
    'pqhybuxo.cc', 'wirqed.cn'
  ];

  function hostOf(href) {
    try { return new URL(href, location.href).hostname.toLowerCase(); } catch (e) { return ''; }
  }
  function allowedHost(h) {
    if (!h) return false;
    for (var i = 0; i < ALLOW.length; i++) {
      var d = ALLOW[i];
      if (h === d || h.slice(-d.length - 1) === '.' + d) return true;
    }
    return false;
  }

  /* ---------- 1. hero carousel: drop ad slides before the site reads them ---------- */
  function cleanHero(node) {
    if (!node || node.getAttribute('data-hg-cleaned')) return;
    try {
      var arr = JSON.parse(node.textContent || '[]');
      if (!Array.isArray(arr)) return;
      var keep = [];
      for (var i = 0; i < arr.length; i++) {
        if (arr[i] && arr[i].isAd === true) continue;
        keep.push(arr[i]);
      }
      if (keep.length && keep.length !== arr.length) {
        node.textContent = JSON.stringify(keep);
        node.setAttribute('data-hg-cleaned', '1');
      } else {
        node.setAttribute('data-hg-cleaned', '1');
      }
    } catch (e) { /* not JSON yet — leave it */ }
  }

  /* ---------- 2. remove ad / promo nodes (also catches late JS-injected ads) ---------- */
  var AD_SELECTORS = [
    '.hg-ssp-slot',                 /* on-site banner ad slots */
    '[aria-label="推广"]',          /* same slots, accessible name */
    '[data-ssp-slot-key]',
    '[data-hero-adbadge]',
    '.hg-hero__ad-badge',
    '.hg-fab__btn--download',       /* "download our APP" floating button */
    'a[data-menu-id="1"]',          /* "黄果短剧APP" promo link in nav / drawer */
    '.hg-footer__addrs'             /* mirror-address block (app does this natively) */
  ].join(',');

  function removeOffsiteAnchors(root) {
    var links = root.querySelectorAll('a[href]');
    for (var j = 0; j < links.length; j++) {
      var a = links[j];
      if (!document.contains(a)) continue;
      var href = a.getAttribute('href') || '';
      if (/^(mailto:|tel:|javascript:|data:|#)/i.test(href)) continue;
      var h = hostOf(href);
      if (!h || allowedHost(h)) continue;
      /* The hero cover link is owned by the carousel script: neutralise it in place
         instead of removing it, so the carousel keeps working. */
      if (a.hasAttribute('data-hero-cover') || a.hasAttribute('data-hero-ad-id')) {
        a.setAttribute('href', '#');
        a.setAttribute('data-hg-ad-neutralized', '1');
        continue;
      }
      /* keep the text, drop the ad link + its tracking */
      var span = document.createElement('span');
      span.className = 'hg-unlinked';
      span.textContent = a.textContent || '';
      if (a.parentNode) a.parentNode.replaceChild(span, a);
    }
  }

  function sweep() {
    try {
      var nodes = document.querySelectorAll(AD_SELECTORS);
      for (var i = 0; i < nodes.length; i++) {
        if (nodes[i].parentNode) nodes[i].parentNode.removeChild(nodes[i]);
      }
      /* announcement ticker: drop only the items that advertise an off-site product */
      var items = document.querySelectorAll('.hg-announce__item');
      for (var k = 0; k < items.length; k++) {
        var a = items[k].tagName === 'A' ? items[k] : items[k].querySelector('a[href]');
        if (!a) continue;
        var h = hostOf(a.getAttribute('href'));
        if (h && !allowedHost(h) && items[k].parentNode) items[k].parentNode.removeChild(items[k]);
      }
      removeOffsiteAnchors(document);
    } catch (e) { /* ignore */ }
  }

  var timer = 0;
  function scheduleSweep() {
    if (timer) return;
    timer = setTimeout(function () { timer = 0; sweep(); }, 40);
  }

  var pre = document.querySelector('script[data-hero-slides]');
  if (pre) cleanHero(pre);

  if (document.documentElement) {
    document.documentElement.classList.add('hg-app');
    var mo = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n.nodeType === 1 && n.tagName === 'SCRIPT' && n.hasAttribute && n.hasAttribute('data-hero-slides')) {
            cleanHero(n);
          }
        }
      }
      scheduleSweep();
    });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  }

  sweep();
  document.addEventListener('DOMContentLoaded', function () {
    sweep();
    setTimeout(sweep, 600);
    setTimeout(sweep, 2000);
  });
  window.addEventListener('load', function () { sweep(); setTimeout(sweep, 1500); });
})();
