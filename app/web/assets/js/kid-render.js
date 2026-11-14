/* ==========================================================================
 * StudyOnTV 儿童识字与拼音增强渲染 + 轻音效模块（严格 ES5，兼容 Android 6.0 WebView）
 * 支持标记：
 *   [[py:zhī]]       -> 三色分段 + ɑ/ɡ 教学规范字形拼音
 *   [[hz:春]]        -> 田字格楷体汉字
 *   [[ruby:chūn|春]] -> 上方三色拼音 + 下方田字格楷体汉字
 * ========================================================================== */
(function () {
  'use strict';

  var TONE_MAP = {
    a: ['ā', 'á', 'ǎ', 'à'],
    o: ['ō', 'ó', 'ǒ', 'ò'],
    e: ['ē', 'é', 'ě', 'è'],
    i: ['ī', 'í', 'ǐ', 'ì'],
    u: ['ū', 'ú', 'ǔ', 'ù'],
    'ü': ['ǖ', 'ǘ', 'ǚ', 'ǜ']
  };
  var TONE_REV = {};
  (function () {
    var keys = ['a', 'o', 'e', 'i', 'u', 'ü'];
    for (var k = 0; k < keys.length; k++) {
      var v = keys[k], arr = TONE_MAP[v];
      for (var t = 0; t < arr.length; t++) {
        TONE_REV[arr[t]] = { base: v, tone: t + 1 };
      }
    }
  })();

  var INITIALS = [
    'zh', 'ch', 'sh', 'b', 'p', 'm', 'f', 'd', 't', 'n', 'l',
    'g', 'k', 'h', 'j', 'q', 'x', 'r', 'z', 'c', 's', 'y', 'w'
  ];

  function esc(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function parsePinyin(raw) {
    var s = String(raw || '').replace(/^\s+|\s+$/g, '').toLowerCase();
    s = s.replace(/u:/g, 'ü').replace(/v/g, 'ü');
    var tone = 0;
    var m = s.match(/^(.*?)([0-5])$/);
    if (m) { s = m[1]; tone = parseInt(m[2], 10) % 5; }
    var base = '';
    for (var i = 0; i < s.length; i++) {
      var ch = s.charAt(i);
      if (TONE_REV[ch]) { base += TONE_REV[ch].base; tone = TONE_REV[ch].tone; }
      else if ((ch >= 'a' && ch <= 'z') || ch === 'ü') base += ch;
    }
    return { base: base, tone: tone };
  }

  function toneIndex(base) {
    if (base.indexOf('a') >= 0) return base.indexOf('a');
    if (base.indexOf('o') >= 0) return base.indexOf('o');
    if (base.indexOf('e') >= 0) return base.indexOf('e');
    if (base.indexOf('iu') >= 0) return base.indexOf('iu') + 1;
    if (base.indexOf('ui') >= 0) return base.indexOf('ui') + 1;
    for (var i = base.length - 1; i >= 0; i--) {
      if ('iuü'.indexOf(base.charAt(i)) >= 0) return i;
    }
    return -1;
  }

  function toToned(p) {
    if (!p.base) return '';
    if (!p.tone) return p.base;
    var idx = toneIndex(p.base);
    if (idx < 0 || !TONE_MAP[p.base.charAt(idx)]) return p.base;
    var ch = TONE_MAP[p.base.charAt(idx)][p.tone - 1];
    return p.base.slice(0, idx) + ch + p.base.slice(idx + 1);
  }

  function glyphHtml(text) {
    var safe = esc(String(text || '').replace(/a/g, 'ɑ').replace(/g/g, 'ɡ'));
    return safe
      .replace(/ā/g, '<span class="sotv-toned-a">ɑ<span class="sotv-tone-mark">ˉ</span></span>')
      .replace(/á/g, '<span class="sotv-toned-a">ɑ<span class="sotv-tone-mark">ˊ</span></span>')
      .replace(/ǎ/g, '<span class="sotv-toned-a">ɑ<span class="sotv-tone-mark">ˇ</span></span>')
      .replace(/à/g, '<span class="sotv-toned-a">ɑ<span class="sotv-tone-mark">ˋ</span></span>');
  }

  function isWholeSyllable(base) {
    var whole = ['zhi', 'chi', 'shi', 'ri', 'zi', 'ci', 'si', 'yi', 'wu', 'yu', 'ye', 'yue', 'yuan', 'yin', 'yun', 'ying'];
    for (var i = 0; i < whole.length; i++) {
      if (whole[i] === base) return true;
    }
    return false;
  }

  function renderSinglePinyinHtml(raw) {
    var p = parsePinyin(raw);
    if (!p.base) return esc(raw);
    var tonedStr = toToned(p);
    var plain = p.base;
    // 整体认读音节不强行拆颜色，统一按韵母主色调展示更准确，或者常规拼音三色拆解
    if (isWholeSyllable(plain) && p.tone === 0 && String(raw).replace(/[0-5]/g, '') === plain) {
      return '<span class="sotv-py"><span class="sotv-py-whole">' + glyphHtml(tonedStr) + '</span></span>';
    }
    var initial = '';
    for (var i = 0; i < INITIALS.length; i++) {
      var ini = INITIALS[i];
      if (plain.indexOf(ini) === 0 && plain.length > ini.length) {
        initial = ini;
        break;
      }
    }
    var html = '';
    if (initial) {
      html += '<span class="sotv-py-ini">' + glyphHtml(tonedStr.slice(0, initial.length)) + '</span>';
    }
    var finPlain = plain.slice(initial.length);
    var finToned = tonedStr.slice(initial.length);
    var rest = finPlain.slice(1);
    var hasMedial = finPlain.length > 1 && /^[iuü]/.test(finPlain) && /[ao]/.test(rest);
    if (hasMedial) {
      html += '<span class="sotv-py-med">' + glyphHtml(finToned.charAt(0)) + '</span>';
      html += '<span class="sotv-py-fin">' + glyphHtml(finToned.slice(1)) + '</span>';
    } else if (finToned.length) {
      html += '<span class="sotv-py-fin">' + glyphHtml(finToned) + '</span>';
    }
    return '<span class="sotv-py">' + html + '</span>';
  }

  function renderPinyinHtml(raw) {
    var trimmed = String(raw || '').replace(/^\s+|\s+$/g, '');
    if (!trimmed) return '';
    var parts = trimmed.split(/\s+/);
    if (parts.length <= 1) return renderSinglePinyinHtml(trimmed);
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      if (parts[i]) out.push(renderSinglePinyinHtml(parts[i]));
    }
    return '<span class="sotv-py-group">' + out.join('<span class="sotv-py-gap" aria-hidden="true"></span>') + '</span>';
  }

  function hasRichTokens(text) {
    return /\[\[(py|hz|ruby):/.test(String(text || ''));
  }

  function renderRichHtml(raw) {
    var safe = esc(raw || '');
    safe = safe.replace(/\[\[ruby:([^|\]]+)\|([^\]]+)\]\]/g, function (_, py, hz) {
      return '<span class="sotv-ruby"><span class="sotv-ruby-py">' + renderPinyinHtml(py) + '</span><span class="sotv-ruby-hz sotv-tian">' + hz + '</span></span>';
    });
    safe = safe.replace(/\[\[py:([^\]]+)\]\]/g, function (_, py) {
      return renderPinyinHtml(py);
    });
    safe = safe.replace(/\[\[hz:([^\]]+)\]\]/g, function (_, hz) {
      return '<span class="sotv-tian sotv-hz-inline">' + hz + '</span>';
    });
    return safe;
  }

  function renderText(el, rawText) {
    if (!el) return;
    var text = String(rawText == null ? '' : rawText);
    if (!hasRichTokens(text)) {
      el.textContent = text;
      return;
    }
    el.innerHTML = renderRichHtml(text);
  }

  function stripTokenText(raw) {
    return String(raw || '')
      .replace(/\[\[ruby:([^|\]]+)\|([^\]]+)\]\]/g, '$2')
      .replace(/\[\[py:([^\]]+)\]\]/g, '$1')
      .replace(/\[\[hz:([^\]]+)\]\]/g, '$1');
  }

  function isKidCardOptions(options) {
    if (!options || !options.length || options.length > 4) return false;
    var richCount = 0, shortCount = 0;
    for (var i = 0; i < options.length; i++) {
      var val = String(options[i] || '');
      if (hasRichTokens(val)) richCount++;
      if (stripTokenText(val).length <= 6) shortCount++;
    }
    return richCount > 0 || shortCount === options.length;
  }

  // 极简 WebAudio 轻音效（无外置 MP3 内存负担）
  var audioCtx = null;
  function getAudioCtx() {
    if (audioCtx) return audioCtx;
    var Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return null;
    audioCtx = new Ctx();
    return audioCtx;
  }

  function playTone(ctx, freq, startOffset, duration, gainVal) {
    var now = ctx.currentTime + startOffset;
    var osc = ctx.createOscillator();
    var gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(freq, now);
    gain.gain.setValueAtTime(0.001, now);
    gain.gain.exponentialRampToValueAtTime(gainVal, now + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.001, now + duration);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + duration + 0.02);
  }

  function playSfx(kind) {
    try {
      var ctx = getAudioCtx();
      if (!ctx) return;
      if (ctx.state === 'suspended' && ctx.resume) ctx.resume();
      if (kind === 'right') {
        playTone(ctx, 523.25, 0, 0.14, 0.14);
        playTone(ctx, 659.25, 0.09, 0.16, 0.14);
        playTone(ctx, 783.99, 0.18, 0.24, 0.16);
      } else {
        playTone(ctx, 293.66, 0, 0.16, 0.12);
        playTone(ctx, 220.00, 0.13, 0.24, 0.12);
      }
    } catch (e) {}
  }

  window.SOTV_KID = {
    hasRichTokens: hasRichTokens,
    renderText: renderText,
    stripTokenText: stripTokenText,
    isKidCardOptions: isKidCardOptions,
    playSfx: playSfx
  };
})();
