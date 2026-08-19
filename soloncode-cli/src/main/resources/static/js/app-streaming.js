/* ===== app-streaming.js ===== */
/* 通信与核心流程：发送 + WebEvent + WebSocket */
/* 依赖：app-base.js, app-ui.js, app-history.js, app-message.js */

/* ===== Send from both inputs ===== */
$(welcomeSendBtn).on('click', function() { sendMessage(); });
$(chatSendBtn).on('click', function() {
    if (btnMode === 'stop' && activeSessionId && sessionMap[activeSessionId]) {
        var sess = sessionMap[activeSessionId];
        // 已在等待服务端 done，避免重复 interrupt
        if (sess.stopRequested) return;
        sess.stopRequested = true;
        // 标记本轮因 Stop 结束：finish 时不再 drain 排队
        sess._stoppedTurn = true;
        // Stop = 中断当前 + 清空排队
        if (sess.messageQueue && sess.messageQueue.length) {
            sess.messageQueue = [];
            if (typeof renderQueueDock === 'function') renderQueueDock();
            if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
        }
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
        // 提交 interrupt；不在本地立即 finishStream
        // 等服务端 error(取消) + trace + done 到齐后再收尾，避免迟到 chunk 被当成新流
        try {
            $.post('/web/chat/interrupt?sessionId=' + encodeURIComponent(activeSessionId))
                .fail(function(err) {
                    console.warn('[stop] interrupt request failed:', err);
                    // HTTP 请求失败时用短兜底快速恢复按鈕，无需等待 4s
                    if (sess._stopFallbackTimer) clearTimeout(sess._stopFallbackTimer);
                    sess._stopFallbackTimer = setTimeout(function() {
                        sess._stopFallbackTimer = null;
                        if (sess.isStreaming && sess.stopRequested) {
                            finishStream(sess);
                        }
                    }, 1500);
                });
        } catch (e) {
            console.warn('[stop] interrupt failed:', e);
        }
        // 兜底：服务端异常未回 done 时，避免按钮永久卡在 stop
        if (sess._stopFallbackTimer) clearTimeout(sess._stopFallbackTimer);
        sess._stopFallbackTimer = setTimeout(function() {
            sess._stopFallbackTimer = null;
            if (sess.isStreaming && sess.stopRequested) {
                finishStream(sess);
            }
        }, 4000);
    } else {
        sendMessage();
    }
});

/* ===== Click to focus ===== */
$('.welcome-input-box').on('click', function(e) {
    if (!$(e.target).closest('button').length && !$(e.target).closest('.loop-panel').length && !$(e.target).closest('.model-dropdown').length) welcomeInput.focus();
});
$('.input-box').on('click', function(e) {
    if (!$(e.target).closest('button').length && !$(e.target).closest('.history-panel').length && !$(e.target).closest('.loop-panel').length && !$(e.target).closest('.model-dropdown').length) chatInput.focus();
});

/* ===== New Chat ===== */
$(newChatBtn).on('click', function() {
    if (typeof closeCenterViewer === 'function') closeCenterViewer();
    currentChatIndex = -1;
    switchToWelcomeMode();
    updateHistoryUI();
});

/* ===== Message Queue (运行中 follow-up 排队) ===== */
var QUEUE_PERSIST_DEBOUNCE_MS = 250;

/** 序列化为可落盘结构（V1：文本+模型元数据，不写附件二进制） */
function serializeQueueForPersist(queue) {
    var q = queue || [];
    var out = [];
    for (var i = 0; i < q.length; i++) {
        var item = q[i];
        if (!item) continue;
        var text = item.text || '';
        var displayText = item.displayText || '';
        var hasFiles = !!(item.files && item.files.length) || !!item.hasFiles;
        // 无文本的纯附件项无法跨刷新恢复，跳过落盘
        if (!String(text).trim() && !String(displayText).trim()) continue;
        var row = {
            id: item.id,
            text: text,
            displayText: displayText,
            createdAt: item.createdAt || Date.now()
        };
        if (item.model) row.model = item.model;
        if (item.reasoningEffort) row.reasoningEffort = item.reasoningEffort;
        if (item.selectedAgent) row.selectedAgent = item.selectedAgent;
        if (hasFiles) row.hasFiles = true;
        out.push(row);
    }
    return out;
}

var _queuePersistFailToastAt = 0;

function schedulePersistMessageQueue(sess) {
    if (!sess || !sess.sessionId) return;
    // 一旦本地发生变更，本地即为权威源（避免清空后因未 hydrate 而跳过写盘）
    sess._queueLoaded = true;
    if (sess._queuePersistTimer) clearTimeout(sess._queuePersistTimer);
    sess._queuePersistTimer = setTimeout(function() {
        sess._queuePersistTimer = null;
        persistMessageQueueNow(sess, false);
    }, QUEUE_PERSIST_DEBOUNCE_MS);
}
window.schedulePersistMessageQueue = schedulePersistMessageQueue;

/**
 * 立即落盘任务排队。
 * @param {boolean} [useKeepalive] 页面卸载路径传 true，提高关闭时请求存活率
 */
