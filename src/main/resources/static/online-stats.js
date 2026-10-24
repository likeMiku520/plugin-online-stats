/* Online-stats tracker.
 * Reports a heartbeat with the current page and, on article pages, the post name so
 * the plugin can attribute views to a specific article. It renders nothing.
 * The server injects window.__ONLINE_STATS__ (heartbeatInterval) before this body runs.
 */
(function () {
  'use strict';

  var cfg = window.__ONLINE_STATS__ || {};
  var apiBase = location.origin + '/apis/online-stats.halo.run/v1alpha1/';
  var interval = cfg.heartbeatInterval > 0 ? cfg.heartbeatInterval : 15;

  var vid = sessionStorage.getItem('os_vid');
  if (!vid) {
    vid = 'v-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
    try {
      sessionStorage.setItem('os_vid', vid);
    } catch (e) {
      /* storage unavailable, ignore */
    }
  }

  function text(value) {
    if (value === undefined || value === null) {
      return '';
    }
    return String(value);
  }

  /* The server writes the post name into a meta tag on article pages. Themes that strip
   it can still be matched by URL on the server, so the tag is optional. */
  function postName() {
    var meta = document.querySelector('meta[name="halo-post-name"]');
    if (meta && meta.content) {
      return meta.content;
    }
    var article = document.querySelector('[data-post-name]');
    if (article) {
      return article.getAttribute('data-post-name');
    }
    var body = document.body;
    if (body && body.dataset && body.dataset.postName) {
      return body.dataset.postName;
    }
    return '';
  }

  function timezone() {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || '';
    } catch (e) {
      return '';
    }
  }

  function connectionInfo() {
    var c = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
    if (!c) {
      return '';
    }
    return [c.effectiveType, c.downlink ? c.downlink + 'Mbps' : '', c.rtt ? c.rtt + 'ms' : '']
      .filter(Boolean)
      .join(' / ');
  }

  function languages() {
    var list = navigator.languages && navigator.languages.length
      ? navigator.languages : [navigator.language];
    return list.filter(Boolean).join(',');
  }

  function payload() {
    return {
      visitorId: vid,
      postName: postName(),
      url: location.pathname + location.search,
      title: document.title,
      referer: document.referrer,
      screen: screen.width + 'x' + screen.height,
      viewport: window.innerWidth + 'x' + window.innerHeight,
      timezone: timezone(),
      language: languages(),
      platform: text(navigator.platform),
      cores: text(navigator.hardwareConcurrency),
      memory: navigator.deviceMemory ? navigator.deviceMemory + 'GB' : '',
      connection: connectionInfo()
    };
  }

  function heartbeat() {
    var body;
    try {
      body = new Blob([JSON.stringify(payload())], { type: 'application/json' });
    } catch (e) {
      return;
    }
    if (navigator.sendBeacon) {
      navigator.sendBeacon(apiBase + 'heartbeat', body);
      return;
    }
    fetch(apiBase + 'heartbeat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload()),
      keepalive: true
    }).catch(function () {
      /* ignore */
    });
  }

  /* SPA / pjax themes change the page without a reload, so report right away. */
  function hookHistory() {
    if (!window.history || !history.pushState) {
      return;
    }
    ['pushState', 'replaceState'].forEach(function (method) {
      var original = history[method];
      if (typeof original !== 'function') {
        return;
      }
      history[method] = function () {
        var result = original.apply(this, arguments);
        window.setTimeout(heartbeat, 300);
        return result;
      };
    });
    window.addEventListener('popstate', function () {
      window.setTimeout(heartbeat, 300);
    });
  }

  heartbeat();
  hookHistory();
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) {
      heartbeat();
    }
  });
  window.setInterval(heartbeat, interval * 1000);
})();