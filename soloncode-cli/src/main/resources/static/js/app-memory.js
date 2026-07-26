/**
 * 长期记忆管理面板
 * 复用 gitDiffViewer 容器在中间大面板展示：左侧条目列表 + 右侧编辑区。
 * 后端接口：/web/chat/memory/{list,get,save,remove}
 */
(function () {
    'use strict';

    var gitDiffViewer = document.getElementById('gitDiffViewer');
    var gitViewerContent = document.getElementById('gitViewerContent');
    var gitViewerLabel = document.getElementById('gitViewerLabel');
    var gitViewerFile = document.getElementById('gitViewerFile');
    var welcomeView = document.getElementById('welcomeView');
    var chatView = document.getElementById('chatView');
    var memoryNavBtn = document.getElementById('memoryNavBtn');
    var memoryBadge = document.getElementById('memoryBadge');

    // 当前展开的 key（null 表示无展开；'__new__' 表示新建态）
    var expandedKey = null;
    var memoryList = [];
    // 已加载的详情缓存：key -> {content, importance}
    var detailCache = {};
    var NEW_KEY = '__new__';

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    var sidebar = document.querySelector('.sidebar');

    // ---- 中间面板显隐（参与 flex 布局：占据左侧边栏之后的整个区域，随边栏收起响应式变宽）----
    function showViewer() {
        if (!gitDiffViewer) return;
        if (welcomeView) welcomeView.style.display = 'none';
        if (chatView) chatView.style.display = 'none';
        document.body.classList.add('memory-active');
        gitDiffViewer.classList.add('mem-overlay');
        gitDiffViewer.style.display = 'flex';

        if (gitViewerLabel) gitViewerLabel.textContent = '长期记忆';
        if (gitViewerFile) gitViewerFile.textContent = '';

        // 记忆面板 header 保留「新建」「全屏」「关闭」：显式复位 header 按钮，
        // 避免看过审查/文件详情后 MD 切换、复制按钮的显隐状态残留过来
        var _mdToggle = document.getElementById('gitViewerMdToggle');
        var _copyBtn = document.getElementById('gitViewerCopyBtn');
        var _fullscreenBtn = document.getElementById('gitViewerFullscreen');
        var _memNewBtn = document.getElementById('gitViewerMemNew');
        if (_mdToggle) _mdToggle.style.display = 'none';
        if (_copyBtn) _copyBtn.style.display = 'none';
        if (_fullscreenBtn) _fullscreenBtn.style.display = '';
        if (_memNewBtn) _memNewBtn.style.display = '';

        // 清理 git 模块可能残留的操作栏
        var oldActions = gitDiffViewer.querySelector('.git-viewer-actions');
        if (oldActions) oldActions.remove();
    }

    // ---- 关闭：移除状态类，恢复顶部条与右侧任务面板，避免残留影响 git diff 内嵌视图 ----
    function closeOverlay() {
        if (!gitDiffViewer) return;
        document.body.classList.remove('memory-active');
        gitDiffViewer.classList.remove('mem-overlay');
    }

    // ---- 打开面板 ----
    function openMemoryViewer() {
        showViewer();
        expandedKey = null;
        renderShell();
        loadMemoryList();
    }

    // ---- 注入面板骨架 ----
    function renderShell() {
        if (!gitViewerContent) return;
        gitViewerContent.innerHTML =
            '<div class="mem-panel">' +
            '  <div class="mem-list" id="memList"></div>' +
            '</div>';
    }

    // ---- 加载列表 ----
    function loadMemoryList() {
        fetch('/web/chat/memory/list')
            .then(function (r) { return r.json(); })
            .then(function (res) {
                memoryList = (res && res.code === 200 && Array.isArray(res.data)) ? res.data : [];
                // 重要度倒序，时间倒序
                memoryList.sort(function (a, b) {
                    var d = (b.importance || 0) - (a.importance || 0);
                    if (d !== 0) return d;
                    return String(b.time || '').localeCompare(String(a.time || ''));
                });
                renderList('');
                updateBadge();
            })
            .catch(function () {
                memoryList = [];
                renderList('');
            });
    }

    function updateBadge() {
        if (!memoryBadge) return;
        if (memoryList.length > 0) {
            memoryBadge.textContent = memoryList.length;
            memoryBadge.style.display = '';
        } else {
            memoryBadge.style.display = 'none';
        }
    }

    // ---- 渲染列表（手风琴，单栏全宽）----
    function renderList(filter) {
        var listEl = document.getElementById('memList');
        if (!listEl) return;

        var items = memoryList;
        if (filter) {
            var f = filter.toLowerCase();
            items = memoryList.filter(function (it) {
                return String(it.key || '').toLowerCase().indexOf(f) >= 0
                    || String(it.content || '').toLowerCase().indexOf(f) >= 0;
            });
        }

        var html = '';

        // 新建态：置顶展开一个空白编辑行
        if (expandedKey === NEW_KEY) {
            html += rowHtml({ key: '', importance: 5, time: '' }, true, true);
        }

        if (items.length === 0 && expandedKey !== NEW_KEY) {
            listEl.innerHTML = '<div class="mem-list-empty">暂无记忆</div>';
            return;
        }

        items.forEach(function (it) {
            var isOpen = (it.key === expandedKey);
            html += rowHtml(it, isOpen, false);
        });
        listEl.innerHTML = html;

        bindRows(listEl);
    }

    // ---- 单行 HTML（收起头 + 可选展开体）----
    function rowHtml(it, isOpen, isNew) {
        var imp = Math.round(it.importance || 0);
        var keyText = isNew ? '新建记忆' : escapeHtml(it.key);
        var caret = '<svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6 4 10 8 6 12"></polyline></svg>';
        var openCls = isOpen ? ' open' : '';
        var dataKey = isNew ? NEW_KEY : escapeHtml(it.key);

        var head =
            '<div class="mem-row-head" data-key="' + dataKey + '">' +
            '  <span class="mem-caret">' + caret + '</span>' +
            '  <span class="mem-row-star">★' + imp + '</span>' +
            '  <span class="mem-row-key">' + keyText + '</span>' +
            '  <span class="mem-row-time">' + escapeHtml(it.time || '') + '</span>' +
            '</div>';

        var body = '';
        if (isOpen) {
            body = editorHtml(dataKey, isNew);
        }

        return '<div class="mem-row' + openCls + '" data-key="' + dataKey + '">' + head + body + '</div>';
    }

    // ---- 展开体：编辑表单 ----
    function editorHtml(dataKey, isNew) {
        // 内容从缓存取（loadDetail 已异步填充），未命中先留空占位
        var cached = detailCache[dataKey] || {};
        var content = isNew ? '' : (cached.content != null ? cached.content : '');
        var imp = isNew ? 5 : (cached.importance != null ? cached.importance : 5);
        var keyVal = isNew ? '' : dataKey;
        var keyReadonly = isNew ? '' : 'readonly';

        return '<div class="mem-row-body">' +
            '  <div class="mem-form">' +
            '    <div class="mem-form-row">' +
            '      <label class="mem-field mem-field-key"><span>Key</span>' +
            '        <input type="text" class="mem-key" value="' + escapeHtml(keyVal) + '" ' + keyReadonly + ' placeholder="唯一标识，如 project-build" /></label>' +
            '      <label class="mem-field mem-field-imp"><span>重要度 (1-10)</span>' +
            '        <input type="number" class="mem-imp" min="1" max="10" value="' + imp + '" /></label>' +
            '    </div>' +
            '    <label class="mem-field"><span>内容</span>' +
            '      <textarea class="mem-content" placeholder="记忆内容（支持 markdown）">' + escapeHtml(content) + '</textarea></label>' +
            '    <div class="mem-actions">' +
            '      <button class="mem-btn mem-btn-primary mem-save">保存</button>' +
            (isNew ? '      <button class="mem-btn mem-cancel">取消</button>' : '      <button class="mem-btn mem-btn-danger mem-del">删除</button>') +
            '' +
            '    </div>' +
            '  </div>' +
            '</div>';
    }

    // ---- 绑定行事件 ----
    function bindRows(listEl) {
        Array.prototype.forEach.call(listEl.querySelectorAll('.mem-row-head'), function (head) {
            head.addEventListener('click', function () {
                toggleRow(head.getAttribute('data-key'));
            });
        });
        Array.prototype.forEach.call(listEl.querySelectorAll('.mem-row.open'), function (row) {
            var saveBtn = row.querySelector('.mem-save');
            if (saveBtn) saveBtn.addEventListener('click', function (e) { e.stopPropagation(); saveMemory(row); });
            var cancelBtn = row.querySelector('.mem-cancel');
            if (cancelBtn) cancelBtn.addEventListener('click', function (e) { e.stopPropagation(); expandedKey = null; renderList(currentFilter()); });
            var delBtn = row.querySelector('.mem-del');
            if (delBtn) delBtn.addEventListener('click', function (e) { e.stopPropagation(); removeMemory(row.getAttribute('data-key')); });
            // 阻止点 body 冒泡到 head 触发收起
            var body = row.querySelector('.mem-row-body');
            if (body) body.addEventListener('click', function (e) { e.stopPropagation(); });
        });
    }

    // ---- 展开/收起 ----
    function toggleRow(key) {
        if (expandedKey === key) {
            expandedKey = null;
            renderList(currentFilter());
            return;
        }
        expandedKey = key;
        renderList(currentFilter());
        if (key !== NEW_KEY) loadDetail(key);
    }

    // ---- 新建态 ----
    function startCreate() {
        expandedKey = NEW_KEY;
        renderList(currentFilter());
    }

    // ---- 加载详情并回填展开体 ----
    function loadDetail(key) {
        if (detailCache[key] != null) return; // 已缓存则跳过
        fetch('/web/chat/memory/get?key=' + encodeURIComponent(key))
            .then(function (r) { return r.json(); })
            .then(function (res) {
                if (res && res.code === 200 && res.data) {
                    detailCache[key] = { content: res.data.content, importance: res.data.importance };
                    // 若仍处于展开态，回填内容
                    if (expandedKey === key) fillOpenRow(key);
                }
            })
            .catch(function () {});
    }

    function fillOpenRow(key) {
        var listEl = document.getElementById('memList');
        if (!listEl) return;
        var row = listEl.querySelector('.mem-row.open[data-key="' + cssEscape(key) + '"]');
        if (!row) return;
        var d = detailCache[key] || {};
        var ta = row.querySelector('.mem-content');
        if (ta && d.content != null) ta.value = d.content;
        var impEl = row.querySelector('.mem-imp');
        if (impEl && d.importance != null) impEl.value = d.importance;
    }

    function cssEscape(s) {
        return String(s).replace(/["\\]/g, '\\$&');
    }

    function currentFilter() {
        return '';
    }

    function memToast(msg, isError) {
        if (typeof layer !== 'undefined' && layer.msg) {
            layer.msg(msg, { icon: isError ? 2 : 1, time: 2500, offset: '120px' });
        } else {
            alert(msg);
        }
    }

    // ---- 保存 ----
    function saveMemory(row) {
        var key = (row.querySelector('.mem-key').value || '').trim();
        var content = (row.querySelector('.mem-content').value || '').trim();
        var imp = parseInt(row.querySelector('.mem-imp').value, 10);

        if (!key) { memToast('Key 不能为空', true); return; }
        if (!content) { memToast('内容不能为空', true); return; }
        if (isNaN(imp) || imp < 1 || imp > 10) { memToast('重要度需在 1-10 之间', true); return; }

        var form = new URLSearchParams();
        form.append('key', key);
        form.append('content', content);
        form.append('importance', imp);

        fetch('/web/chat/memory/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: form.toString()
        })
            .then(function (r) { return r.json(); })
            .then(function (res) {
                if (res && res.code === 200) {
                    detailCache[key] = { content: content, importance: imp };
                    expandedKey = key;
                    loadMemoryList();
                    memToast('保存成功', false);
                } else {
                    memToast((res && res.description) || '保存失败', true);
                }
            })
            .catch(function (e) { memToast('保存失败: ' + e.message, true); });
    }

    // ---- 删除 ----
    function removeMemory(key) {
        if (!key || key === NEW_KEY) return;

        var doRemove = function () {
            var form = new URLSearchParams();
            form.append('key', key);

            fetch('/web/chat/memory/remove', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: form.toString()
            })
                .then(function (r) { return r.json(); })
                .then(function (res) {
                    if (res && res.code === 200) {
                        delete detailCache[key];
                        expandedKey = null;
                        loadMemoryList();
                    }
                })
                .catch(function () {});
        };

        if (typeof layer !== 'undefined' && layer.confirm) {
            layer.confirm('确定删除记忆「' + key + '」？', { title: '确认删除', btn: ['删除', '取消'], icon: 3, offset: '120px' }, function (index) {
                layer.close(index);
                doRemove();
            });
        } else if (window.confirm('确定删除记忆「' + key + '」？')) {
            doRemove();
        }
    }

    // ---- 绑定导航按钮 ----
    if (memoryNavBtn) {
        memoryNavBtn.addEventListener('click', openMemoryViewer);
    }

    // 关闭按钮（与 git 模块共享）：隐藏的同时清理覆盖层状态
    var gitViewerClose = document.getElementById('gitViewerClose');
    if (gitViewerClose) {
        gitViewerClose.addEventListener('click', closeOverlay);
    }

    // header 「新建」按钮（与 git 模块共享容器，仅记忆面板时显示）
    var gitViewerMemNew = document.getElementById('gitViewerMemNew');
    if (gitViewerMemNew) {
        gitViewerMemNew.addEventListener('click', function () { startCreate(); });
    }
    // Esc 关闭时一并清理
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeOverlay();
    });

    // 首次进入时刷新一次徽标
    loadMemoryList();

    // 暴露给外部
    window.openMemoryViewer = openMemoryViewer;
})();