function persistMessageQueueNow(sess, useKeepalive) {
    if (!sess || !sess.sessionId) return;
    var payload = {
        sessionId: sess.sessionId,
        updatedAt: Date.now(),
        items: serializeQueueForPersist(sess.messageQueue || [])
    };
    try {
        var opts = {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        };
        if (useKeepalive) opts.keepalive = true;
        fetch('/web/chat/queue', opts)
            .then(function(r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function(res) {
                if (res && res.code === 200) return;
                var msg = (res && res.description) || (res && res.message) || I18n.t('streaming.queueSaveFailed');
                console.warn('[queue] persist rejected:', msg);
                var now = Date.now();
                if (typeof showToast === 'function' && now - _queuePersistFailToastAt > 8000) {
                    _queuePersistFailToastAt = now;
                    showToast(I18n.t('streaming.queuePersistFailed'), 'error', 2500);
                }
            })
            .catch(function(err) {
                console.warn('[queue] persist failed:', err);
                // unload 路径不弹 toast，避免关页时打扰
                if (useKeepalive) return;
                var now = Date.now();
                if (typeof showToast === 'function' && now - _queuePersistFailToastAt > 8000) {
                    _queuePersistFailToastAt = now;
                    showToast(I18n.t('streaming.queuePersistFailed'), 'error', 2500);
                }
            });
    } catch (e) {
        console.warn('[queue] persist failed:', e);
    }
}
window.persistMessageQueueNow = persistMessageQueueNow;

/** 从会话目录 queue-tasks.json 恢复排队（仅文本；附件需重新添加）。冷恢复只展示，不自动发送。 */
function loadMessageQueue(sess) {
    if (!sess || !sess.sessionId) return;
    if (sess._queueLoaded || sess._queueLoading) return;
    sess._queueLoading = true;
    var sid = sess.sessionId;
    fetch('/web/chat/queue?sessionId=' + encodeURIComponent(sid))
        .then(function(r) { return r.json(); })
        .then(function(res) {
            if (!sessionMap[sid]) return;
            var target = sessionMap[sid];
            target._queueLoading = false;
            target._queueLoaded = true;

            // 加载期间用户已本地入队：本地为准，并写回服务端
            if (target.messageQueue && target.messageQueue.length) {
                schedulePersistMessageQueue(target);
                if (sid === activeSessionId) {
                    if (typeof renderQueueDock === 'function') renderQueueDock();
                    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
                }
                return;
            }

            if (res && res.code && res.code !== 200) {
                console.warn('[queue] load rejected:', res.description || res.message || res.code);
                return;
            }

            var items = (res && res.data && res.data.items) ? res.data.items : [];
            var restored = [];
            for (var i = 0; i < items.length; i++) {
                var it = items[i];
                if (!it) continue;
                restored.push({
                    id: it.id || ('q_' + Date.now().toString(36) + '_' + i),
                    text: it.text || '',
                    displayText: it.displayText || it.text || '',
                    files: [],
                    hasFiles: !!it.hasFiles,
                    model: it.model || null,
                    reasoningEffort: it.reasoningEffort || null,
                    selectedAgent: it.selectedAgent || '',
                    createdAt: it.createdAt || Date.now()
                });
            }
            target.messageQueue = restored;

            if (sid === activeSessionId) {
                if (typeof renderQueueDock === 'function') renderQueueDock();
                if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
                // 冷恢复：只 hydrate UI，不自动 drain。用户 Enter 空发或带新消息入队后会续发。
                if (restored.length && typeof showToast === 'function') {
                    showToast(I18n.t('streaming.queueRestored', {n: restored.length}), 'info', 2800);
                    if (typeof expandFilerPanel === 'function') {
                        try { expandFilerPanel(); } catch (e) {}
                    }
                }
            }
        })
        .catch(function(err) {
            if (sessionMap[sid]) {
                sessionMap[sid]._queueLoading = false;
                // 失败也标记 loaded，避免反复打接口；本地队列仍可用
                sessionMap[sid]._queueLoaded = true;
            }
            console.warn('[queue] load failed:', err);
        });
}
window.loadMessageQueue = loadMessageQueue;

// 页面关闭/刷新前冲刷未落盘的 debounce，尽量减少丢队
function flushAllMessageQueuesNow() {
    try {
        Object.keys(sessionMap || {}).forEach(function(sid) {
            var s = sessionMap[sid];
            if (!s) return;
            if (s._queuePersistTimer) {
                clearTimeout(s._queuePersistTimer);
                s._queuePersistTimer = null;
                persistMessageQueueNow(s, true);
            }
        });
    } catch (e) {}
}
window.addEventListener('beforeunload', flushAllMessageQueuesNow);
window.addEventListener('pagehide', flushAllMessageQueuesNow);

function buildDisplayText(text, filesToSend) {
    var displayText = text || '';
    if (!displayText && filesToSend && filesToSend.length > 0) {
        var first = filesToSend[0];
        if (first.attachmentsType === 'image') {
            displayText = I18n.t('streaming.describeImages');
        } else {
            displayText = I18n.t('streaming.processFiles');
        }
    }
    return displayText;
}

    function truncateQueueText(text, maxLen) {
    var s = String(text || '').replace(/\s+/g, ' ').trim();
    if (!s) return I18n.t('streaming.attachment');
    maxLen = maxLen || 60;
    if (s.length <= maxLen) return s;
    return s.slice(0, maxLen) + '…';
}

    function hasDraftInput() {
    return !!(chatInput && chatInput.value.trim()) || (pendingFiles && pendingFiles.length > 0);
        }

    function applyQueuedItemToInput(item) {
    if (!item) return;
    if (!inChatMode) switchToChatMode();
    chatInput.value = item.text || '';
    autoResize(chatInput);
    if (item.files && item.files.length) {
        pendingFiles = item.files.slice();
        if (typeof renderAttachments === 'function') renderAttachments();
    } else {
        clearAttachmentPreview();
    }
    chatInput.focus();
    }

    function enqueueMessage(sess, text, files) {
    if (!sess) return false;
    // Stop 窗口期：禁止再入队，避免结束后误续发
    if (sess.stopRequested) {
        showToast(I18n.t('streaming.stoppingWaitSend'), 'info', 1500);
        return false;
    }
    if (!sess.messageQueue) sess.messageQueue = [];
    if (sess.messageQueue.length >= MAX_QUEUED_MESSAGES) {
        showToast(I18n.t('streaming.queueMaxLimit', {n: MAX_QUEUED_MESSAGES}), 'info', 2000);
        return false;
    }
    var filesSnap = (files || []).slice();
    var displayText = buildDisplayText(text, filesSnap);
    sess.messageQueue.push({
        id: 'q_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 6),
        text: text || '',
        displayText: displayText,
        files: filesSnap,
        hasFiles: filesSnap.length > 0,
        model: typeof getSelectedModel === 'function' ? getSelectedModel() : null,
        reasoningEffort: typeof getSelectedReasoning === 'function' ? getSelectedReasoning() : null,
        thinkingMode: typeof getSelectedThinking === 'function' ? getSelectedThinking() : '',
        selectedAgent: typeof getSelectedAgent === 'function' ? getSelectedAgent() : '',
        createdAt: Date.now()
    });
    clearInput();
    clearAttachmentPreview();
    if (typeof renderQueueDock === 'function') renderQueueDock();
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
    // 首次入队且右栏折叠时自动展开，避免「队列进了黑洞」
    if (sess.messageQueue.length === 1 && typeof window.expandFilerPanel === 'function') {
        window.expandFilerPanel();
    }
    return true;
    }

    function sendMessageCore(sess, text, filesToSend, options) {
    options = options || {};
    filesToSend = filesToSend || [];
    var displayText = options.displayText || buildDisplayText(text, filesToSend);

    if (currentChatIndex === -1) {
        saveChatToHistory(displayText);
    }

    // 先标记 streaming，再 setActiveSession，避免会话切换时误触发 drain 连发
    sess.isStreaming = true;
    sess.stopRequested = false;
    // 新一轮开始：清除上一轮 Stop 标记
    sess._stoppedTurn = false;
    sess.acceptingStream = true;
    sess._streamClosed = false;
    sess.messageStartTime = Date.now();

    if (!inChatMode) switchToChatMode();
    setActiveSession(sess.sessionId);

    // 用户主动发消息：作废此前上滑状态，后续 AI 输出必须重新粘底
    if (typeof scrollToBottom === 'function') scrollToBottom(true);

    var imageDataUrls = [];
    var fileAttachments = [];
    for (var i = 0; i < filesToSend.length; i++) {
        if (filesToSend[i].type === 'image') imageDataUrls.push(filesToSend[i]);
        else fileAttachments.push(filesToSend[i]);
    }
    appendUserMessage(sess, displayText, imageDataUrls, fileAttachments);

    isStreaming = true;
    setBtnStopMode();
    resetStreamState(sess);
    showThinking(sess);
    if (typeof startRoundElapsed === 'function') startRoundElapsed(sess);
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();

    sendWithFormDataGrouped(sess, text || '', filesToSend, options);
}

    function sendQueuedItem(sess, item) {
    if (!sess || !item) return;
    sendMessageCore(sess, item.text || '', item.files || [], {
        displayText: item.displayText,
        model: item.model,
        reasoningEffort: item.reasoningEffort,
        thinkingMode: item.thinkingMode || '',
        selectedAgent: item.selectedAgent
    });
        }

    function drainMessageQueue(sess) {
    if (!sess || sess._queueDraining) return;
    if (sess.isStreaming) return;
    if (sess.stopRequested || sess._stoppedTurn) return;
    if (!sess.messageQueue || !sess.messageQueue.length) return;

    // 仅 active 会话自动续发，避免后台会话抢焦点
    if (sess.sessionId !== activeSessionId) return;

    sess._queueDraining = true;
    try {
        var item = sess.messageQueue.shift();
        if (typeof renderQueueDock === 'function') renderQueueDock();
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
        if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
        sendQueuedItem(sess, item);
    } finally {
        sess._queueDraining = false;
    }
}
    window.drainMessageQueue = drainMessageQueue;

    function removeQueuedMessage(sess, id) {
    if (!sess || !sess.messageQueue) return null;
    for (var i = 0; i < sess.messageQueue.length; i++) {
        if (sess.messageQueue[i].id === id) {
            var removed = sess.messageQueue.splice(i, 1)[0];
            if (typeof renderQueueDock === 'function') renderQueueDock();
            if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
            if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
            return removed;
        }
    }
    return null;
    }

function editQueuedMessageToInput(sess, id) {
    if (!sess || !id) return;
    // 先检查草稿，确认后再出队，避免取消时丢队列项
    if (hasDraftInput()) {
        if (!window.confirm(I18n.t('streaming.overwriteDraftConfirm'))) return;
    }
    var item = removeQueuedMessage(sess, id);
    if (!item) return;
    applyQueuedItemToInput(item);
}

function cancelLastQueuedToInput(sess) {
    if (!sess || !sess.messageQueue || !sess.messageQueue.length) return false;
    var item = sess.messageQueue.pop();
    if (typeof renderQueueDock === 'function') renderQueueDock();
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
    applyQueuedItemToInput(item);
    return true;
        }

    function clearMessageQueue(sess) {
    if (!sess) return;
    sess.messageQueue = [];
    if (typeof renderQueueDock === 'function') renderQueueDock();
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
    }

    var _queueDockExpanded = false;

    function renderQueueDock() {
        var dock = document.getElementById('chatQueueDock');
        if (!dock) return;
        var sess = activeSessionId && sessionMap[activeSessionId];
        var q = (sess && sess.messageQueue) || [];
        // 折叠按钮角标：即使 strip 不可见也能感知排队数
        if (typeof window.updateFilerQueueBadge === 'function') {
            window.updateFilerQueueBadge(q.length);
        }
        if (!q.length) {
            dock.style.display = 'none';
            return;
        }
        // 右栏底部 strip：用 flex 布局，避免 display:block 破坏 workspace-panel 列排布
        dock.style.display = 'flex';
        if (_queueDockExpanded) $(dock).removeClass('collapsed');
        else $(dock).addClass('collapsed');

        var titleEl = document.getElementById('chatQueueTitle');
        if (titleEl) titleEl.textContent = String(q.length);

        var previewEl = document.getElementById('chatQueuePreview');
        if (previewEl) {
            previewEl.textContent = I18n.t('streaming.nextMessage') + truncateQueueText(q[0].displayText || q[0].text, 36);
            previewEl.style.display = _queueDockExpanded ? 'none' : 'block';
        }

        var toggleEl = document.getElementById('chatQueueToggle');
        if (toggleEl) {
            toggleEl.title = _queueDockExpanded ? I18n.t('streaming.collapse') : I18n.t('streaming.expand');
            toggleEl.setAttribute('aria-label', _queueDockExpanded ? I18n.t('streaming.collapse') : I18n.t('streaming.expand'));
            if (_queueDockExpanded) toggleEl.classList.add('expanded');
            else toggleEl.classList.remove('expanded');
        }

        var listEl = document.getElementById('chatQueueList');
        if (!listEl) return;
        var html = '';
        for (var i = 0; i < q.length; i++) {
            var item = q[i];
            var fileCount = (item.files && item.files.length) ? item.files.length : 0;
            var attachBadge = (fileCount > 0 || item.hasFiles)
                ? '<span class="queue-item-attach" title="' +
                    (fileCount > 0 ? I18n.t('streaming.attachCount', {n: fileCount}) : I18n.t('streaming.attachNotPersisted')) +
                    '">📎' + (fileCount > 0 ? fileCount : '!') + '</span>'
                : '';
            html += '<div class="queue-item" data-qid="' + escapeHtml(item.id) + '">' +
                '<span class="queue-item-idx">' + (i + 1) + '.</span>' +
                '<span class="queue-item-text" title="' + escapeHtml(item.displayText || item.text || '') + '">' +
                    escapeHtml(truncateQueueText(item.displayText || item.text, 48)) +
                '</span>' + attachBadge +
                '<span class="queue-item-actions">' +
                    '<button type="button" data-act="edit">' + I18n.t('streaming.edit') + '</button>' +
                    '<button type="button" data-act="cancel">' + I18n.t('common.cancel') + '</button>' +
                '</span></div>';
        }
        listEl.innerHTML = html;
    }
    window.renderQueueDock = renderQueueDock;

        function updateStreamingPlaceholder() {
    if (!chatInput) return;
    var sess = activeSessionId && sessionMap[activeSessionId];
    if (!sess) {
        chatInput.placeholder = I18n.t('welcome.inputPlaceholder');
        return;
    }
    if (sess.isStreaming) {
        if (sess.stopRequested) {
            chatInput.placeholder = I18n.t('streaming.stoppingPlaceholder');
            return;
        }
        var n = (sess.messageQueue || []).length;
        chatInput.placeholder = n > 0
            ? I18n.t('streaming.queuePosition', {n: n + 1})
            : I18n.t('streaming.taskRunningPlaceholder');
        return;
    }
    // 空闲但有任务排队：提示 Enter 续发（冷恢复后不自动发）
    var qn = (sess.messageQueue || []).length;
    if (qn > 0 && !sess.stopRequested && !sess._stoppedTurn) {
        chatInput.placeholder = I18n.t('streaming.queueWaiting', {n: qn});
        return;
    }
    chatInput.placeholder = I18n.t('welcome.inputPlaceholder');
            }
            window.updateStreamingPlaceholder = updateStreamingPlaceholder;

        // 任务排队 strip 事件（右栏底部，跨 Tab 常驻）—— DOM 就绪后绑定一次
        (function bindQueueDockEvents() {
            function bind() {
                var dock = document.getElementById('chatQueueDock');
                if (!dock || dock._queueBound) return;
                dock._queueBound = true;

                $(dock).on('click', '#chatQueueHeader', function(e) {
                    if ($(e.target).closest('#chatQueueClear, #chatQueueToggle').length) return;
                    _queueDockExpanded = !_queueDockExpanded;
                    renderQueueDock();
                });
                $(dock).on('click', '#chatQueueToggle', function(e) {
                    e.stopPropagation();
                    _queueDockExpanded = !_queueDockExpanded;
                    renderQueueDock();
                });
                $(dock).on('click', '#chatQueueClear', function(e) {
                    e.stopPropagation();
                    var sess = activeSessionId && sessionMap[activeSessionId];
                    if (!sess || !sess.messageQueue || !sess.messageQueue.length) return;
                    if (sess.messageQueue.length >= 3) {
                        if (!window.confirm(I18n.t('streaming.clearQueueConfirm', {n: sess.messageQueue.length}))) return;
                    }
                    clearMessageQueue(sess);
                });
                $(dock).on('click', '.queue-item-actions button', function(e) {
                    e.stopPropagation();
                    var act = $(this).attr('data-act');
                    var qid = $(this).closest('.queue-item').attr('data-qid');
                    var sess = activeSessionId && sessionMap[activeSessionId];
                    if (!sess || !qid) return;
                    if (act === 'edit') editQueuedMessageToInput(sess, qid);
                    else if (act === 'cancel') removeQueuedMessage(sess, qid);
                });
            }
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', bind);
            } else {
                bind();
            }
        })();

            /* ===== Send ===== */
        function sendMessage() {
    var text = getInputText();
    var streamSess = activeSessionId && sessionMap[activeSessionId];

    /* 空闲 + 有排队：允许空 Enter 续发队头；有内容则入队尾再 drain */
    if (streamSess && !streamSess.isStreaming
        && !streamSess.stopRequested && !streamSess._stoppedTurn
        && streamSess.messageQueue && streamSess.messageQueue.length) {
        if (!text && pendingFiles.length === 0) {
            drainMessageQueue(streamSess);
            chatInput.focus();
            return;
        }
        if (text && text.charAt(0) === '/') {
            showToast(I18n.t('streaming.clearQueueBeforeCommand'), 'error', 2000);
            return;
        }
        // 入队失败（如超限）时仍尝试 drain 已有队列，避免卡住
        enqueueMessage(streamSess, text, pendingFiles.slice());
        drainMessageQueue(streamSess);
        chatInput.focus();
        return;
    }

    if (!text && pendingFiles.length === 0) return;

    /* 活动会话 streaming：入队等待，不打断当前轮 */
    if (streamSess && streamSess.isStreaming) {
        if (streamSess.stopRequested) {
            showToast(I18n.t('streaming.stoppingWaitSend'), 'info', 1500);
            return;
        }
        // 斜杠命令不进排队，避免当普通气泡发出或语义错乱
        if (text && text.charAt(0) === '/') {
            showToast(I18n.t('streaming.stopBeforeCommand'), 'error', 2000);
            return;
        }
        enqueueMessage(streamSess, text, pendingFiles.slice());
        chatInput.focus();
        return;
    }

    /* /clear 命令：先发送到服务端清后端数据，流结束后再清前端 UI */
    if (text === '/clear') {
        clearInput();
        clearAttachmentPreview();
        if (!inChatMode) switchToChatMode();
        setActiveSession(SESSION_ID);
        var clearSess = sessionMap[SESSION_ID];
        if (clearSess) {
            clearSess._pendingClear = true;
            // /clear 会清会话，同步丢掉排队
            if (clearSess.messageQueue && clearSess.messageQueue.length) {
                clearMessageQueue(clearSess);
            }
            sendCommandSilent('/clear', null);
        }
        chatInput.focus();
        return;
    }

    var filesToSend = pendingFiles.slice(); // snapshot
    var displayText = buildDisplayText(text, filesToSend);

    clearInput();
    clearAttachmentPreview();

    // 统一由 sendMessageCore 负责 setActiveSession / 开流，避免重复调度 drain
    var sess = getOrCreateSession(SESSION_ID);
    sendMessageCore(sess, text, filesToSend, { displayText: displayText });
}

