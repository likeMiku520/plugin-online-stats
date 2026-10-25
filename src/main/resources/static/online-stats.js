/* Online-stats tracker.
 * Reports a heartbeat with the current page and, on article pages, the post name so
 * the plugin can attribute views to a specific article. It also polls for messages the
 * console wrote to this address and renders them in a small reply card.
 * The server injects window.__ONLINE_STATS__ before this body runs.
 */
(function () {
  'use strict';

  /* Themes that swap pages through swup re-run this file on every navigation
   * (SwupScriptsPlugin) and replace the document head, which drops the stylesheet it
   * injected (SwupHeadPlugin). The tracker therefore lives once per document: a second
   * execution refreshes the page reporting and repairs the widget instead of building
   * a second card and a second launcher. */
  var running = window.__ONLINE_STATS_TRACKER__;
  if (running) {
    running.resume();
    return;
  }

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

  /* ---------------------------------------------------------------------------
   * Console to visitor messages.
   *
   * The console can write to the address a visitor is browsing from. This half of
   * the script polls for those messages and shows them in a card that follows the
   * blog's own popup style: a bar next to the title, a rotating close button, the
   * author's avatar and name, and a reply box.
   *
   * Everything the visitor writes is treated as text. It is rendered with
   * textContent and never as markup, and the server drops control characters and
   * caps the length, so a reply cannot turn into a script on the page.
   * ------------------------------------------------------------------------- */
  var chatEnabled = cfg.chatEnabled !== false;
  var chatPollSeconds = cfg.chatPollSeconds > 0 ? cfg.chatPollSeconds : 5;
  var CHAT_MAX_LENGTH = 1000;
  var CHAT_RENDER_LIMIT = 30;

  /* Nothing is kept on the visitor's device: the cursor lives for this page only, and a
   * reload always starts over. That is both the privacy answer and what makes a reload
   * put the card away for good until the next message arrives. */
  var chatSeq = 0;
  var chatSeeded = false;
  var chatWarned = false;
  var chatMessages = [];

  /* Console diagnostics only: a visitor never sees this, but the site owner looking at
   * the browser console gets the exact reason the conversation is not working. */
  function warn(message) {
    try {
      if (window.console && window.console.warn) {
        window.console.warn('[online-stats] ' + message);
      }
    } catch (e) {
      /* nothing to do */
    }
  }
  var chatUnread = 0;
  var chatSending = false;
  var chatOpen = false;
  var chatDom = null;

  function chatElement(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined && text !== null) {
      node.textContent = String(text);
    }
    return node;
  }

  function timeLabel(ms) {
    if (!ms) {
      return '';
    }
    var diff = Date.now() - ms;
    if (diff < 60000) {
      return '刚刚';
    }
    if (diff < 3600000) {
      return Math.floor(diff / 60000) + ' 分钟前';
    }
    if (diff < 86400000) {
      return Math.floor(diff / 3600000) + ' 小时前';
    }
    return Math.floor(diff / 86400000) + ' 天前';
  }

  function lastAdminMessage() {
    for (var i = chatMessages.length - 1; i >= 0; i--) {
      if (chatMessages[i].direction === 'admin') {
        return chatMessages[i];
      }
    }
    return null;
  }

  function authorLabel() {
    var message = lastAdminMessage();
    return message && message.authorName ? message.authorName : '博主';
  }

  /* One author writes the whole thread, so every message is drawn with the same
   avatar instead of mixing whatever each message happened to carry. */
  function authorAvatar() {
    var message = lastAdminMessage();
    return message && message.authorAvatar ? String(message.authorAvatar) : '';
  }

  function injectChatStyle() {
    if (document.getElementById('oschat-style')) {
      return;
    }
    var style = document.createElement('style');
    style.id = 'oschat-style';
    style.textContent = [
      '.oschat-card{position:fixed;z-index:2147482000;display:flex;flex-direction:column;',
      'box-sizing:border-box;overflow:hidden;background:var(--card-bg,#fff);color:#0f172a;',
      'border-radius:.75rem;box-shadow:0 12px 40px rgba(15,23,42,.18);',
      'font-family:inherit;text-align:left}',
      '.oschat-card *{box-sizing:border-box}',
      '.oschat-head{display:flex;align-items:center;gap:.625rem;padding:.875rem 1rem .5rem}',
      '.oschat-title{position:relative;flex:1;min-width:0;margin:0;padding-left:.75rem;',
      'font-size:.9375rem;font-weight:700;line-height:1.4;color:inherit}',
      '.oschat-title::before{content:"";position:absolute;left:0;top:50%;width:.25rem;height:1rem;',
      'transform:translateY(-50%);border-radius:.375rem;background:var(--primary,#39C5BB)}',
      '.oschat-close{flex:none;display:flex;align-items:center;justify-content:center;width:2rem;',
      'height:2rem;padding:0;font-size:1rem;line-height:1;color:#94a3b8;cursor:pointer;',
      'background:transparent;border:0;border-radius:.5rem;transition:color .3s ease}',
      '.oschat-close span{display:block;transition:transform .3s ease}',
      '.oschat-close:hover{color:var(--primary,#39C5BB)}',
      '.oschat-close:hover span{transform:rotate(90deg)}',
      '.oschat-body{flex:1;min-height:0;padding:0 1rem;overflow-y:auto}',
      '.oschat-thread{display:flex;flex-direction:column;gap:.625rem;padding-bottom:.5rem}',
      '.oschat-msg{display:flex;gap:.5rem;align-items:flex-start;max-width:100%}',
      '.oschat-msg.is-me{justify-content:flex-end}',
      '.oschat-avatar{flex:none;display:flex;align-items:center;justify-content:center;',
      'width:1.75rem;height:1.75rem;color:var(--primary,#39C5BB);',
      'font-size:.875rem;font-weight:700;background:none}',
      'img.oschat-avatar{display:block;object-fit:contain}',
      '.oschat-bubble{padding:.375rem .625rem;border-radius:.625rem;background:#f1f5f9}',
      '.oschat-name{margin-bottom:.125rem;font-size:.6875rem;color:#94a3b8}',
      '.oschat-text{margin:0;font-size:.8125rem;line-height:1.6;white-space:pre-wrap;',
      'word-break:break-word;color:rgba(0,0,0,.72)}',
      '.oschat-msg.is-me .oschat-bubble{background:var(--primary,#39C5BB)}',
      '.oschat-msg.is-me .oschat-text{color:#fff}',
      '.oschat-foot{display:flex;gap:.5rem;align-items:flex-end;padding:.625rem 1rem .875rem;',
      'border-top:1px solid rgba(15,23,42,.06)}',
      '.oschat-input{flex:1;min-height:2.25rem;max-height:5rem;padding:.5rem .625rem;',
      'font-family:inherit;font-size:.8125rem;line-height:1.5;color:inherit;resize:none;',
      'background:rgba(15,23,42,.03);border:1px solid rgba(15,23,42,.12);',
      'border-radius:.5rem;outline:none}',
      '.oschat-input:focus{border-color:var(--primary,#39C5BB)}',
      '.oschat-send{flex:none;padding:.5rem .875rem;font-family:inherit;font-size:.8125rem;',
      'color:#fff;cursor:pointer;background:var(--primary,#39C5BB);border:0;border-radius:.5rem}',
      '.oschat-send:disabled{cursor:not-allowed;opacity:.55}',
      '.oschat-note{display:none;padding:0 1rem .625rem;font-size:.6875rem;color:#dc2626}',
      /* Same shape as the theme's own floating buttons, but on a solid card surface
       * rather than a translucent one: the solid colour is legible over any banner.
       * The geometry lives inline on the element so a missing stylesheet cannot blow
       * it up. */
      '.oschat-launcher{box-sizing:border-box;cursor:pointer;color:var(--primary,#39C5BB);',
      'background-color:var(--card-bg,#fff);',
      'box-shadow:0 4px 16px -4px #0000004d;transition:transform .2s ease}',
      '.oschat-launcher:active{transform:scale(.9)}',
      '.oschat-launcher svg{display:block;width:1.75rem;height:1.75rem}',
      '.oschat-badge{position:absolute;top:-.25rem;right:-.25rem;min-width:1.125rem;',
      'height:1.125rem;padding:0 .25rem;font-size:.625rem;line-height:1.125rem;color:#fff;',
      'text-align:center;background:#ef4444;border-radius:9999px}',
      '@media (min-width:769px){.oschat-card{right:1rem;bottom:1rem;width:20rem;',
      'max-height:60vh;animation:oschat-up .28s ease-out}}',
      '@media (max-width:768px){.oschat-card{top:0;left:0;right:0;height:33.333vh;',
      'max-height:33.333vh;border-radius:0 0 .75rem .75rem;animation:oschat-down .28s ease-out}}',
      '@keyframes oschat-up{from{opacity:0;transform:translateY(1rem)}to{opacity:1;transform:none}}',
      '@keyframes oschat-down{from{opacity:0;transform:translateY(-100%)}',
      'to{opacity:1;transform:none}}',
      /* The blog toggles dark mode with a class on :root, so both signals are honoured. */
      ':root.dark .oschat-card{background:var(--card-bg,#1e293b);color:#e2e8f0}',
      ':root.dark .oschat-launcher{background-color:var(--card-bg,#1e293b)}',
      ':root.dark .oschat-bubble{background:rgba(255,255,255,.08)}',
      ':root.dark .oschat-text{color:rgba(255,255,255,.72)}',
      ':root.dark .oschat-input{color:#e2e8f0;background:rgba(255,255,255,.06);',
      'border-color:rgba(255,255,255,.14)}',
      ':root.dark .oschat-foot{border-top-color:rgba(255,255,255,.08)}',
      '@media (prefers-color-scheme:dark){.oschat-card{background:var(--card-bg,#1e293b);',
      'color:#e2e8f0}.oschat-launcher{background-color:var(--card-bg,#1e293b)}',
      '.oschat-bubble{background:rgba(255,255,255,.08)}.oschat-text{color:rgba(255,255,255,.72)}',
      '.oschat-input{color:#e2e8f0;background:rgba(255,255,255,.06);',
      'border-color:rgba(255,255,255,.14)}.oschat-foot{border-top-color:rgba(255,255,255,.08)}}'
    ].join('');
    (document.head || document.documentElement).appendChild(style);
  }

  function buildAvatar(className) {
    var fallback = authorLabel().slice(0, 1);
    var source = authorAvatar();
    if (source.indexOf('https://') === 0 || source.indexOf('http://') === 0) {
      var image = chatElement('img', className);
      image.src = source;
      image.alt = '';
      image.onerror = function () {
        var replacement = chatElement('span', className, fallback);
        if (image.parentNode) {
          image.parentNode.replaceChild(replacement, image);
        }
      };
      return image;
    }
    return chatElement('span', className, fallback);
  }

  function buildCard() {
    var card = chatElement('div', 'oschat-card');
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-label', '博主发来的消息');

    var head = chatElement('div', 'oschat-head');
    var title = chatElement('h2', 'oschat-title');
    var close = chatElement('button', 'oschat-close');
    close.type = 'button';
    close.setAttribute('aria-label', '关闭消息卡片');
    close.appendChild(chatElement('span', null, '✕'));
    close.addEventListener('click', closeChat);
    head.appendChild(title);
    head.appendChild(close);

    var body = chatElement('div', 'oschat-body');
    var thread = chatElement('div', 'oschat-thread');
    body.appendChild(thread);

    var note = chatElement('p', 'oschat-note');
    var foot = chatElement('div', 'oschat-foot');
    var input = chatElement('textarea', 'oschat-input');
    input.rows = 1;
    input.maxLength = CHAT_MAX_LENGTH;
    input.placeholder = '回复博主…';
    input.setAttribute('aria-label', '回复内容');
    input.addEventListener('keydown', function (event) {
      if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
        event.preventDefault();
        sendReply();
      }
    });
    var send = chatElement('button', 'oschat-send', '发送');
    send.type = 'button';
    send.addEventListener('click', sendReply);
    foot.appendChild(input);
    foot.appendChild(send);

    card.appendChild(head);
    card.appendChild(body);
    card.appendChild(note);
    card.appendChild(foot);
    (document.body || document.documentElement).appendChild(card);

    chatDom = {
      card: card,
      title: title,
      thread: thread,
      body: body,
      note: note,
      input: input,
      send: send
    };
  }

  function buildThread() {
    var opener = lastAdminMessage();
    chatDom.title.textContent = opener && opener.authorName
      ? opener.authorName + ' 给你发来消息' : '你有一条新消息';
    chatDom.thread.textContent = '';
    var start = Math.max(0, chatMessages.length - CHAT_RENDER_LIMIT);
    for (var i = start; i < chatMessages.length; i++) {
      var message = chatMessages[i];
      var mine = message.direction !== 'admin';
      var row = chatElement('div', mine ? 'oschat-msg is-me' : 'oschat-msg');
      if (!mine) {
        row.appendChild(buildAvatar('oschat-avatar'));
      }
      var box = chatElement('div');
      if (!mine) {
        box.appendChild(chatElement('div', 'oschat-name',
          authorLabel() + (message.sentAt ? ' · ' + timeLabel(message.sentAt) : '')));
      }
      var bubble = chatElement('div', 'oschat-bubble');
      bubble.appendChild(chatElement('p', 'oschat-text', message.text));
      box.appendChild(bubble);
      row.appendChild(box);
      chatDom.thread.appendChild(row);
    }
  }

  function scrollThread() {
    if (chatDom) {
      chatDom.body.scrollTop = chatDom.body.scrollHeight;
    }
  }

  function renderThread() {
    if (!chatDom) {
      return;
    }
    buildThread();
    scrollThread();
  }

  /* The card carries no standing hint; a note only appears when something failed. */
  function setNote(text) {
    if (!chatDom) {
      return;
    }
    if (text) {
      chatDom.note.textContent = text;
      chatDom.note.style.display = 'block';
    } else {
      chatDom.note.textContent = '';
      chatDom.note.style.display = 'none';
    }
  }

  function setSending() {
    if (chatDom) {
      chatDom.send.disabled = chatSending;
      chatDom.send.textContent = chatSending ? '发送中' : '发送';
    }
  }

  /* focusInput is only set when the visitor opens the card themselves: a card that
   * arrives on its own must not take the keyboard, which on a phone would open it
   * over the page the visitor is reading. */
  function openChat(focusInput) {
    injectChatStyle();
    if (!chatDom) {
      buildCard();
    }
    chatDom.card.style.display = 'flex';
    chatOpen = true;
    chatUnread = 0;
    renderThread();
    setNote('');
    renderLauncher();
    if (focusInput) {
      try {
        chatDom.input.focus({ preventScroll: true });
      } catch (e) {
        /* focus is best effort */
      }
    }
  }

  function closeChat() {
    if (chatDom) {
      chatDom.card.style.display = 'none';
    }
    chatOpen = false;
    renderLauncher();
  }

  var chatLauncher = null;
  var launcherMeasureAt = 0;

  /* Fixed placement rather than a measured one: the widget must never depend on the
   * stylesheet, which a theme page swap can remove at any moment. */
  var LAUNCHER_SIZE = '3.5rem';
  var NAVBAR_GAP = 20;
  /* .floating-controls is fixed at right:.75rem, so the same offset keeps this button
   * in the column of the theme's own floating buttons. */
  var FLOATING_COLUMN_RIGHT = 12;

  /* iconify uil:message, sized like the theme's own floating button icons. */
  var CHAT_ICON_PATH =
    'm20.34 9.32l-14-7a3 3 0 0 0-4.08 3.9l2.4 5.37a1.06 1.06 0 0 1 0 .82l-2.4 5.37A3 3 ' +
    '0 0 0 5 22a3.14 3.14 0 0 0 1.35-.32l14-7a3 3 0 0 0 0-5.36Zm-.89 3.57l-14 7a1 1 0 0 ' +
    '1-1.35-1.3l2.39-5.37a2 2 0 0 0 .08-.22h6.89a1 1 0 0 0 0-2H6.57a2 2 0 0 0-.08-.22L4.1 ' +
    '5.41a1 1 0 0 1 1.35-1.3l14 7a1 1 0 0 1 0 1.78';

  function chatIcon() {
    var ns = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    // Attributes keep the mark at its intended size even without the stylesheet.
    svg.setAttribute('width', '28');
    svg.setAttribute('height', '28');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    var path = document.createElementNS(ns, 'path');
    path.setAttribute('fill', 'currentColor');
    path.setAttribute('d', CHAT_ICON_PATH);
    svg.appendChild(path);
    return svg;
  }

  function navbarRect() {
    var selectors = ['#navbar-card', '#navbar', '#navbar-wrapper', '.navbar', 'header'];
    for (var i = 0; i < selectors.length; i++) {
      var node = document.querySelector(selectors[i]);
      if (!node || !node.getBoundingClientRect) {
        continue;
      }
      var rect = node.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        return rect;
      }
    }
    return null;
  }

  /* Top right corner, under the navigation bar and in the same column as the theme's
   * floating buttons at the bottom. Measured once so it never drifts while scrolling. */
  function positionLauncher() {
    if (!chatLauncher) {
      return;
    }
    chatLauncher.style.right = FLOATING_COLUMN_RIGHT + 'px';
    chatLauncher.style.bottom = 'auto';
    chatLauncher.style.left = 'auto';
    var rect = navbarRect();
    if (rect && rect.bottom > 8 && rect.bottom < window.innerHeight * 0.6) {
      chatLauncher.style.top = Math.round(rect.bottom + NAVBAR_GAP) + 'px';
    } else {
      chatLauncher.style.top = '5.5rem';
    }
  }

  function remeasureLauncher() {
    var now = Date.now();
    if (now - launcherMeasureAt < 200) {
      return;
    }
    launcherMeasureAt = now;
    positionLauncher();
  }

  function renderLauncher() {
    var visible = chatEnabled && chatMessages.length > 0 && !chatOpen;
    if (!visible) {
      if (chatLauncher) {
        chatLauncher.style.display = 'none';
      }
      return;
    }
    if (!chatLauncher) {
      chatLauncher = chatElement('button', 'oschat-launcher');
      chatLauncher.type = 'button';
      chatLauncher.setAttribute('aria-label', '打开与博主的对话');
      chatLauncher.addEventListener('click', function () {
        openChat(true);
      });
      /* Layout that must survive a missing stylesheet: without it a theme head swap
       * turns the avatar into a full page image. */
      chatLauncher.style.position = 'fixed';
      chatLauncher.style.zIndex = '40';
      chatLauncher.style.width = LAUNCHER_SIZE;
      chatLauncher.style.height = LAUNCHER_SIZE;
      chatLauncher.style.padding = '0';
      chatLauncher.style.border = '0';
      chatLauncher.style.overflow = 'hidden';
      chatLauncher.style.borderRadius = '1rem';
      chatLauncher.style.alignItems = 'center';
      chatLauncher.style.justifyContent = 'center';
      (document.body || document.documentElement).appendChild(chatLauncher);
      window.addEventListener('resize', remeasureLauncher);
      // Measured once, on purpose: re-measuring on every poll would make the button
      // drift with the navigation bar while the visitor scrolls.
      positionLauncher();
    }
    chatLauncher.textContent = '';
    chatLauncher.appendChild(chatIcon());
    if (chatUnread > 0) {
      chatLauncher.appendChild(chatElement('span', 'oschat-badge', String(chatUnread)));
    }
    chatLauncher.style.display = 'flex';
  }

  /* Re-attaches the widget if a page swap moved the body out from under it. */
  function reattachChatUi() {
    var host = document.body || document.documentElement;
    if (chatDom && chatDom.card && !chatDom.card.isConnected) {
      host.appendChild(chatDom.card);
    }
    if (chatLauncher && !chatLauncher.isConnected) {
      host.appendChild(chatLauncher);
      positionLauncher();
    }
  }

  /* Called by a second execution of this file after a theme page swap. */
  function resume() {
    heartbeat();
    injectChatStyle();
    reattachChatUi();
    if (chatEnabled) {
      pollChat();
    }
  }

  function pushMessage(message) {
    if (!message || typeof message.seq !== 'number') {
      return;
    }
    for (var i = 0; i < chatMessages.length; i++) {
      if (chatMessages[i].seq === message.seq) {
        return;
      }
    }
    chatMessages.push(message);
    chatMessages.sort(function (left, right) {
      return left.seq - right.seq;
    });
  }

  function isNewFromAdmin(message) {
    return message && message.direction === 'admin';
  }

  function sendReply() {
    if (!chatDom || chatSending) {
      return;
    }
    var text = (chatDom.input.value || '').trim();
    if (!text) {
      return;
    }
    chatSending = true;
    setSending();
    setNote('');
    fetch(apiBase + 'chat-reply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text: text,
        url: location.pathname + location.search,
        title: document.title
      })
    }).then(function (response) {
      return response.ok ? response.json() : null;
    }).then(function (data) {
      chatSending = false;
      if (data && data.ok) {
        chatDom.input.value = '';
        pushMessage({
          seq: data.seq,
          direction: 'visitor',
          text: text,
          sentAt: Date.now()
        });
        if (data.seq > chatSeq) {
          chatSeq = data.seq;
        }
        chatSeeded = true;
        renderThread();
        setNote('');
      } else {
        setNote('发送失败，请稍后再试。');
      }
      setSending();
    }).catch(function () {
      chatSending = false;
      setSending();
      setNote('发送失败，请稍后再试。');
    });
  }

  function pollChat() {
    if (!chatEnabled || document.hidden) {
      return;
    }
    /* The first poll of a page never shows anything: it only learns where the thread
     * stands, so a reload leaves nothing on screen until a new message arrives. The
     * flag is cleared before the request rather than after a successful one, so a
     * refused or failed first poll can never swallow every later message. */
    var seeding = !chatSeeded;
    chatSeeded = true;
    fetch(apiBase + 'chat-inbox?since=' + chatSeq, {
      headers: { Accept: 'application/json' }
    }).then(function (response) {
      if (response.status === 403) {
        return response.json().then(function (body) {
          return { refused: true, hint: body && body.hint ? body.hint : '' };
        }).catch(function () {
          return { refused: true, hint: '' };
        });
      }
      return response.ok ? response.json() : null;
    }).then(function (data) {
      if (data && data.refused) {
        // Nothing is drawn for visitors; the site owner needs the reason though, and
        // the console is where they will look.
        if (!chatWarned) {
          chatWarned = true;
          warn('对话接口拒绝了这次查询：' + (data.hint || '403'));
        }
        return;
      }
      if (!data || data.enabled === false) {
        return;
      }
      var list = data.messages || [];
      var incoming = 0;
      if (!seeding) {
        for (var i = 0; i < list.length; i++) {
          pushMessage(list[i]);
          if (isNewFromAdmin(list[i])) {
            incoming++;
          }
        }
      }
      if (typeof data.latestSeq === 'number' && data.latestSeq > chatSeq) {
        chatSeq = data.latestSeq;
      }
      if (seeding || !chatMessages.length) {
        return;
      }
      if (incoming > 0) {
        chatUnread += incoming;
        openChat(false);
      } else if (chatOpen) {
        renderThread();
      } else {
        renderLauncher();
      }
    }).catch(function () {
      /* the next poll retries */
    });
  }

  heartbeat();
  hookHistory();
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) {
      heartbeat();
      pollChat();
    }
  });
  window.setInterval(heartbeat, interval * 1000);
  if (chatEnabled) {
    pollChat();
    window.setInterval(function () {
      // The head is replaced on every theme page swap, which drops the stylesheet
      // injected above; putting it back on each tick keeps the widget styled even if
      // the swap happens in an order that skips resume().
      injectChatStyle();
      pollChat();
    }, chatPollSeconds * 1000);
  }

  window.__ONLINE_STATS_TRACKER__ = { resume: resume };
})();