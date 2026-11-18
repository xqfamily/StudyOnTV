/* ==========================================================================
 * 学一会 · 电视端教学黑板 / 渐隐背诵 / 数学分步讲解 / 听写机 / 随堂小测 / 云端扫码配对
 * 严格 ES5 语法，兼容 Android 5.0 / 6.0 老电视 WebView。
 * ========================================================================== */
(function () {
  'use strict';

  var st = {
    mode: '',          // 'lesson' | 'dictation' | 'push-quiz' | 'cloud-pair'
    lesson: null,
    runMode: 'guided', // 'guided' | 'auto'
    viewTab: 0,        // 0=原文, 1=讲解, 2=背诵
    reciteLevel: 0,    // 0=原文+拼音, 1=去拼音, 2=隐去后半句, 3=留首字, 4=只留标题
    pinyinLevel: 0,    // 0=全注音, 1=只注生字, 2=无注音
    activeLine: 0,
    revealedLines: {},
    stepIndex: 0,
    startedAt: 0,
    autoTimer: 0,
    eyeTimer: 0,
    toastTimer: 0,
    dictIndex: 0,
    dictRound: 1,
    dictSeconds: 10,
    dictPhase: 'listen', // 'listen' | 'check'
    dictTimer: 0,
    pushQuiz: null,
    pqIndex: 0,
    pqFirstCorrect: 0,
    pqTriedCurrent: false,
    pairTimer: 0,
    pairCode: ''
  };

  function $(id) { return document.getElementById(id); }

  function nativeCall(name, args) {
    var b = window.SOTVNative;
    try { if (b && typeof b[name] === 'function') return b[name].apply(b, args || []); } catch (e) {}
    return null;
  }

  function apiReq(method, path, body, cb) {
    var xhr = new XMLHttpRequest(), done = false;
    function fin(err, data) { if (done) return; done = true; if (cb) cb(err, data); }
    xhr.open(method, path, true);
    xhr.timeout = 9000;
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('X-Device-Token', nativeCall('apiToken') || '');
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      var parsed;
      try { parsed = JSON.parse(xhr.responseText || '{}'); } catch (e) { return fin('解析失败'); }
      if (xhr.status < 200 || xhr.status >= 300 || parsed.error) return fin(parsed.message || parsed.error || '请求失败');
      fin(null, parsed);
    };
    xhr.onerror = function () { fin('网络错误'); };
    xhr.ontimeout = function () { fin('请求超时'); };
    xhr.send(body ? JSON.stringify(body) : null);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function clearTimers() {
    window.clearTimeout(st.autoTimer);
    window.clearTimeout(st.eyeTimer);
    window.clearInterval(st.dictTimer);
    window.clearInterval(st.pairTimer);
  }

  function showScreen(name) {
    var list = document.querySelectorAll('.screen');
    for (var i = 0; i < list.length; i++) list[i].className = 'screen';
    var target = $('screen-' + name);
    if (target) target.className = 'screen active';
    document.body.className = 'on-' + name;
  }

  function playLessonAudio(url, fallbackText) {
    nativeCall('stopReading');
    if (url && /^https?:\/\//.test(url)) {
      if (window.SOTVNative) nativeCall('playAudio', [url]);
      else { try { new Audio(url).play(); } catch (e) {} }
      return;
    }
    if (fallbackText) nativeCall('readInLanguage', [fallbackText, 'zh-CN']);
  }

  function showCloudToast(text, badge) {
    var box = $('cloudToastBanner');
    if (!box) return;
    box.innerHTML = (badge === 'flower' ? '🌸 ' : '💬 ') + esc(text);
    box.className = 'cloud-toast-banner show';
    window.clearTimeout(st.toastTimer);
    st.toastTimer = window.setTimeout(function () { box.className = 'cloud-toast-banner'; }, 6000);
  }

  function startEyeGuardTimer() {
    window.clearTimeout(st.eyeTimer);
    st.eyeTimer = window.setTimeout(function () {
      showCloudToast('已经学了 15 分钟啦，看看远处休息一会吧！', 'flower');
    }, 15 * 60 * 1000);
  }

  function exitToNormal() {
    clearTimers();
    nativeCall('stopReading');
    var sec = st.startedAt ? Math.round((Date.now() - st.startedAt) / 1000) : 0;
    if (st.lesson && sec > 2) {
      apiReq('POST', '/api/lesson/exit', { lessonId: st.lesson.lessonId || '', seconds: sec }, function () {});
    }
    st.mode = '';
    nativeCall('exitToLauncher');
  }

  /* ---------- 三档拼音脚手架 + 五档渐隐背诵渲染 ---------- */
  function renderSectionTokens(sec, lineIdx) {
    var raw = sec.text || '';
    var mask = sec.knownMask || [];
    var revealed = !!st.revealedLines[lineIdx];
    var recite = (st.viewTab === 2) ? st.reciteLevel : 0;
    if (recite === 4 && !revealed) {
      return '<span style="color:#64748b;font-size:26px;">【按 OK 键核对第 ' + (lineIdx + 1) + ' 句】</span>';
    }
    var charCount = 0;
    var parts = [];
    var re = /\[\[ruby:([^|\]]+)\|([^\]]+)\]\]/g;
    var lastIndex = 0;
    var m;
    while ((m = re.exec(raw)) !== null) {
      if (m.index > lastIndex) parts.push(esc(raw.slice(lastIndex, m.index)));
      var py = m[1], ch = m[2];
      var hidePy = (st.pinyinLevel === 2) || (st.pinyinLevel === 1 && mask[charCount] === true) || (recite >= 1);
      var hideChar = false;
      if (!revealed) {
        if (recite === 2 && charCount >= 2) hideChar = true;
        if (recite === 3 && charCount >= 1) hideChar = true;
      }
      var pyHtml = window.SOTV_KID ? window.SOTV_KID.renderText('[[py:' + py + ']]') : esc(py);
      var pyCls = 'sotv-ruby-py' + (hidePy ? ' py-hidden' : '');
      var hzCls = 'sotv-ruby-hz sotv-tian' + (hideChar ? ' lesson-char-hidden' : '');
      var hzText = hideChar ? '？' : esc(ch);
      parts.push('<span class="sotv-ruby"><span class="' + pyCls + '">' + pyHtml + '</span><span class="' + hzCls + '">' + hzText + '</span></span>');
      charCount++;
      lastIndex = re.lastIndex;
    }
    if (lastIndex < raw.length) parts.push(esc(raw.slice(lastIndex)));
    return parts.join('');
  }

  function renderMathTenFrameSvg(tf) {
    if (!tf || !tf.kind) return '';
    var a = +tf.a || 8, b = +tf.b || 7, need = +tf.need || 2, rest = +tf.rest || 5;
    var cells = [];
    for (var i = 0; i < 10; i++) {
      var col = i % 5, row = Math.floor(i / 5);
      var cx = 44 + col * 72, cy = 44 + row * 72;
      var fill = (i < a) ? '#38bdf8' : 'none';
      var stroke = (i < a) ? '#38bdf8' : '#34d399';
      var dash = (i < a) ? '' : ' stroke-dasharray="6,4"';
      cells.push('<circle cx="' + cx + '" cy="' + cy + '" r="26" fill="' + fill + '" stroke="' + stroke + '" stroke-width="3"' + dash + '/>');
    }
    var subDots = [];
    if (tf.kind === 'make_ten') {
      for (var j = 0; j < b; j++) {
        var sx = 44 + j * 46;
        var sfill = (j < need) ? '#34d399' : '#fb923c';
        subDots.push('<circle cx="' + sx + '" cy="215" r="16" fill="' + sfill + '"/>');
      }
    }
    return '<svg viewBox="0 0 400 250" width="100%" height="240">' +
      '<rect x="8" y="8" width="384" height="146" rx="14" fill="rgba(255,255,255,0.03)" stroke="rgba(255,255,255,0.2)"/>' +
      cells.join('') +
      '<text x="16" y="182" fill="#94a3b8" font-size="18">' + esc(a + ' 格已有，凑十还差 ' + need + ' 格') + '</text>' +
      subDots.join('') +
      '</svg>';
  }

  function renderLesson() {
    var box = $('lessonContainer');
    var lsn = st.lesson;
    if (!box || !lsn) return;
    var modeLabel = { poem: '古诗', text: '课文', 'char': '生字', math: '数学讲解' }[lsn.mode] || '教学板';
    var pyLabels = ['全注音', '仅生字', '无注音'];
    var reciteLabels = ['1.全文+拼音', '2.去拼音', '3.隐后半句', '4.留首字', '5.只留标题'];

    var mainHtml = '';
    if (lsn.mode === 'math' && lsn.steps && lsn.steps.length) {
      var tfHtml = lsn.tenFrame ? renderMathTenFrameSvg(lsn.tenFrame) : '<div style="font-size:28px;color:#fbbf24;">思考步骤</div>';
      var stepRows = [];
      for (var i = 0; i < lsn.steps.length; i++) {
        var sp = lsn.steps[i];
        var cls = i < st.stepIndex ? 'done' : (i === st.stepIndex ? 'current' : 'locked');
        var rightTxt = i < st.stepIndex ? esc(sp.answer) : (i === st.stepIndex ? '按 OK 揭示' : '？');
        var leftTxt = i <= st.stepIndex ? esc((i + 1) + '. ' + sp.prompt) : ((i + 1) + '. 想一想…');
        stepRows.push('<div class="math-step-row ' + cls + '"><span>' + leftTxt + '</span><b>' + rightTxt + '</b></div>');
      }
      mainHtml = '<div class="math-split">' +
        '<div class="math-tenframe-box">' + tfHtml + (lsn.note && st.stepIndex >= lsn.steps.length ? '<div style="margin-top:12px;color:#d1fae5;font-size:18px;">' + esc(lsn.note) + '</div>' : '') + '</div>' +
        '<div class="math-steps-box">' + stepRows.join('') + '</div></div>';
    } else if (st.viewTab === 1) {
      mainHtml = '<div class="lesson-note-box">' +
        (lsn.image ? '<img src="' + esc(lsn.image) + '" style="max-height:130px;margin-bottom:12px;border-radius:10px;display:block;"/>' : '') +
        '<div>' + esc(lsn.note || '暂无更多讲解') + '</div></div>';
    } else {
      var reciteBar = '';
      if (st.viewTab === 2) {
        var rItems = [];
        for (var r = 0; r < reciteLabels.length; r++) {
          rItems.push('<span class="recite-step ' + (st.reciteLevel === r ? 'active' : '') + '">' + reciteLabels[r] + '</span>');
        }
        reciteBar = '<div class="recite-bar">' + rItems.join('') + '</div>';
      }
      var linesHtml = [];
      var secs = lsn.sections || [];
      for (var j = 0; j < secs.length; j++) {
        var isAct = (j === st.activeLine);
        linesHtml.push('<div class="lesson-line-card ' + (isAct ? 'active' : '') + '" data-line="' + j + '">' + renderSectionTokens(secs[j], j) + '</div>');
      }
      mainHtml = reciteBar + linesHtml.join('');
    }

    var sideHtml = '<div class="lesson-side">' +
      '<div class="lesson-side-item ' + (st.viewTab === 0 ? 'active' : '') + '">• 原文</div>' +
      '<div class="lesson-side-item ' + (st.viewTab === 1 ? 'active' : '') + '">• 讲解</div>' +
      '<div class="lesson-side-item ' + (st.viewTab === 2 ? 'active' : '') + '">• 背诵</div>' +
      (lsn.quiz && lsn.quiz.length ? '<div style="margin-top:14px;font-size:13px;color:#34d399;">附 ' + lsn.quiz.length + ' 道小测</div>' : '') +
      '</div>';

    var secsLen = (lsn.sections || []).length;
    box.innerHTML =
      '<div class="lesson-head">' +
        '<div class="lesson-pills"><span class="lesson-pill accent">教学板 · ' + esc(modeLabel) + '</span>' +
        '<span class="lesson-pill">' + (st.runMode === 'auto' ? '自学范读模式' : '陪学模式') + '</span></div>' +
        '<div class="lesson-pills"><span class="lesson-pill">拼音：' + pyLabels[st.pinyinLevel] + '</span></div>' +
      '</div>' +
      '<h1 class="lesson-title">' + esc(lsn.title || '') + '</h1>' +
      '<div class="lesson-subtitle">' + esc(lsn.subtitle || '') + '</div>' +
      '<div class="lesson-body"><div class="lesson-main">' + mainHtml + '</div>' + (lsn.mode !== 'math' ? sideHtml : '') + '</div>' +
      '<div class="lesson-foot">' +
        '<div class="hint"><span class="kbd">↑↓</span><span class="hint-text">换句</span><span class="kbd">OK</span><span class="hint-text">' + (lsn.mode === 'math' ? '下一步' : (st.viewTab === 2 ? '揭示/核对' : '朗读')) + '</span><span class="kbd">←→</span><span class="hint-text">原文·讲解·背诵</span><span class="kbd">菜单</span><span class="hint-text">拼音档位</span><span class="kbd">返回</span><span class="hint-text">退出</span></div>' +
        '<div class="lesson-pill">第 ' + (st.activeLine + 1) + ' / ' + Math.max(1, secsLen) + ' 句</div>' +
      '</div>';
  }

  function speakActiveLine() {
    var lsn = st.lesson;
    if (!lsn) return;
    var secs = lsn.sections || [];
    var sec = secs[st.activeLine];
    if (!sec) return;
    playLessonAudio(sec.audio || lsn.fullAudio, sec.plain);
  }

  function scheduleAutoAdvance() {
    window.clearTimeout(st.autoTimer);
    if (st.runMode !== 'auto' || st.mode !== 'lesson' || !st.lesson) return;
    var secs = st.lesson.sections || [];
    if (st.activeLine >= secs.length) return;
    speakActiveLine();
    var textLen = (secs[st.activeLine].plain || '').length;
    var waitMs = Math.max(3500, Math.min(9000, textLen * 450 + 1800));
    st.autoTimer = window.setTimeout(function () {
      if (st.mode !== 'lesson' || st.runMode !== 'auto') return;
      if (st.activeLine + 1 < secs.length) {
        st.activeLine++;
        renderLesson();
        scheduleAutoAdvance();
      } else {
        showCloudToast('范读完成！请自己试着读一遍哦', 'flower');
      }
    }, waitMs);
  }

  function openLesson() {
    clearTimers();
    st.mode = 'lesson';
    st.startedAt = Date.now();
    showScreen('lesson');
    startEyeGuardTimer();
    apiReq('GET', '/api/lesson/current', null, function (err, data) {
      if (err || !data || !data.lesson) {
        $('lessonContainer').innerHTML = '<h1 class="lesson-title">暂无待展示教学内容</h1><p style="text-align:center;color:#94a3b8;">请在家长手机端选择课文或古诗后点击推屏</p>';
        return;
      }
      st.lesson = data.lesson;
      st.runMode = data.runMode || 'guided';
      st.viewTab = 0;
      st.reciteLevel = 0;
      st.activeLine = 0;
      st.stepIndex = 0;
      st.revealedLines = {};
      renderLesson();
      if (st.runMode === 'auto') scheduleAutoAdvance();
      else speakActiveLine();
    });
  }

  /* ---------- 听写机 ---------- */
  function renderDictation() {
    var box = $('dictationContainer');
    var lsn = st.lesson;
    if (!box || !lsn) return;
    var secs = lsn.sections || [];
    if (st.dictPhase === 'check') {
      var cards = [];
      for (var i = 0; i < secs.length; i++) {
        cards.push('<div class="dict-word-card">' + (window.SOTV_KID ? window.SOTV_KID.renderText(secs[i].text) : esc(secs[i].plain)) + '</div>');
      }
      box.innerHTML =
        '<div class="lesson-head"><div class="lesson-pills"><span class="lesson-pill accent">听写核对 · 共 ' + secs.length + ' 词</span></div></div>' +
        '<h1 class="lesson-title">' + esc(lsn.title) + ' · 对照答案</h1>' +
        '<div class="lesson-subtitle">在手机端勾选写错的词语会自动纳入重点复习</div>' +
        '<div class="dict-grid">' + cards.join('') + '</div>' +
        '<div class="lesson-foot"><div class="hint"><span class="kbd">←</span><span class="hint-text">重听一遍</span><span class="kbd">返回</span><span class="hint-text">结束听写</span></div></div>';
      return;
    }

    var cur = secs[st.dictIndex] || { charCount: 2, plain: '' };
    var slotCount = cur.charCount || (cur.plain || '').length || 2;
    var slots = [];
    for (var s = 0; s < slotCount; s++) slots.push('<div class="dict-slot"></div>');
    var dots = [];
    for (var d = 0; d < secs.length; d++) {
      var dc = d < st.dictIndex ? '#34d399' : (d === st.dictIndex ? '#f59e0b' : 'rgba(255,255,255,0.2)');
      dots.push('<span style="display:inline-block;width:12px;height:12px;border-radius:50%;margin:0 4px;background:' + dc + ';"></span>');
    }
    box.innerHTML =
      '<div class="lesson-head"><div class="lesson-pills"><span class="lesson-pill accent">听写 · 第 ' + (st.dictIndex + 1) + ' / ' + secs.length + ' 词</span></div></div>' +
      '<h1 class="lesson-title">听一听，写在本子上</h1>' +
      '<div class="lesson-subtitle">每个词读两遍，写完坐端正等下一个</div>' +
      '<div class="dict-stage">' +
        '<div><div style="font-size:28px;font-weight:900;color:#fbbf24;text-align:center;">🔊 第 ' + st.dictRound + ' 遍</div></div>' +
        '<div class="dict-slots">' + slots.join('') + '</div>' +
        '<div class="dict-ring">' + st.dictSeconds + '</div>' +
      '</div>' +
      '<div style="text-align:center;color:#94a3b8;font-size:18px;margin-bottom:14px;">共 ' + slotCount + ' 个字</div>' +
      '<div style="text-align:center;">' + dots.join('') + '</div>' +
      '<div class="lesson-foot"><div class="hint"><span class="kbd">OK</span><span class="hint-text">再读一遍</span><span class="kbd">→</span><span class="hint-text">下一个</span><span class="kbd">←</span><span class="hint-text">上一个</span><span class="kbd">返回</span><span class="hint-text">退出</span></div></div>';
  }

  function startDictationWord() {
    window.clearInterval(st.dictTimer);
    var secs = (st.lesson && st.lesson.sections) || [];
    if (st.dictIndex >= secs.length) {
      st.dictPhase = 'check';
      renderDictation();
      return;
    }
    var cur = secs[st.dictIndex];
    st.dictRound = 1;
    st.dictSeconds = 10;
    renderDictation();
    playLessonAudio(cur.audio, cur.plain);
    st.dictTimer = window.setInterval(function () {
      if (st.mode !== 'dictation' || st.dictPhase !== 'listen') { window.clearInterval(st.dictTimer); return; }
      st.dictSeconds--;
      if (st.dictSeconds === 6 && st.dictRound === 1) {
        st.dictRound = 2;
        playLessonAudio(cur.audio, cur.plain);
      }
      if (st.dictSeconds <= 0) {
        st.dictIndex++;
        startDictationWord();
        return;
      }
      renderDictation();
    }, 1000);
  }

  function openDictation() {
    clearTimers();
    st.mode = 'dictation';
    st.startedAt = Date.now();
    showScreen('dictation');
    apiReq('GET', '/api/lesson/current', null, function (err, data) {
      if (err || !data || !data.lesson) {
        $('dictationContainer').innerHTML = '<h1 class="lesson-title">暂无听写词表</h1>';
        return;
      }
      st.lesson = data.lesson;
      st.dictPhase = 'listen';
      st.dictIndex = 0;
      startDictationWord();
    });
  }

  /* ---------- 即时随堂小测（不扣额度，支持自由练/奖励上限） ---------- */
  function openPushQuiz() {
    clearTimers();
    st.mode = 'push-quiz';
    showScreen('quiz');
    apiReq('GET', '/api/push-quiz/current', null, function (err, data) {
      if (err || !data || !data.items || !data.items.length) {
        $('qText').textContent = '暂无随堂小测题目';
        $('qOptions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">返回</button>';
        return;
      }
      st.pushQuiz = data;
      st.pqIndex = 0;
      st.pqFirstCorrect = 0;
      renderPushQuizQuestion();
    });
  }

  function renderPushQuizQuestion() {
    var pq = st.pushQuiz;
    var items = pq.items || [];
    if (st.pqIndex >= items.length) {
      apiReq('POST', '/api/push-quiz/finish', {
        correctFirstTry: st.pqFirstCorrect,
        total: items.length
      }, function (err, res) {
        var granted = (res && res.grantedMinutes) || 0;
        var tip = granted > 0 ? ('太棒啦！获得 ' + granted + ' 分钟看电视奖励！') : '随堂小测完成啦，按返回键继续！';
        $('qText').textContent = '答对 ' + st.pqFirstCorrect + ' / ' + items.length + ' 题 · ' + tip;
        $('qOptions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">完成退出</button>';
        $('qMedia').innerHTML = '';
        $('qFeedback').className = 'feedback';
        if (window.TV && window.TV.focusDefault) window.TV.focusDefault();
      });
      return;
    }
    var item = items[st.pqIndex];
    st.pqTriedCurrent = false;
    $('qMode').textContent = pq.title || '随堂小测';
    $('qIndex').textContent = String(st.pqIndex + 1);
    $('qTotal').textContent = ' / ' + items.length;
    if (window.SOTV_KID) window.SOTV_KID.renderText($('qText'), item.q || '');
    else $('qText').textContent = item.q || '';
    $('qMedia').innerHTML = '';
    $('qFeedback').className = 'feedback';
    var opts = item.a || [];
    var html = [];
    for (var i = 0; i < opts.length; i++) {
      var txt = window.SOTV_KID ? window.SOTV_KID.renderText(opts[i]) : esc(opts[i]);
      html.push('<button class="option focusable" data-pq-opt="' + i + '"><span class="letter">' + String.fromCharCode(65 + i) + '</span><span class="opt-text">' + txt + '</span></button>');
    }
    $('qOptions').innerHTML = html.join('');
    var btns = document.querySelectorAll('#qOptions [data-pq-opt]');
    for (var b = 0; b < btns.length; b++) {
      btns[b].onclick = function () {
        var idx = +this.getAttribute('data-pq-opt');
        if (idx === item.k) {
          if (!st.pqTriedCurrent) st.pqFirstCorrect++;
          if (window.SOTV_KID && window.SOTV_KID.playSfx) window.SOTV_KID.playSfx('right');
          st.pqIndex++;
          renderPushQuizQuestion();
        } else {
          st.pqTriedCurrent = true;
          this.className = 'option focusable wrong';
          if (window.SOTV_KID && window.SOTV_KID.playSfx) window.SOTV_KID.playSfx('wrong');
        }
      };
    }
    if (btns[0]) btns[0].classList.add('focused');
  }

  /* ---------- 云端扫码配对 UI（#screen-pair 增强） ---------- */
  function startCloudPair() {
    window.clearInterval(st.pairTimer);
    var statusEl = $('cloudPairStatus'), digitsEl = $('cloudPairCodeBox');
    apiReq('GET', '/api/pair/state', null, function (err, info) {
      var curOrigin = (info && info.origin) || '';
      var origin = curOrigin || window.prompt('请输入服务端地址（如 https://tv.example.com）：', 'http://192.168.50.5:8006') || '';
      if (!origin) return;
      if (statusEl) { statusEl.style.display = 'block'; statusEl.textContent = '正在向云端申请 6 位配对码…'; }
      apiReq('POST', '/api/pair/start', { origin: origin }, function (e2, data) {
        if (e2 || !data || !data.code) {
          if (statusEl) statusEl.textContent = e2 || '申请配对码失败';
          return;
        }
        st.pairCode = data.code;
        if (digitsEl) {
          digitsEl.style.display = 'flex';
          digitsEl.innerHTML = data.code.split('').map(function (c) { return '<div class="cloud-pair-digit">' + esc(c) + '</div>'; }).join('');
        }
        if ($('pairTitle')) $('pairTitle').textContent = '扫码连接云端';
        if ($('pairUrl')) $('pairUrl').textContent = data.pairUrl || '';
        if (window.qrcode && data.pairUrl) {
          var qr = window.qrcode(0, 'M'); qr.addData(data.pairUrl); qr.make();
          $('pairQr').innerHTML = qr.createImgTag(8, 8);
        }
        if (statusEl) statusEl.textContent = '正在等待手机端扫码绑定（10 分钟内有效）…';
        st.pairTimer = window.setInterval(function () {
          apiReq('GET', '/api/pair/status?code=' + encodeURIComponent(st.pairCode), null, function (pe, pd) {
            if (!pe && pd && pd.status === 'bound') {
              window.clearInterval(st.pairTimer);
              if (statusEl) statusEl.textContent = '✅ 已成功绑定到「' + (pd.familyName || '我的家庭') + '」！';
              showCloudToast('云端连接成功！现在可以用手机随时推屏教学', 'flower');
            }
          });
        }, 3000);
      });
    });
  }

  /* ---------- 遥控器与手机教鞭统一按键处理 ---------- */
  function handleKey(action) {
    if (st.mode === 'push-quiz') {
      if (action === 'back') { exitToNormal(); return true; }
      return false;
    }
    if (st.mode === 'dictation') {
      if (action === 'back') { exitToNormal(); return true; }
      if (action === 'ok' && st.dictPhase === 'listen') {
        var cur = ((st.lesson && st.lesson.sections) || [])[st.dictIndex];
        if (cur) playLessonAudio(cur.audio, cur.plain);
        return true;
      }
      if (action === 'right' && st.dictPhase === 'listen') { st.dictIndex++; startDictationWord(); return true; }
      if (action === 'left') {
        if (st.dictPhase === 'check') { st.dictPhase = 'listen'; st.dictIndex = 0; startDictationWord(); }
        else if (st.dictIndex > 0) { st.dictIndex--; startDictationWord(); }
        return true;
      }
      return true;
    }
    if (st.mode !== 'lesson' || !st.lesson) return false;
    var secs = st.lesson.sections || [];
    var steps = st.lesson.steps || [];

    if (action === 'back') { exitToNormal(); return true; }
    if (action === 'up') {
      if (st.lesson.mode === 'math') { if (st.stepIndex > 0) st.stepIndex--; }
      else if (st.activeLine > 0) st.activeLine--;
      renderLesson(); return true;
    }
    if (action === 'down') {
      if (st.lesson.mode === 'math') { if (st.stepIndex < steps.length) st.stepIndex++; }
      else if (st.activeLine + 1 < secs.length) st.activeLine++;
      renderLesson(); return true;
    }
    if (action === 'left') {
      if (st.viewTab === 2 && st.reciteLevel > 0) st.reciteLevel--;
      else st.viewTab = (st.viewTab + 2) % 3;
      renderLesson(); return true;
    }
    if (action === 'right') {
      if (st.viewTab === 2 && st.reciteLevel < 4) st.reciteLevel++;
      else st.viewTab = (st.viewTab + 1) % 3;
      renderLesson(); return true;
    }
    if (action === 'menu') {
      st.pinyinLevel = (st.pinyinLevel + 1) % 3;
      renderLesson(); return true;
    }
    if (action === 'ok') {
      if (st.lesson.mode === 'math') {
        if (st.stepIndex < steps.length) st.stepIndex++;
        renderLesson();
      } else if (st.viewTab === 2 && st.reciteLevel >= 2 && !st.revealedLines[st.activeLine]) {
        st.revealedLines[st.activeLine] = true;
        renderLesson();
        speakActiveLine();
      } else {
        speakActiveLine();
      }
      return true;
    }
    return false;
  }

  function handleCloudEvent(action, payload) {
    payload = payload || {};
    if (action === 'toast' && payload.text) {
      showCloudToast(payload.text, payload.badge || 'flower');
      return;
    }
    if (action === 'lesson_updated') {
      if (payload.page === 'dictation') openDictation();
      else if (payload.page === 'lesson') openLesson();
      return;
    }
    if (action === 'lesson_ctrl') {
      var act = payload.action;
      if (act === 'prev_section') handleKey('up');
      else if (act === 'next_section') handleKey('down');
      else if (act === 'play') handleKey('ok');
      else if (act === 'recite_level') { st.viewTab = 2; st.reciteLevel = (st.reciteLevel + 1) % 5; renderLesson(); }
      else if (act === 'pinyin_level') handleKey('menu');
      else if (act === 'close') exitToNormal();
    }
  }

  window.SOTV_LESSON = {
    openLesson: openLesson,
    openDictation: openDictation,
    openPushQuiz: openPushQuiz,
    startCloudPair: startCloudPair,
    handleKey: handleKey,
    handleCloudEvent: handleCloudEvent,
    isActive: function () { return st.mode === 'lesson' || st.mode === 'dictation' || st.mode === 'push-quiz'; }
  };
  window.SOTV_CLOUD_EVENT = handleCloudEvent;
})();