function sendWithFormData(sess, text, filesToSend) {
    sendWithFormDataGrouped(sess, text, filesToSend);
}

/* ===== 静默发送斜杠命令 =====
   与 sendMessage 不同：不渲染用户气泡（避免出现 "/rerun" 这样的丑斜杠文本），
   只进入流式等待态并发起命令。供最后一条 AI 消息的“重新运行/继续运行”按钮使用。
   onBeforeSend：发起前的同步回调（如清理旧 DOM）。 */
function sendCommandSilent(cmdText, onBeforeSend) {
    if (!activeSessionId || !sessionMap[activeSessionId]) return;
    var sess = sessionMap[activeSessionId];
    /* 流式进行中禁止重复触发 */
    if (sess.isStreaming) return;
    // 有排队时禁止静默命令插队（/clear 由 sendMessage 先清队列再调用）
    if (sess.messageQueue && sess.messageQueue.length) {
        if (typeof showToast === 'function') {
            showToast(I18n.t('streaming.clearQueueBeforeCommand'), 'error', 2000);
        }
        return;
    }
    if (sess.stopRequested || sess._stoppedTurn) {
        if (typeof showToast === 'function') {
            showToast(I18n.t('streaming.stoppingWaitRetry'), 'info', 1500);
        }
        return;
    }

    if (typeof onBeforeSend === 'function') {
        try { onBeforeSend(sess); } catch (e) {}
    }

    if (!inChatMode) switchToChatMode();

    // 先标记 streaming，再 setActiveSession，避免会话切换时误触发 drain
    sess.isStreaming = true;
    sess.stopRequested = false;
    sess._stoppedTurn = false;
    sess.acceptingStream = true;
    sess._streamClosed = false;
    isStreaming = true;
    sess.messageStartTime = Date.now();
    setActiveSession(sess.sessionId);
    setBtnStopMode();
    resetStreamState(sess);
    showThinking(sess);
    if (typeof startRoundElapsed === 'function') startRoundElapsed(sess);
    if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();

    sendWithFormDataGrouped(sess, cmdText, []);
}
window.sendCommandSilent = sendCommandSilent;

function sendWithFormDataGrouped(sess, text, filesToSend, options) {
    options = options || {};
    if (sess.eventSource) { sess.eventSource.close(); sess.eventSource = null; }
    var model = (options.model !== undefined && options.model !== null)
        ? options.model
        : getSelectedModel();
    var formData = new FormData();
    formData.append('input', text);
    formData.append('sessionId', sess.sessionId);
    if (model) formData.append('model', model);
    var effort = (options.reasoningEffort !== undefined && options.reasoningEffort !== null)
        ? options.reasoningEffort
        : (typeof getSelectedReasoning === 'function' ? getSelectedReasoning() : '');
    if (effort) formData.append('reasoningEffort', effort);
    // 思考模式独立参数：仅显式 on/off 时携带（'' 不干预，跟随模型/effort 默认）
    var thinking = (options.thinkingMode !== undefined && options.thinkingMode !== null)
        ? options.thinkingMode
        : (typeof getSelectedThinking === 'function' ? getSelectedThinking() : '');
    if (thinking) formData.append('thinkingMode', thinking);
    var selectedAgent = options.selectedAgent;
    if (selectedAgent === undefined && typeof getSelectedAgent === 'function') {
        selectedAgent = getSelectedAgent();
    }
    if (selectedAgent) formData.append('selectedAgent', selectedAgent);
    for (var i = 0; i < filesToSend.length; i++) {
        formData.append('attachments', filesToSend[i].file, filesToSend[i].name);
        formData.append('attachmentTypes', filesToSend[i].attachmentsType || 'file');
    }

    // 标记流式状态，WebSocket onmessage 会处理数据
    sess.isStreaming = true;
    sess.stopRequested = false;
    sess.acceptingStream = true;
    sess._streamClosed = false;
    if (!sess.messageStartTime) sess.messageStartTime = Date.now();
    if (sess.sessionId === activeSessionId) {
        isStreaming = true;
        setBtnStopMode();
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    }
    resetStreamState(sess);
    showThinking(sess);
    // 兜底起表（外部推送 / 未走 sendMessage 的入口）；已 start 则不重置
    if (typeof startRoundElapsed === 'function') startRoundElapsed(sess);

    $.ajax({
        url: SSE_ENDPOINT,
        method: 'POST',
        data: formData,
        processData: false,
        contentType: false
    }).done(function() {
        // HTTP 响应只有 {"status":"ok"}，实际数据通过 WebSocket 推送
    }).fail(function(err) {
        console.error('Send error:', err);
        finishStream(sess);
    });
}
window.sendWithFormDataGrouped = sendWithFormDataGrouped;

/* ===== WebEvent / SAEP 2.0 Native Dispatcher (Session-Aware) =====
 * 基于 SAEP 2.0 规范的原生事件分发架构，直接解构 event + payload
 */
var AgentEventDispatcher = {
    // 统一将输入归一化为 SAEP 2.0 WebEvent 结构
    toWebEvent(raw) {
        if (!raw || !raw.event) return null;
        return {
            event: raw.event,
            sessionId: raw.sessionId,
            runId: raw.runId,
            taskId: raw.taskId,
            reasonId: raw.reasonId,
            agentName: raw.agentName,
            timestamp: raw.timestamp || Date.now(),
            payload: raw.payload || {}
        };
    }
};

var _STREAM_BATCH_EVENTS = {
    'message.delta': 1,
    'thought.delta': 1
};

function processWebEventNow(sess, webEvt) {
    if (!webEvt || !webEvt.event) return;
    try {
        if (sess.silenceTimer) {
            clearTimeout(sess.silenceTimer);
        }

        removeInlineThinking(sess);

        var event = webEvt.event;
        var p = webEvt.payload || {};
        var taskId = webEvt.taskId;
        var reasonId = webEvt.reasonId;
        var agentName = webEvt.agentName;

        // 存储当前 runId
        if (webEvt.runId) {
            sess.currentRunId = webEvt.runId;
        }

        // 捕获消息来源标识
        if (p.sourceLabel && !sess.currentSourceLabel) {
            sess.currentSourceLabel = p.sourceLabel;
        }

        // 确定是否需要创建或定位 streamSegment
        var isVisualEvent = (event === 'message.delta' || event === 'thought.delta' || event === 'tool.start');
        var isActionEndWithoutPending = (event === 'tool.end' && !findPendingToolCard(sess, p.callId, null).pending);
        var segment = null;

        if (isVisualEvent || isActionEndWithoutPending) {
            segment = ensureStreamSegment(sess, taskId, p.taskDescription || p.title, agentName);
        } else if (event === 'tool.end' && taskId && sess.taskSegments[taskId]) {
            segment = sess.taskSegments[taskId];
            sess.currentStreamSegment = segment;
        }

        var sourceEl = null;

        switch (event) {
            case 'message.delta':
                sourceEl = appendContentChunk(sess, segment, p.delta || p.content || '', true, reasonId);
                break;

            case 'thought.delta':
                sourceEl = appendReasonChunk(sess, segment, p.delta || '', reasonId, agentName);
                break;

            case 'tool.start':
                sourceEl = appendActionStartChunk(sess, segment, p.name, p.args, p.title || p.name, reasonId, agentName, p.callId);
                break;

            case 'tool.end':
                var toolArgs = p.args || (p.diff ? { diff: p.diff } : {});
                if (p.diff && !toolArgs.diff) toolArgs.diff = p.diff;
                sourceEl = appendActionEndChunk(sess, segment, p.name, p.result || '', toolArgs, p.title || p.name, reasonId, agentName, p.callId);
                if (window._todoChunkHandlers) {
                    var todoEvent = { toolName: p.name, text: p.result, args: toolArgs };
                    window._todoChunkHandlers.forEach(function(h) { h(todoEvent); });
                }
                break;

            case 'hitl.pending':
                finishThinkingBlock(sess);
                finishPendingTool(sess);
                sourceEl = appendHitlCard(sess, p.toolName || p.name, p.command, p.callId, p.args, p.toolTitle || p.title || p.toolName, p.comment);
                break;

            case 'task.done':
                if (typeof applyTaskDoneChunk === 'function') {
                    applyTaskDoneChunk(sess, {
                        taskId: p.taskId || taskId,
                        parentTaskId: p.parentTaskId,
                        status: p.status,
                        taskDescription: p.title || p.taskDescription
                    });
                }
                sourceEl = segment && segment.groupEl;
                break;

            case 'system.trace':
                finishThinkingBlock(sess);
                finishPendingTool(sess);
                sourceEl = appendTraceBadge(sess, { model: p.model, totalTokens: p.totalTokens, elapsedSeconds: p.elapsedSeconds, text: p.finalAnswer });
                break;

            case 'system.context':
                if (typeof updateContextIndicator === 'function') {
                    // updateContextIndicator 读取 totalTokens 与 args.contextLength/args.cacheRate，
                    // 必须按该结构透传，否则上下文条恒显 "/ 0 (0%)" 且 Cache% 丢失。
                    updateContextIndicator({
                        totalTokens: p.tokens,
                        args: {
                            contextLength: p.contextLimit,
                            cacheRate: p.cacheRate
                        }
                    }, sess);
                }
                break;

            case 'system.command':
                finishThinkingBlock(sess);
                finishPendingTool(sess);
                sourceEl = appendCommandOutput(sess, p.command);
                break;

            case 'system.rewind':
                finishThinkingBlock(sess);
                finishPendingTool(sess);
                handleRewind(sess, p.count || 1);
                break;

            case 'system.error':
                finishThinkingBlock(sess);
                sourceEl = appendErrorChunk(sess, p.message || '未知错误', taskId, p.title, agentName, p.code);
                break;
        }

        // task-group 展开状态更新
        if (segment && segment.taskId && event !== 'task.done') {
            markTaskGroupUpdated(sess, segment);
        }

        if (event !== 'system.rewind' && event !== 'system.context') {
            scrollForStreamEvent(sess, { type: event }, sourceEl, false);
        }

        sess.silenceTimer = setTimeout(function() {
            if (sess.isStreaming && !sess.thinkingBlockEl) showInlineThinking(sess);
        }, 1000);
    } catch (e) {
        console.warn('[processWebEventNow]', e);
    }
}

/* 合并同类型连续 message.delta / thought.delta：减少 DOM 调度次数，保持事件保序 */
function coalesceQueuedEvents(queue) {
    if (!queue || queue.length <= 1) return queue || [];
    var out = [];
    for (var i = 0; i < queue.length; i++) {
        var e = queue[i];
        var prev = out.length ? out[out.length - 1] : null;
        var isMergeable = prev && prev.event === e.event && (e.event === 'message.delta' || e.event === 'thought.delta')
            && prev.reasonId === e.reasonId
            && prev.taskId === e.taskId
            && prev.agentName === e.agentName
            && prev.runId === e.runId;

        if (isMergeable) {
            prev.payload.delta = (prev.payload.delta || '') + (e.payload.delta || '');
            if (!prev.payload.sourceLabel && e.payload.sourceLabel) prev.payload.sourceLabel = e.payload.sourceLabel;
            if (!prev.runId && e.runId) prev.runId = e.runId;
            if (!prev.agentName && e.agentName) prev.agentName = e.agentName;
        } else {
            out.push(e);
        }
    }
    return out;
}

    function drainWebEventQueue(sess, flushAll) {
    if (!sess || !sess._chunkQueue || !sess._chunkQueue.length) {
        if (sess) sess._chunkDrainScheduled = false;
        return;
    }
    sess._chunkDrainScheduled = false;
    var batch = coalesceQueuedEvents(sess._chunkQueue);
    sess._chunkQueue = [];
    // 非 flush 时每帧最多处理一定数量，避免超长队列堵主线程
    var limit = flushAll ? batch.length : Math.min(batch.length, 40);
    for (var i = 0; i < limit; i++) {
        processWebEventNow(sess, batch[i]);
    }
    if (limit < batch.length) {
        sess._chunkQueue = batch.slice(limit).concat(sess._chunkQueue || []);
        scheduleWebEventDrain(sess);
    }
}
    window.drainWebEventQueue = drainWebEventQueue;

    function scheduleWebEventDrain(sess) {
        if (!sess || sess._chunkDrainScheduled) return;
        sess._chunkDrainScheduled = true;
        requestAnimationFrame(function() {
            drainWebEventQueue(sess, false);
        });
    }
    
    function onWebEvent(sess, raw) {
        if (!sess || !raw) return;
        var webEvt = AgentEventDispatcher.toWebEvent(raw);
        if (!webEvt) return;
        
        // 高频流式文本走队列批处理；控制类消息立即处理（先排空队列保序）
        if (_STREAM_BATCH_EVENTS[webEvt.event]) {
            if (!sess._chunkQueue) sess._chunkQueue = [];
            sess._chunkQueue.push(webEvt);
            scheduleWebEventDrain(sess);
            return;
        }
        if (sess._chunkQueue && sess._chunkQueue.length) {
            drainWebEventQueue(sess, true);
        }
        processWebEventNow(sess, webEvt);
    }

function finishStream(sess) {
    var wasStreaming = sess.isStreaming;
    // 必须在复位 stopRequested 前读取：Stop 窗口期入队/结束后误 drain 的防护
    var wasStopped = !!sess.stopRequested || !!sess._stoppedTurn;
    sess.isStreaming = false;
    sess.stopRequested = false;
    // 关闭流接收：done 之后的迟到 chunk 不得再把 UI 拉起
    sess.acceptingStream = false;
    // 仅标记“本页本轮已收尾”，刷新后不会带上该标记
    sess._streamClosed = true;
    sess._pendingStreamChunks = null;
    if (sess._stopFallbackTimer) {
        clearTimeout(sess._stopFallbackTimer);
        sess._stopFallbackTimer = null;
    }
    if (sess.silenceTimer) { clearTimeout(sess.silenceTimer); sess.silenceTimer = null; }

    // 先排空该会话尚未处理的 chunk 队列，避免丢尾部文本
        if (typeof drainWebEventQueue === 'function') drainWebEventQueue(sess, true);

    // --- 强刷逻辑：必须在 resetStreamState 之前执行 ---
    // 1. 取消还没跑的动画帧
    if (sess.contentRafId) { cancelAnimationFrame(sess.contentRafId); sess.contentRafId = null; }
    if (sess.reasonRafId) { cancelAnimationFrame(sess.reasonRafId); sess.reasonRafId = null; }
    // Cancel per-reasonId RAF IDs
    for (var _rid in sess.reasonGroups) {
        if (sess.reasonGroups[_rid].reasonRafId) {
            cancelAnimationFrame(sess.reasonGroups[_rid].reasonRafId);
            sess.reasonGroups[_rid].reasonRafId = null;
        }
        if (sess.reasonGroups[_rid].groupRafId) {
            cancelAnimationFrame(sess.reasonGroups[_rid].groupRafId);
            sess.reasonGroups[_rid].groupRafId = null;
        }
    }

    // 2. 取消文本 run 的待执行 RAF（真正的 Markdown 升级交给 finishThinkingBlock / 下方统一 finalize）
    if (sess.reasonBuffer) {
        // 旧路径：无 reasonGroups 时可能直接写在 bubble 上
        var el = ensureAssistantBubble(sess);
        if (typeof finalizeMdElement === 'function') finalizeMdElement(el, sess.reasonBuffer);
        else {
            el.setAttribute('data-md-raw', sess.reasonBuffer);
            el.innerHTML = renderMd(sess.reasonBuffer);
        }
    }
    for (var _rid in sess.reasonGroups) {
        var group = sess.reasonGroups[_rid];
        if (group.textRuns && group.textRuns.length) {
            for (var ri = 0; ri < group.textRuns.length; ri++) {
                var run = group.textRuns[ri];
                if (run.rafId) { cancelAnimationFrame(run.rafId); run.rafId = null; }
                // 仅升级文本 run；思考块留给 finishThinkingBlock，避免双重 marked
                if (run.el && run.buffer) {
                    if (typeof finalizeMdElement === 'function') finalizeMdElement(run.el, run.buffer);
                    else {
                        run.el.setAttribute('data-md-raw', run.buffer);
                        run.el.innerHTML = renderMd(run.buffer);
                    }
                }
            }
        } else if (group.groupContentEl && group.groupBuffer) {
            if (typeof finalizeMdElement === 'function') finalizeMdElement(group.groupContentEl, group.groupBuffer);
            else {
                group.groupContentEl.setAttribute('data-md-raw', group.groupBuffer);
                group.groupContentEl.innerHTML = renderMd(group.groupBuffer);
            }
        }
    }
    // ---------------------------------------------------

    removeThinking(sess);
    purgeInlineThinking(sess);
    // 关闭所有未完成的 reasonId 分组思考块（内部会 finalize 一次思考内容）
    for (var _rid in sess.reasonGroups) {
        if (sess.reasonGroups[_rid].thinkingBlockEl) {
            finishThinkingBlock(sess, _rid);
        }
    }
    finishThinkingBlock(sess);
    finishPendingTool(sess);
    sess.hitlApprovedCards = {};

    // 结算全部 task-group：非 error → done（绿勾）；error 保留红叉
    if (typeof finalizeTaskGroups === 'function') finalizeTaskGroups(sess);
    // 本轮总计时定格（Context 条）
    if (typeof stopRoundElapsed === 'function') stopRoundElapsed(sess);

    if (sess.eventSource) { sess.eventSource.close(); sess.eventSource = null; }

    // 保存行引用（currentBubbleEl 可能在后续清理中被移除）
    var doneRow = sess.currentBubbleEl ? $(sess.currentBubbleEl).closest('.msg-row')[0] : null;

    // 条件显示助手消息时间戳：仅当有实际文本输出时才显示
    var hasTextOutput = !!(sess.reasonBuffer && sess.reasonBuffer.trim());
    if (!hasTextOutput && doneRow) {
        $(doneRow).find('.msg-bubble .md-content').each(function() {
            if (this.getAttribute('data-md-raw') || (this.innerText && this.innerText.trim())) {
                hasTextOutput = true;
                return false;
            }
        });
    }
    if (hasTextOutput) {
        setAssistantTime(sess, sess._lastCreatedAt || Date.now());
    }
    sess._lastCreatedAt = null;

    // 流式结束：切换 class 并显示操作按钮
    if (doneRow) {
        $(doneRow).removeClass('streaming').addClass('done');
        $(doneRow).find('.msg-actions').show();
    }

    // 清理未落到任何实际内容块的前置空白缓存：这些空白没有对应的服务端正文，
    // 不应创建 DOM；已关联到正文的空白此前会原样拼回 buffer，绝不 trim。
    sess.pendingReasonWhitespace = {};
    sess.pendingGroupWhitespace = {};
    sess.pendingThinkingWhitespace = '';

    // 清理空的 md-content 节点（无 data-md-raw、无实际文本、无子元素）。
    // trim 仅用于判断 DOM 是否可回收，不会回写或修改服务端流式内容。
    if (doneRow) {
        $(doneRow).find('.msg-bubble .md-content').each(function() {
            if (!this.getAttribute('data-md-raw') && (!this.innerText || !this.innerText.trim()) && !$(this).children().length) {
                $(this).remove();
            }
        });
        // 先移除没有实际内容的思考块外壳。仅按 reason-group 的直接子节点数判断，
        // 会把包含空 reason-group-think 的分组误判为非空而残留。
        $(doneRow).find('.reason-group > .reason-group-think').each(function() {
            var body = $(this).find('.reason-group-think-body')[0];
            var hasContent = body && ((body.textContent && /\S/.test(body.textContent)) || $(body).children().length);
            if (!hasContent) {
                $(this).remove();
            }
        });
        // 回收没有任何实际子内容的 reason-group，避免留下空白边框。
        $(doneRow).find('.reason-group').each(function() {
            if (!$(this).children().length) {
                $(this).remove();
            }
        });
        // 空 task-group 仅可能由上一步移除最后一个 reason-group 产生，随即一并回收。
        $(doneRow).find('.task-group').each(function() {
            if (!$(this).find('.task-group-body').children().length) {
                $(this).remove();
            }
        });
    }

    // 清除客户端计时（已由 trace 类型的服务端耗时替代）
    if (sess.messageStartTime) {
        sess.messageStartTime = null;
    }

    // 清除消息来源标识，避免污染下一条流式响应
    sess.currentSourceLabel = null;

    // /clear 命令处理完毕：清空前端对话 UI
    if (sess._pendingClear) {
        sess._pendingClear = false;
        sess.messageQueue = [];
        if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
        if (sess.sessionId === activeSessionId && typeof renderQueueDock === 'function') {
            renderQueueDock();
        }
        $(sess.container).empty();
        sess.currentBubbleEl = null;
        sess.reasonBuffer = '';
        sess.thinkingBuffer = '';
        sess.thinkingBlockEl = null;
        sess.thinkingBodyMdEl = null;
        sess.thinkingBodyWrapEl = null;
        sess.pendingToolCard = null;
        sess.pendingToolStarted = false;
        sess.hitlApprovedCards = {};
        sess.userMsgCounter = 0;
        // 清空后不再展示上轮 Context / 总计时
        sess.roundStartedAt = null;
        sess.roundEndedAt = null;
        sess.contextTokens = null;
        sess.contextLength = null;
        if (sess.sessionId === activeSessionId && typeof resetContextIndicator === 'function') {
            resetContextIndicator();
        }
    }

    // resetStreamState 会清空 buffer，所以必须在上面强刷完后再调
    resetStreamState(sess);

    if (sess.sessionId === activeSessionId) {
        isStreaming = false;
        setBtnSendMode();
        // 只有在活动会话才滚动
        if (shouldScheduleSessionScroll(sess)) {
            scrollToBottom(true);
        }
        chatInput.focus();
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    }

    // 刷新任务面板
    if (window.loadTodos) window.loadTodos();

    // 任务完成通知（页面在后台时弹通知 + 播放提示音）
    setTimeout(window._notifyTaskComplete, 500);

    // 当前轮结束后按 FIFO 自动发送排队消息（仅 active 会话）
    // setTimeout(0) 让本轮 UI 先收尾，避免与 setBtnSendMode 竞态
    if (wasStopped) {
        // Stop 收尾：丢弃残留排队，避免停止后仍自动续发
        if (sess.messageQueue && sess.messageQueue.length) {
            sess.messageQueue = [];
            if (typeof schedulePersistMessageQueue === 'function') schedulePersistMessageQueue(sess);
            if (sess.sessionId === activeSessionId && typeof renderQueueDock === 'function') {
                renderQueueDock();
            }
        }
        sess._stoppedTurn = false;
    } else if (sess.messageQueue && sess.messageQueue.length) {
        setTimeout(function() {
            if (typeof drainMessageQueue === 'function') drainMessageQueue(sess);
        }, 0);
    } else {
        sess._stoppedTurn = false;
    }
}
window.finishStream = finishStream;

/* ===== WebSocket 单连接 ===== */
var webGateSocket = null;
var webGateReconnectAttempts = 0;
var webGateHeartbeatTimer = null;
var WEBGATE_MAX_RECONNECT = 10;
var WEBGATE_PENDING_CHUNK_MAX = 300;

/* 历史消息加载期间先缓存流式 chunk，加载完再回放，避免被 DOM 重建冲掉 */
function bufferPendingStreamChunk(sess, chunk) {
    if (!sess || !chunk) return;
    if (!sess._pendingStreamChunks) sess._pendingStreamChunks = [];
    if (sess._pendingStreamChunks.length >= WEBGATE_PENDING_CHUNK_MAX) {
        sess._pendingStreamChunks.shift();
    }
    sess._pendingStreamChunks.push(chunk);
}

function flushPendingStreamChunks(sess) {
    var buf = sess && sess._pendingStreamChunks;
    if (!sess) return;
    sess._pendingStreamChunks = null;
    if (!buf || !buf.length) return;
    for (var i = 0; i < buf.length; i++) {
        handleWebGateChunk(buf[i]);
    }
}
window.flushPendingStreamChunks = flushPendingStreamChunks;

/** 有流式消息到来时，打开本会话的接收/展示状态 */
function openStreamFromIncoming(sess) {
    if (!sess || sess.stopRequested) return false;
    sess._streamClosed = false;
    sess.acceptingStream = true;
    if (sess.isStreaming) return true;
    sess.isStreaming = true;
    sess.stopRequested = false;
    if (!sess.messageStartTime) sess.messageStartTime = Date.now();
    if (sess.sessionId === activeSessionId) {
        isStreaming = true;
        setBtnStopMode();
        if (!inChatMode) switchToChatMode();
        if (typeof updateStreamingPlaceholder === 'function') updateStreamingPlaceholder();
    }
    resetStreamState(sess);
    showThinking(sess);
    if (typeof startRoundElapsed === 'function') startRoundElapsed(sess);
    if (typeof updateHistoryUI === 'function') updateHistoryUI();
    return true;
}

function handleWebGateChunk(raw) {
    if (!raw) return;

    var webEvt = AgentEventDispatcher.toWebEvent(raw);
    if (!webEvt) return;

    var sid = webEvt.sessionId;
    var event = webEvt.event;
    var p = webEvt.payload || {};

    // WebSocket 流结束信号
    if (event === 'system.done') {
        if (!sid) return;
        var sess = sessionMap[sid] || getOrCreateSession(sid);
        if (p.createdAt) sess._lastCreatedAt = p.createdAt;
        // 历史还在加载：先缓存，加载完再收尾
        if (sess._loadingHistory) {
            bufferPendingStreamChunk(sess, webEvt);
            return;
        }
        if (!sess.isStreaming) return;
        finishStream(sess);
        return;
    }

    // 文件变更通知（无 sessionId，系统级广播）
    if (event === 'system.filer_change') {
        if (typeof onFilerChange === 'function') {
            onFilerChange(p);
        }
        return;
    }

    if (!sid) return;

    // 即使 sess 不存在，也优先处理 todowrite（更新左侧 todo 进度）
    if (event === 'tool.end' && p.name === 'todowrite') {
        if (window._todoChunkHandlers) {
            var todoEvent = { toolName: p.name, text: p.result, args: p.args };
            window._todoChunkHandlers.forEach(function(h) { h(todoEvent); });
        }
    }

    // Loop/Goal 异步 agent 流启动信号：重置流关闭状态，使后续文本能够正常开新气泡
    if (event === 'system.reset') {
        var sess = getOrCreateSession(sid);
        sess._streamClosed = false;
        sess.acceptingStream = true;
        sess.stopRequested = false;
        return;
    }

    // Loop/微信 等后端推送的用户提示词
    if (event === 'system.user_input') {
        var userSess = getOrCreateSession(sid);
        userSess._streamClosed = false;
        userSess.acceptingStream = true;
        userSess.stopRequested = false;
        if (typeof ensureChatInHistory === 'function') {
            ensureChatInHistory(sid, p.text, true);
        }
        appendUserMessage(userSess, p.text, null, null, p.createdAt, p.sourceLabel);
        if (userSess.sessionId === activeSessionId) {
            if (!inChatMode) switchToChatMode();
            scrollToBottom(true);
        }
        return;
    }

    var sess2 = getOrCreateSession(sid);

    // 历史加载中：先缓存，避免 loadMessages 重建 DOM 时丢内容
    if (sess2._loadingHistory) {
        bufferPendingStreamChunk(sess2, webEvt);
        return;
    }

    if (!sess2.isStreaming) {
        if (sess2.stopRequested) return;
        // 本页已正常 finishStream 的迟到包丢弃；刷新后 _streamClosed 未设置，有流就显示
        if (!sess2.acceptingStream) {
            if (sess2._streamClosed) return;
            if (!openStreamFromIncoming(sess2)) return;
        } else if (!openStreamFromIncoming(sess2)) {
            return;
        }
    }
                onWebEvent(sess2, webEvt);
}

function connectWebGate() {
    if (webGateSocket && webGateSocket.readyState === WebSocket.OPEN) return;
    try {
        var protocol = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
        var wsUrl = protocol + '//' + window.location.host + '/web/gate?_t=1' + window.wsAndSuffix();
        webGateSocket = new WebSocket(wsUrl);
    } catch(e) {
        console.error('[WebGate] create failed:', e);
        scheduleWebGateReconnect();
        return;
    }

    webGateSocket.onopen = function() {
        console.log('[WebGate] connected');
        webGateReconnectAttempts = 0;
        startWebGateHeartbeat();
        hideNetworkBar();
        // 重连后刷新文件树
        if (typeof loadTree === 'function') {
            loadTree();
        }
    };

    webGateSocket.onmessage = function(event) {
        var raw = event.data;
        if (raw === 'pong') return; // 心跳回复
        try {
            handleWebGateChunk(JSON.parse(raw));
        } catch(e) {
            // 非 JSON 消息忽略
        }
    };

    webGateSocket.onclose = function() {
        console.log('[WebGate] closed');
        stopWebGateHeartbeat();
        showNetworkBar('disconnected', I18n.t('streaming.wsDisconnected'));
        scheduleWebGateReconnect();
    };

    webGateSocket.onerror = function(err) {
        console.error('[WebGate] error:', err);
    };
}

function startWebGateHeartbeat() {
    stopWebGateHeartbeat();
    webGateHeartbeatTimer = setInterval(function() {
        if (webGateSocket && webGateSocket.readyState === WebSocket.OPEN) {
            webGateSocket.send('ping');
        }
    }, 15000);
}

function stopWebGateHeartbeat() {
    if (webGateHeartbeatTimer) {
        clearInterval(webGateHeartbeatTimer);
        webGateHeartbeatTimer = null;
    }
}

function scheduleWebGateReconnect() {
    if (webGateReconnectAttempts >= WEBGATE_MAX_RECONNECT) {
        console.warn('[WebGate] max reconnect attempts reached');
        return;
    }
    var delay = Math.min(1000 * Math.pow(2, webGateReconnectAttempts), 30000);
    webGateReconnectAttempts++;
    console.log('[WebGate] reconnecting in ' + delay + 'ms (attempt ' + webGateReconnectAttempts + ')');
    showNetworkBar('reconnecting', I18n.t('streaming.wsReconnecting', {attempt: webGateReconnectAttempts, max: WEBGATE_MAX_RECONNECT}));
    setTimeout(function() {
        connectWebGate();
    }, delay);
}

// 页面可见性控制心跳
$(document).on('visibilitychange', function() {
    if (document.hidden) {
        stopWebGateHeartbeat();
    } else {
        startWebGateHeartbeat();
    }
});

// 页面加载后自动建立 WebSocket 连接
connectWebGate();

/* ===== WeChat ClawBot Channel ===== */
var wechatHeaderBtn = $('#wechatHeaderBtn');
var wechatHeaderLabel = $('#wechatHeaderLabel');
var wechatModalOverlay = null;
var wechatPollTimer = null;

function updateWechatUI() {
    if (!activeSessionId) return;
    $.get('/web/chat/wechat/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
        try {
            var bound = resp.data && resp.data.bound;
            wechatHeaderBtn.toggleClass('bound', !!bound);
            wechatHeaderLabel.text(bound ? I18n.t('im.connected') : '');
            wechatHeaderBtn.attr('title', bound ? I18n.t('im.wechatBoundUnbind') : I18n.t('im.wechatBind'));
        } catch(e) {}
    }, 'json');
}

// 首屏：IM 状态属于次要请求，延后到空闲时再拉，避免与 sessions/meta/ws 抢带宽
function scheduleIdle(fn, timeoutMs) {
    if (window.requestIdleCallback) {
        requestIdleCallback(function() { fn(); }, { timeout: timeoutMs || 2000 });
    } else {
        setTimeout(fn, timeoutMs || 800);
    }
}
scheduleIdle(function() {
    updateWechatUI();
    updateFeishuUI();
    updateDingTalkUI();
}, 1500);

var origSetActiveSession = setActiveSession;
var _sessionSwitchTimer = null;
setActiveSession = function(sid) {
    origSetActiveSession(sid);
    if (_sessionSwitchTimer) {
        clearTimeout(_sessionSwitchTimer);
    }
    // 将非关键请求延迟执行，让 UI 先完成切换
    _sessionSwitchTimer = setTimeout(function() {
        _sessionSwitchTimer = null;
        updateWechatUI();
        updateFeishuUI();
        updateDingTalkUI();
        // 切换会话时刷新任务面板
        if (window.loadTodos) window.loadTodos();
        // 切换会话时恢复任务排队（queue-tasks.json，冷恢复不自动发送）
        var qSess = sessionMap[sid];
        if (qSess && typeof window.loadMessageQueue === 'function') {
            window.loadMessageQueue(qSess);
        }
        // 切换会话时重置上下文指示器
        if (typeof resetContextIndicator === 'function') resetContextIndicator();
    }, 50);
};

wechatHeaderBtn.on('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (wechatHeaderBtn.hasClass('bound')) {
        layer.confirm(I18n.t('im.wechatUnbindConfirm'), { title: I18n.t('im.confirmUnbind'), btn: [I18n.t('im.unbind'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            $.post('/web/chat/wechat/unbind?sessionId=' + encodeURIComponent(activeSessionId)).always(function() {
                updateWechatUI();
            });
        });
        return;
    }
    // Not bound: show QR modal
    showWechatModal();
});

function showWechatModal() {
    if (wechatModalOverlay) return;

    wechatModalOverlay = $('<div>').addClass('wechat-modal-overlay').html(
        '<div class="wechat-modal">'
        + '<div class="wechat-modal-title">' + I18n.t('im.wechatScanBind') + '</div>'
        + '<div class="wechat-modal-subtitle">' + I18n.t('im.wechatScanSubtitle') + '</div>'
        + '<div class="wechat-qr-wrap" id="wechatQrWrap"><span style="color:#999;font-size:13px">' + I18n.t('common.loading') + '...</span></div>'
        + '<div class="wechat-status" id="wechatQrStatus">' + I18n.t('im.waitingScan') + '...</div>'
        + '<div class="im-bind-hint">' + I18n.t('im.wechatBindHint') + '</div>'
        + '<button class="wechat-modal-close" id="wechatModalClose">' + I18n.t('common.cancel') + '</button>'
        + '</div>'
    );
    $('body').append(wechatModalOverlay);

    $('#wechatModalClose').on('click', closeWechatModal);
    wechatModalOverlay.on('click', function(e) {
        if ($(e.target).is(wechatModalOverlay)) closeWechatModal();
    });

    // Fetch QR code
    $.get('/web/chat/wechat/qrcode?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
        try {
            if (resp.code !== 200 || !resp.data) {
                $('#wechatQrStatus').text(resp.message || I18n.t('im.qrcodeFailed')).addClass('error');
                return;
            }
            var $qrWrap = $('#wechatQrWrap');
            $qrWrap.html('');
            var qrContent = resp.data.qrcode_img_content || resp.data.qrcode;
            if (qrContent) {
                var renderQr = function(err) {
                    if (err || typeof QRCode === 'undefined') {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px">' + escapeHtml(qrContent) + '</span>');
                        return;
                    }
                    try {
                        new QRCode($qrWrap[0], { text: qrContent, width: 180, height: 180 });
                    } catch(e) {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px">' + escapeHtml(qrContent) + '</span>');
                    }
                };
                if (typeof ensureQrcode === 'function') ensureQrcode(renderQr);
                else renderQr(null);
            }
            // Start polling
            startWechatPoll(resp.data.qrcode, activeSessionId);
        } catch(e) {
            $('#wechatQrStatus').text(I18n.t('im.parseFailed')).addClass('error');
        }
    }, 'json');
}

function startWechatPoll(qrcode, sessionId) {
    if (wechatPollTimer) clearInterval(wechatPollTimer);
    wechatPollTimer = setInterval(function() {
        $.get('/web/chat/wechat/qrcode/status?qrcode=' + encodeURIComponent(qrcode) + '&sessionId=' + encodeURIComponent(sessionId), function(resp) {
            try {
                var data = resp.data || {};
                var $statusEl = $('#wechatQrStatus');
                if (!$statusEl.length) return;

                var status = data.status;
                if (status === 'wait') {
                    $statusEl.text(I18n.t('im.waitingScan') + '...').removeClass('error scanned');
                } else if (status === 'scaned') {
                    $statusEl.text(I18n.t('im.scannedConfirm')).removeClass('error').addClass('scanned');
                } else if (status === 'confirmed') {
                    $statusEl.text(I18n.t('im.connectSuccess')).removeClass('error').addClass('scanned');
                    clearInterval(wechatPollTimer);
                    wechatPollTimer = null;
                    setTimeout(function() {
                        closeWechatModal();
                        updateWechatUI();
                        switchToChatMode();
                        var initSess = getOrCreateSession(SESSION_ID);
                        if (!initSess._wechatInited) {
                            initSess._wechatInited = true;
                            appendSystemNotice(initSess, I18n.t('im.wechatConnectedNotice'));
                        }
                    }, 1200);
                } else if (status === 'expired') {
                    $statusEl.text(I18n.t('im.qrcodeExpired')).removeClass('scanned').addClass('error');
                    clearInterval(wechatPollTimer);
                    wechatPollTimer = null;
                } else {
                    // 临时错误或未知状态：继续轮询，扫码过程中的API短暂波动不应打断流程
                    if (wechatPollTimer) {
                        $statusEl.text(I18n.t('im.scanProcessing') + '...').removeClass('error scanned');
                    }
                }
            } catch(e) {}
        }, 'json');
    }, 2000);
}

function closeWechatModal() {
    if (wechatPollTimer) { clearInterval(wechatPollTimer); wechatPollTimer = null; }
    if (wechatModalOverlay) {
        wechatModalOverlay.remove();
        wechatModalOverlay = null;
    }
}

/* ===== Feishu Channel ===== */
var feishuHeaderBtn = $('#feishuHeaderBtn');
var feishuHeaderLabel = $('#feishuHeaderLabel');
var feishuModalOverlay = null;
var feishuPollTimer = null;

function updateFeishuUI() {
    if (!activeSessionId) return;
    $.get('/web/chat/feishu/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
        try {
            var data = resp.data || {};
            var bound = !!data.bound;
            feishuHeaderBtn.toggleClass('bound', bound);
            feishuHeaderLabel.text(bound ? I18n.t('im.connected') : '');
            feishuHeaderBtn.attr('title', bound ? I18n.t('im.feishuBoundUnbind') : I18n.t('im.feishuBind'));
        } catch(e) {}
    }, 'json');
}

// Page load: refresh status
updateFeishuUI();

feishuHeaderBtn.on('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (feishuHeaderBtn.hasClass('bound')) {
        layer.confirm(I18n.t('im.feishuUnbindConfirm'), { title: I18n.t('im.confirmUnbind'), btn: [I18n.t('im.unbind'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            $.post('/web/chat/feishu/unbind?sessionId=' + encodeURIComponent(activeSessionId)).always(function() {
                updateFeishuUI();
            });
        });
        return;
    }
    // Not bound: show bind modal
    showFeishuModal();
});

function showFeishuModal() {
    if (feishuModalOverlay) return;

    feishuModalOverlay = $('<div>').addClass('im-bind-modal-overlay').html(
        '<div class="im-bind-modal" style="min-width:360px">'
        + '<div class="im-bind-modal-title" style="color:#3370ff">' + I18n.t('im.feishuBind') + '</div>'
        + '<div class="im-bind-tabs">'
        + '  <button class="im-bind-tab active" data-tab="qrcode">' + I18n.t('im.scanBind') + '</button>'
        + '  <button class="im-bind-tab" data-tab="credential">' + I18n.t('im.manualInput') + '</button>'
        + '</div>'
        /* === 手动输入 Tab === */
        + '<div class="im-bind-tab-content" id="feishuTabCredential" style="display:none">'
        + '<div class="im-bind-modal-subtitle">' + I18n.t('im.feishuCredentialSubtitle') + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">App ID</label>'
        + '  <input class="im-bind-input" id="feishuAppIdInput" placeholder="' + I18n.t('im.feishuAppIdPlaceholder') + '" />'
        + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">App Secret</label>'
        + '  <input class="im-bind-input" id="feishuAppSecretInput" type="password" placeholder="' + I18n.t('im.feishuAppSecretPlaceholder') + '" />'
        + '</div>'
        + '<div class="im-bind-status" id="feishuBindStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn feishu" id="feishuBindConfirmBtn">' + I18n.t('im.connect') + '</button>'
        + '<div class="im-bind-hint">' + I18n.t('im.feishuBindHint') + '</div>'
        + '</div>'
        /* === 扫码绑定 Tab === */
        + '<div class="im-bind-tab-content" id="feishuTabQrcode">'
        + '<div class="im-bind-modal-subtitle">' + I18n.t('im.feishuScanSubtitle') + '</div>'
        + '<div class="feishu-qr-wrap" id="feishuQrWrap"><span class="feishu-qr-loading">' + I18n.t('im.fetchingQrcode') + '...</span></div>'
        + '<div class="im-bind-status" id="feishuQrStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn feishu" id="feishuQrRefreshBtn" style="display:none">' + I18n.t('im.refreshQrcode') + '</button>'
        + '<div class="im-bind-hint">' + I18n.t('im.feishuChatHint') + '</div>'
        + '</div>'
        + '<button class="im-bind-modal-close" id="feishuModalClose">' + I18n.t('common.cancel') + '</button>'
        + '</div>'
    );
    $('body').append(feishuModalOverlay);

    // Tab切换
    feishuModalOverlay.find('.im-bind-tab').on('click', function() {
        var tab = $(this).data('tab');
        feishuModalOverlay.find('.im-bind-tab').removeClass('active');
        $(this).addClass('active');
        feishuModalOverlay.find('.im-bind-tab-content').hide();
        $('#feishuTab' + tab.charAt(0).toUpperCase() + tab.slice(1)).show();
        if (tab === 'qrcode') {
            startFeishuQrBinding();
        }
    });

    $('#feishuModalClose').on('click', closeFeishuModal);
    feishuModalOverlay.on('click', function(e) {
        if ($(e.target).is(feishuModalOverlay)) closeFeishuModal();
    });

    // 默认扫码 tab，自动获取二维码
    startFeishuQrBinding();

    /* ---- 手动输入 Tab 逻辑 ---- */
    var $appIdInput = $('#feishuAppIdInput');
    var $appSecretInput = $('#feishuAppSecretInput');
    var $statusEl = $('#feishuBindStatus');
    var $confirmBtn = $('#feishuBindConfirmBtn');

    $appIdInput.focus();

    $confirmBtn.on('click', function() {
        var appId = $appIdInput.val().trim();
        var appSecret = $appSecretInput.val().trim();
        if (!appId) {
            $statusEl.text(I18n.t('im.inputAppId')).addClass('error');
            return;
        }
        if (!appSecret) {
            $statusEl.text(I18n.t('im.inputAppSecret')).addClass('error');
            return;
        }
        $statusEl.text(I18n.t('im.startingWsConnection') + '...').removeClass('error scanned');
        $confirmBtn.prop('disabled', true);
        $appIdInput.prop('disabled', true);
        $appSecretInput.prop('disabled', true);

        var params = 'sessionId=' + encodeURIComponent(activeSessionId)
            + '&appId=' + encodeURIComponent(appId)
            + '&appSecret=' + encodeURIComponent(appSecret);

        $.ajax({
            url: '/web/chat/feishu/bind?' + params,
            method: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            if (resp.code === 200) {
                // WebSocket 启动成功，进入等待飞书消息状态
                $statusEl.text(I18n.t('im.feishuConnectSuccessHint') + '...').removeClass('error');
                $confirmBtn.hide();
                // 开始轮询绑定状态
                startFeishuPoll();
            } else {
                $statusEl.text(resp.message || I18n.t('im.connectFailed')).addClass('error');
                $confirmBtn.prop('disabled', false);
                $appIdInput.prop('disabled', false);
                $appSecretInput.prop('disabled', false);
            }
        }).fail(function(jqXhr) {
            if (jqXhr.status) {
                $statusEl.text(I18n.t('im.requestFailed', {status: jqXhr.status})).addClass('error');
            } else {
                $statusEl.text(I18n.t('im.connectFailed')).addClass('error');
            }
            $confirmBtn.prop('disabled', false);
            $appIdInput.prop('disabled', false);
            $appSecretInput.prop('disabled', false);
        });
    });

    // Enter key to confirm
    $appIdInput.add($appSecretInput).on('keydown', function(e) {
        if (e.key === 'Enter' && !isInputComposing(e)) {
            e.preventDefault();
            $confirmBtn.click();
        }
    });

    /* ---- 手动输入绑定轮询逻辑 ---- */
    function startFeishuPoll() {
        if (feishuPollTimer) clearInterval(feishuPollTimer);
        var dotCount = 0;
        feishuPollTimer = setInterval(function() {
            dotCount = (dotCount + 1) % 4;
            var dots = '.'.repeat(dotCount);
            $statusEl.text(I18n.t('im.waitingFeishuMsg') + dots);

            $.get('/web/chat/feishu/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                try {
                    var data = resp.data || {};
                    if (data.bound) {
                        clearInterval(feishuPollTimer);
                        feishuPollTimer = null;
                        $statusEl.text(I18n.t('im.bindSuccess')).removeClass('error').addClass('scanned');
                        setTimeout(function() {
                            closeFeishuModal();
                            updateFeishuUI();
                            switchToChatMode();
                        }, 1000);
                    }
                } catch(e) {}
            }, 'json');
        }, 2000);
    }

    /* ---- 扫码绑定 Tab 逻辑 ---- */
    function startFeishuQrBinding() {
        var $qrWrap = $('#feishuQrWrap');
        var $qrStatus = $('#feishuQrStatus');
        var $refreshBtn = $('#feishuQrRefreshBtn');

        $qrStatus.text('').removeClass('error scanned');
        $refreshBtn.hide();

        $.ajax({
            url: '/web/chat/feishu/qrcode?sessionId=' + encodeURIComponent(activeSessionId),
            method: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            if (resp.code !== 200 || !resp.data) {
                var errMsg = resp.message || I18n.t('im.qrcodeFailed');
                $qrWrap.html('<span style="font-size:13px;color:#666">' + escapeHtml(errMsg) + '</span>');
                $qrStatus.text(errMsg).addClass('error');
                $refreshBtn.show();
                return;
            }
            var qrUrl = resp.data.qrUrl;
            $qrWrap.html('');
            if (qrUrl) {
                var renderFeishuQr = function(err) {
                    if (err || typeof QRCode === 'undefined') {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px;word-break:break-all">' + escapeHtml(qrUrl) + '</span>');
                        $qrStatus.text(I18n.t('im.qrcodeLibFailed')).addClass('error');
                        return;
                    }
                    try {
                        new QRCode($qrWrap[0], { text: qrUrl, width: 180, height: 180 });
                        $qrStatus.text(I18n.t('im.feishuScanQr')).removeClass('error scanned');
                    } catch(e) {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px;word-break:break-all">' + escapeHtml(qrUrl) + '</span>');
                    }
                };
                if (typeof ensureQrcode === 'function') ensureQrcode(renderFeishuQr);
                else renderFeishuQr(null);
            }
            // 开始轮询扫码状态
            startFeishuQrPoll();
        }).fail(function(jqXhr) {
            $qrWrap.html('<span style="font-size:13px;color:#666">' + I18n.t('im.networkFailed') + '</span>');
            $qrStatus.text(I18n.t('im.networkFailed')).addClass('error');
            $refreshBtn.show();
        });
    }

    function startFeishuQrPoll() {
        if (feishuPollTimer) clearInterval(feishuPollTimer);
        var dotCount = 0;
        feishuPollTimer = setInterval(function() {
            $.get('/web/chat/feishu/qrcode/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                try {
                    var data = resp.data || {};
                    var $qrStatus = $('#feishuQrStatus');
                    if (!$qrStatus.length) return;

                    var status = data.status;
                    if (status === 'waiting') {
                        dotCount = (dotCount + 1) % 4;
                        var dots = '.'.repeat(dotCount);
                        $qrStatus.text(I18n.t('im.waitingScan') + dots).removeClass('error scanned');
                    } else if (status === 'success') {
                        $qrStatus.text(I18n.t('im.bindSuccess')).removeClass('error').addClass('scanned');
                        clearInterval(feishuPollTimer);
                        feishuPollTimer = null;
                        setTimeout(function() {
                            closeFeishuModal();
                            updateFeishuUI();
                            switchToChatMode();
                        }, 1200);
                    } else if (status === 'failed') {
                        $qrStatus.text(data.message || I18n.t('im.bindFailed')).addClass('error');
                        clearInterval(feishuPollTimer);
                        feishuPollTimer = null;
                        $('#feishuQrRefreshBtn').show();
                    } else if (status === 'error') {
                        $qrStatus.text(data.message || I18n.t('im.queryStatusFailed')).addClass('error');
                        clearInterval(feishuPollTimer);
                        feishuPollTimer = null;
                        $('#feishuQrRefreshBtn').show();
                    }
                } catch(e) {}
            }, 'json');
        }, 2000);
    }

    // 刷新二维码
    $('#feishuQrRefreshBtn').on('click', function() {
        if (feishuPollTimer) {
            clearInterval(feishuPollTimer);
            feishuPollTimer = null;
        }
        startFeishuQrBinding();
    });
}

function closeFeishuModal() {
    if (feishuPollTimer) {
        clearInterval(feishuPollTimer);
        feishuPollTimer = null;
    }
    if (feishuModalOverlay) {
        feishuModalOverlay.remove();
        feishuModalOverlay = null;
    }
}

/* ===== DingTalk Channel ===== */
var dingtalkHeaderBtn = $('#dingtalkHeaderBtn');
var dingtalkHeaderLabel = $('#dingtalkHeaderLabel');
var dingtalkModalOverlay = null;
var dingtalkPollTimer = null;
var dingtalkStatusTimer = null;
var dingtalkBindCheckTimer = null;

function updateDingTalkUI() {
    if (!activeSessionId) return;
    $.get('/web/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
        try {
            var data = resp.data || {};
            var bound = !!data.bound;
            var pending = !!data.pending;
            if (bound && !pending) {
                // 完全绑定（用户已在钉上发过消息）
                dingtalkHeaderBtn.toggleClass('bound', true).removeClass('pending');
                dingtalkHeaderLabel.text(I18n.t('im.connected'));
                dingtalkHeaderBtn.attr('title', I18n.t('im.dingtalkBoundUnbind'));
            } else if (bound && pending) {
                // 半绑定（扫码成功，等待用户发第一条消息）
                dingtalkHeaderBtn.toggleClass('pending', true).removeClass('bound');
                dingtalkHeaderLabel.text(I18n.t('im.connecting') + '...');
                dingtalkHeaderBtn.attr('title', I18n.t('im.dingtalkWaitingMsg'));
            } else {
                dingtalkHeaderBtn.removeClass('bound pending');
                dingtalkHeaderLabel.text('');
                dingtalkHeaderBtn.attr('title', I18n.t('im.dingtalkBind'));
            }
        } catch(e) {}
    }, 'json');
}

/**
 * 后台轮询钉钉绑定状态。
 * 扫码绑定成功后，等待用户给钉钉机器人发消息完成真正绑定，
 * 一旦检测到 bound=true 自动更新按钮为"已连接"（无需刷新页面）。
 */
function startDingtalkStatusPoll() {
    if (dingtalkStatusTimer) return;
    dingtalkStatusTimer = setInterval(function() {
        if (!activeSessionId) {
            clearInterval(dingtalkStatusTimer);
            dingtalkStatusTimer = null;
            return;
        }
        $.get('/web/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
            try {
                var data = resp.data || {};
                // 只在完全绑定（pending=false）时才停止轮询
                if (data.bound && !data.pending) {
                    clearInterval(dingtalkStatusTimer);
                    dingtalkStatusTimer = null;
                    dingtalkHeaderBtn.toggleClass('bound', true).removeClass('pending');
                    dingtalkHeaderLabel.text(I18n.t('im.connected'));
                    dingtalkHeaderBtn.attr('title', I18n.t('im.dingtalkBoundUnbind'));
                }
            } catch(e) {}
        }, 'json');
    }, 3000);
}

// Page load: refresh status
updateDingTalkUI();

dingtalkHeaderBtn.on('click', function() {
    if (!activeSessionId) return;
    // If already bound, unbind
    if (dingtalkHeaderBtn.hasClass('bound')) {
        layer.confirm(I18n.t('im.dingtalkUnbindConfirm'), { title: I18n.t('im.confirmUnbind'), btn: [I18n.t('im.unbind'), I18n.t('common.cancel')], icon: 3, offset: '120px' }, function(index) {
            layer.close(index);
            $.post('/web/chat/dingtalk/unbind?sessionId=' + encodeURIComponent(activeSessionId)).always(function() {
                updateDingTalkUI();
            });
        });
        return;
    }
    // Not bound: show bind modal
    showDingTalkModal();
});

function showDingTalkModal() {
    if (dingtalkModalOverlay) return;

    dingtalkModalOverlay = $('<div>').addClass('im-bind-modal-overlay').html(
        '<div class="im-bind-modal" style="min-width:360px">'
        + '<div class="im-bind-modal-title" style="color:#0089FF">' + I18n.t('im.dingtalkBind') + '</div>'
        + '<div class="im-bind-tabs">'
        + '  <button class="im-bind-tab active" data-tab="qrcode">' + I18n.t('im.scanBind') + '</button>'
        + '  <button class="im-bind-tab" data-tab="credential">' + I18n.t('im.manualInput') + '</button>'
        + '</div>'
        /* === 手动输入 Tab === */
        + '<div class="im-bind-tab-content" id="dingtalkTabCredential" style="display:none">'
        + '<div class="im-bind-modal-subtitle">' + I18n.t('im.dingtalkCredentialSubtitle') + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">' + I18n.t('im.appKeyLabel') + '</label>'
        + '  <input class="im-bind-input" id="dingtalkAppKeyInput" placeholder="' + I18n.t('im.dingtalkAppKeyPlaceholder') + '" />'
        + '</div>'
        + '<div class="im-bind-input-group">'
        + '  <label class="im-bind-input-label">' + I18n.t('im.appSecretLabel') + '</label>'
        + '  <input class="im-bind-input" id="dingtalkAppSecretInput" type="password" placeholder="' + I18n.t('im.dingtalkAppSecretPlaceholder') + '" />'
        + '</div>'
        + '<div class="im-bind-status" id="dingtalkBindStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn dingtalk" id="dingtalkBindConfirmBtn">' + I18n.t('im.connect') + '</button>'
        + '<div class="im-bind-hint">' + I18n.t('im.dingtalkBindHint') + '</div>'
        + '</div>'
        /* === 扫码绑定 Tab === */
        + '<div class="im-bind-tab-content" id="dingtalkTabQrcode">'
        + '<div class="im-bind-modal-subtitle">' + I18n.t('im.dingtalkScanSubtitle') + '</div>'
        + '<div class="feishu-qr-wrap" id="dingtalkQrWrap"><span class="feishu-qr-loading">' + I18n.t('im.fetchingQrcode') + '...</span></div>'
        + '<div class="im-bind-status" id="dingtalkQrStatus">&nbsp;</div>'
        + '<button class="im-bind-confirm-btn dingtalk" id="dingtalkQrRefreshBtn" style="display:none">' + I18n.t('im.refreshQrcode') + '</button>'
        + '<div class="im-bind-hint">' + I18n.t('im.dingtalkChatHint') + '</div>'
        + '</div>'
        + '<button class="im-bind-modal-close" id="dingtalkModalClose">' + I18n.t('common.cancel') + '</button>'
        + '</div>'
    );
    $('body').append(dingtalkModalOverlay);

    // Tab切换
    dingtalkModalOverlay.find('.im-bind-tab').on('click', function() {
        var tab = $(this).data('tab');
        dingtalkModalOverlay.find('.im-bind-tab').removeClass('active');
        $(this).addClass('active');
        dingtalkModalOverlay.find('.im-bind-tab-content').hide();
        $('#dingtalkTab' + tab.charAt(0).toUpperCase() + tab.slice(1)).show();
        if (tab === 'qrcode') {
            startDingtalkQrBinding();
        }
    });

    $('#dingtalkModalClose').on('click', closeDingTalkModal);
    dingtalkModalOverlay.on('click', function(e) {
        if ($(e.target).is(dingtalkModalOverlay)) closeDingTalkModal();
    });

    // 默认扫码 tab，自动获取二维码
    startDingtalkQrBinding();

    /* ---- 手动输入 Tab 逻辑 ---- */
    var $appKeyInput = $('#dingtalkAppKeyInput');
    var $appSecretInput = $('#dingtalkAppSecretInput');
    var $statusEl = $('#dingtalkBindStatus');
    var $confirmBtn = $('#dingtalkBindConfirmBtn');

    $appKeyInput.focus();

    $confirmBtn.on('click', function() {
        var appKey = $appKeyInput.val().trim();
        var appSecret = $appSecretInput.val().trim();
        if (!appKey) {
            $statusEl.text(I18n.t('im.inputAppKey')).addClass('error');
            return;
        }
        if (!appSecret) {
            $statusEl.text(I18n.t('im.inputAppSecret')).addClass('error');
            return;
        }
        $statusEl.text(I18n.t('im.startingStreamConnection') + '...').removeClass('error scanned');
        $confirmBtn.prop('disabled', true);
        $appKeyInput.prop('disabled', true);
        $appSecretInput.prop('disabled', true);

        var params = 'sessionId=' + encodeURIComponent(activeSessionId)
            + '&appKey=' + encodeURIComponent(appKey)
            + '&appSecret=' + encodeURIComponent(appSecret);

        $.ajax({
            url: '/web/chat/dingtalk/bind?' + params,
            method: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            if (resp.code === 200) {
                // Stream 启动成功，进入等待钉钉消息状态
                $statusEl.text(I18n.t('im.dingtalkConnectSuccessHint') + '...').removeClass('error');
                $confirmBtn.hide();
                // 开始轮询绑定状态
                startDingTalkPoll();
            } else {
                $statusEl.text(resp.message || I18n.t('im.connectFailed')).addClass('error');
                $confirmBtn.prop('disabled', false);
                $appKeyInput.prop('disabled', false);
                $appSecretInput.prop('disabled', false);
            }
        }).fail(function(jqXhr) {
            if (jqXhr.status) {
                $statusEl.text(I18n.t('im.requestFailed', {status: jqXhr.status})).addClass('error');
            } else {
                $statusEl.text(I18n.t('im.connectFailed')).addClass('error');
            }
            $confirmBtn.prop('disabled', false);
            $appKeyInput.prop('disabled', false);
            $appSecretInput.prop('disabled', false);
        });
    });

    function startDingTalkPoll() {
        if (dingtalkPollTimer) clearInterval(dingtalkPollTimer);
        var dotCount = 0;
        dingtalkPollTimer = setInterval(function() {
            dotCount = (dotCount + 1) % 4;
            var dots = '.'.repeat(dotCount);
            $statusEl.text(I18n.t('im.waitingDingtalkMsg') + dots);

            $.get('/web/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                try {
                    var data = resp.data || {};
                    if (data.bound) {
                        // 绑定成功！
                        clearInterval(dingtalkPollTimer);
                        dingtalkPollTimer = null;
                        $statusEl.text(I18n.t('im.bindSuccess')).removeClass('error').addClass('scanned');
                        setTimeout(function() {
                            closeDingTalkModal();
                            updateDingTalkUI();
                            switchToChatMode();
                        }, 1000);
                    }
                } catch(e) {}
            }, 'json');
        }, 2000);
    }

    // Enter key to confirm
    $appKeyInput.add($appSecretInput).on('keydown', function(e) {
        if (e.key === 'Enter' && !isInputComposing(e)) {
            e.preventDefault();
            $confirmBtn.click();
        }
    });

    /* ---- 扫码绑定 Tab 逻辑 ---- */
    function startDingtalkQrBinding() {
        var $qrWrap = $('#dingtalkQrWrap');
        var $qrStatus = $('#dingtalkQrStatus');
        var $refreshBtn = $('#dingtalkQrRefreshBtn');

        // 防止已在轮询中再次触发
        if ($qrWrap.find('canvas').length > 0) return;

        $qrStatus.text('').removeClass('error scanned');
        $refreshBtn.hide();

        $.ajax({
            url: '/web/chat/dingtalk/qrcode?sessionId=' + encodeURIComponent(activeSessionId),
            method: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            if (resp.code !== 200 || !resp.data) {
                var errMsg = resp.message || I18n.t('im.qrcodeFailed');
                $qrWrap.html('<span style="font-size:13px;color:#666">' + escapeHtml(errMsg) + '</span>');
                $qrStatus.text(errMsg).addClass('error');
                $refreshBtn.show();
                return;
            }
            var qrUrl = resp.data.qrUrl;
            $qrWrap.html('');
            if (qrUrl) {
                var renderDingtalkQr = function(err) {
                    if (err || typeof QRCode === 'undefined') {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px;word-break:break-all">' + escapeHtml(qrUrl) + '</span>');
                        $qrStatus.text(I18n.t('im.qrcodeLibFailed')).addClass('error');
                        return;
                    }
                    try {
                        new QRCode($qrWrap[0], { text: qrUrl, width: 180, height: 180 });
                        $qrStatus.text(I18n.t('im.dingtalkScanQr')).removeClass('error scanned');
                    } catch(e) {
                        $qrWrap.html('<span style="font-size:12px;color:#666;padding:10px;word-break:break-all">' + escapeHtml(qrUrl) + '</span>');
                    }
                };
                if (typeof ensureQrcode === 'function') ensureQrcode(renderDingtalkQr);
                else renderDingtalkQr(null);
            }
            // 开始轮询扫码状态
            startDingtalkQrPoll();
        }).fail(function(jqXhr) {
            $qrWrap.html('<span style="font-size:13px;color:#666">' + I18n.t('im.networkFailed') + '</span>');
            $qrStatus.text(I18n.t('im.networkFailed')).addClass('error');
            $refreshBtn.show();
        });
    }

    function startDingtalkQrPoll() {
        if (dingtalkPollTimer) clearInterval(dingtalkPollTimer);
        var dotCount = 0;
        dingtalkPollTimer = setInterval(function() {
            $.get('/web/chat/dingtalk/qrcode/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                try {
                    var data = resp.data || {};
                    var $qrStatus = $('#dingtalkQrStatus');
                    if (!$qrStatus.length) return;

                    var status = data.status;
                    if (status === 'waiting') {
                        dotCount = (dotCount + 1) % 4;
                        var dots = '.'.repeat(dotCount);
                        $qrStatus.text(I18n.t('im.waitingScan') + dots).removeClass('error scanned');
                    } else if (status === 'success') {
                        clearInterval(dingtalkPollTimer);
                        dingtalkPollTimer = null;
                        $qrStatus.text(I18n.t('im.dingtalkScanSuccess')).removeClass('error').addClass('scanned');
                        $('#dingtalkQrRefreshBtn').hide();
                        // 遮罩层变透明、不阻断页面交互，弹窗保持可见等待真正绑定
                        dingtalkModalOverlay.css({ pointerEvents: 'none', background: 'transparent' });
                        dingtalkModalOverlay.find('.im-bind-modal').css('pointerEvents', 'auto');
                        // 开始轮询真正绑定状态
                        dingtalkBindCheckTimer = setInterval(function() {
                            $.get('/web/chat/dingtalk/status?sessionId=' + encodeURIComponent(activeSessionId), function(resp) {
                                try {
                                    var data = resp.data || {};
                                    // bound=true + pending=false 表示用户已在钉钉上发消息完成了绑定
                                    if (data.bound && !data.pending) {
                                        clearInterval(dingtalkBindCheckTimer);
                                        dingtalkBindCheckTimer = null;
                                        $qrStatus.text(I18n.t('im.bindSuccess')).removeClass('error').addClass('scanned');
                                        setTimeout(function() {
                                            closeDingTalkModal();
                                            updateDingTalkUI();
                                            switchToChatMode();
                                            startDingtalkStatusPoll();
                                        }, 800);
                                    }
                                } catch(e) {}
                            }, 'json');
                        }, 2000);
                    } else if (status === 'failed') {
                        $qrStatus.text(data.message || I18n.t('im.bindFailed')).addClass('error');
                        clearInterval(dingtalkPollTimer);
                        dingtalkPollTimer = null;
                        $('#dingtalkQrRefreshBtn').show();
                    } else if (status === 'error') {
                        $qrStatus.text(data.message || I18n.t('im.queryStatusFailed')).addClass('error');
                        clearInterval(dingtalkPollTimer);
                        dingtalkPollTimer = null;
                        $('#dingtalkQrRefreshBtn').show();
                    }
                } catch(e) {}
            }, 'json');
        }, 2000);
    }

    // 刷新二维码
    $('#dingtalkQrRefreshBtn').on('click', function() {
        if (dingtalkPollTimer) {
            clearInterval(dingtalkPollTimer);
            dingtalkPollTimer = null;
        }
        startDingtalkQrBinding();
    });
}

function closeDingTalkModal() {
    if (dingtalkPollTimer) {
        clearInterval(dingtalkPollTimer);
        dingtalkPollTimer = null;
    }
    if (dingtalkBindCheckTimer) {
        clearInterval(dingtalkBindCheckTimer);
        dingtalkBindCheckTimer = null;
    }
    if (dingtalkModalOverlay) {
        dingtalkModalOverlay.remove();
        dingtalkModalOverlay = null;
    }
}

/* ===== 初始化：注册回调 + 激活默认会话 ===== */
onFinishStream = finishStream;
setActiveSession(SESSION_ID);
